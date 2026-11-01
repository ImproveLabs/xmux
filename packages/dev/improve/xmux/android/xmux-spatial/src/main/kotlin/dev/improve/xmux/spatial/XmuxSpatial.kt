package dev.improve.xmux.spatial

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkGrabMovementType
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkPanelDimensions
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.ResizeMode
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.client.PaneId
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.client.TerminalHandle
import dev.improve.xmux.client.TerminalId
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.screen.layout.PxSize
import dev.improve.xmux.screen.view.ScreenView
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.terminal.grid.TerminalGridView
import dev.improve.xmux.terminal.window.TerminalWindow
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.xmux.window.frame.WindowFrame

/** What a grid window shows: a [CellGrid] the embedder fills itself (demos, custom content). */
public class WindowContent(
  public val state: TerminalWindowState,
  public val grid: CellGrid,
  public val chrome: WindowChrome = WindowChrome.Default,
)

/**
 * A mounted window: a grid window ([content] set) or one cmux screen ([screen] set), its entity
 * and its view once the panel is inflated.
 */
public class ScreenWindow internal constructor(
  public val index: Int,
  public val content: WindowContent?,
  public val screen: ScreenId?,
  public val isLayer: Boolean,
  internal val binding: ScreenBinding?,
  internal var spec: PanelSpec,
  /** Header configuration: colors, traffic-light callbacks and the leading/trailing slots. */
  public val chrome: WindowChrome,
) {
  public var entity: Entity? = null
    internal set
  /** The grid view of a grid window. */
  public var view: TerminalGridView? = null
    internal set
  /** The screen view of a cmux screen window. */
  public var screenView: ScreenView? = null
    internal set
  internal var pending: Placement? = null

  /** The screen view's last laid-out size; differs from the spec's body after a Relayout resize. */
  @Volatile internal var laidOutBody: PxSize? = null
  /** When a grid resize was requested; the panel snaps back to its grids if none arrives by then. */
  internal var gridResizeDeadline: Long = 0L
  /** The body a snap-back already answered, so a layout quirk cannot respawn the panel forever. */
  internal var snappedBody: PxSize? = null

  public val state: TerminalWindowState get() = content?.state ?: binding!!.state
  public val session: XmuxSession? get() = binding?.session

  /** The focused pane of a cmux screen window: the last one tapped or focused from code, else cmux's. */
  public val focusedPane: PaneId? get() = binding?.focusedPaneId

  /** The active terminal of [focusedPane]; `session.terminal(it)` writes to it. */
  public val focusedTerminal: TerminalId? get() = binding?.focusedTerminalId()

  /** Panel size in pixels (the header included); [WindowHit.x] and [WindowHit.y] use this space. */
  public val panelSize: PanelSpec get() = spec

  /** Redraws the panel if anything it shows changed since the last frame. */
  public fun refreshIfDirty() {
    view?.refreshIfDirty()
    binding?.refreshIfDirty()
  }

  public fun setPose(pose: Pose) {
    entity?.setComponent(Transform(pose))
  }

  public fun pose(): Pose? = entity?.tryGetComponent<Transform>()?.transform

  /** What is under texture coordinate ([u], [v]) of this window's panel (top-left origin, 0..1). */
  internal fun hitAt(u: Float, v: Float, point: Vector3?): WindowHit? {
    val (x, y) = PanelHit.pixel(u, v, spec) ?: return null
    val bodyY = PanelHit.bodyY(y, chrome.headerHeightDp)
      ?: return WindowHit(this, x, y, null, null, -1, -1, onHeader = true, point)
    screenView?.let { sv ->
      val h = sv.hit(x, bodyY) ?: return WindowHit(this, x, y, null, null, -1, -1, onHeader = false, point)
      val pane = PaneId(h.pane)
      return WindowHit(this, x, y, pane, binding?.terminalOf(pane), h.row, h.col, onHeader = false, point)
    }
    val cell = view?.cellAt(x, bodyY)
    return WindowHit(this, x, y, null, null, cell?.first ?: -1, cell?.second ?: -1, onHeader = false, point)
  }
}

/**
 * Hosts xmux windows inside a Meta Spatial SDK activity. The host forwards four calls
 * ([registerFeatures], [registerPanels], [onSceneReady], [onSceneTick]) and, for keyboards,
 * [dispatchKeyEvent]; everything else is [mount], [follow] and [unmount].
 */
public class XmuxSpatial(
  private val activity: AppSystemActivity,
  public val config: SpatialConfig = SpatialConfig(),
) {
  public val font: TerminalFont by lazy { TerminalFont(activity, config.fontPx) }
  /** Panel size of a grid window ([SpatialConfig.cols] x [SpatialConfig.rows]). */
  public val spec: PanelSpec by lazy {
    PanelSpec.of(font, config.cols, config.rows, config.paddingPx, WindowChrome.Default.headerHeightDp, config.windowWidthM)
  }

  /** Physical size of one panel pixel, so every window shares one text size. */
  private val metresPerPx: Float get() = config.windowWidthM / spec.widthPx

  private val mounted = ArrayList<ScreenWindow>()
  private val terminals = TerminalRefs()
  private var sceneReady = false
  private var nextRegistration = BASE_REGISTRATION_ID
  private var following: XmuxSession? = null
  private var followPlacement: (Int, Int) -> Placement = { i, n -> if (n == 1) Placement.InFrontOfHead else Placement.Arc(i, n) }
  private var followChrome: (ScreenId) -> WindowChrome = { WindowChrome.Default }

  public val windows: List<ScreenWindow> get() = mounted

  /**
   * With [SpatialConfig.resizeGrids], called on the main thread when the user resized [window]:
   * [sizes] holds the grid each of its terminals needs to fill the new size (only those that
   * change, background tabs included). The default asks cmux through this viewer
   * ([TerminalHandle.resize]) for the terminals the window shows; replace it to apply the size
   * another way. The window follows once the new grids arrive, and snaps back to its current
   * grids if none do within a few seconds.
   */
  public var onGridResize: (window: ScreenWindow, sizes: Map<TerminalId, GridSize>) -> Unit = { window, sizes ->
    val session = window.session
    val shown = window.screen?.let { session?.topology?.value?.screen(it) }?.panes?.mapNotNull { it.activeTerminal }.orEmpty()
    if (session != null) for ((terminal, size) in sizes) if (terminal in shown) session.terminal(terminal).resize(size.cols, size.rows)
  }

  /** The window that last got a tap; keyboard input goes to its focused pane. */
  public var focused: ScreenWindow? = null
    private set

  /** Default features; a host that already supplies VRFeature/ComposeFeature can skip this. */
  public fun registerFeatures(): List<SpatialFeature> =
    listOf(
      VRFeature(activity, inputSystemType = VrInputSystemType.INTERACTION_SDK, handMicrogestureLocomotion = false),
      ComposeFeature(),
    )

  /** Panels are registered on [mount], so nothing is registered up front. */
  public fun registerPanels(): List<PanelRegistration> = emptyList()

  public fun onSceneReady() {
    val scene = activity.scene
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    if (config.enablePassthrough) scene.enablePassthrough(true)
    scene.setViewOrigin(0f, 0f, 0f, 0f)
    sceneReady = true
    for (w in mounted) if (w.entity == null) spawn(w)
  }

  public fun onSceneTick() {
    following?.let { syncScreens(it) }
    for (w in mounted.toList()) {
      val b = w.binding ?: continue
      if (b.gone) {
        unmount(w)
        continue
      }
      if (b.tick() && w.entity != null) respawn(w)
    }
    if (config.resizeGrids) for (w in mounted.toList()) settleGridResize(w)
    for (w in mounted) w.refreshIfDirty()
    if (mounted.none { it.pending != null }) return
    val head = headPose() ?: return
    for (w in mounted) {
      val placement = w.pending ?: continue
      w.setPose(WindowLayout.resolve(placement, head, config, w.spec))
      w.pending = null
    }
  }

  /** Mounts a grid window for [content] (now, or when the scene is ready). */
  public fun mount(content: WindowContent, placement: Placement = Placement.InFrontOfHead): ScreenWindow {
    val index = mounted.size
    val header = content.chrome.headerHeightDp
    val windowSpec = if (header == WindowChrome.Default.headerHeightDp) spec else PanelSpec.of(font, config.cols, config.rows, config.paddingPx, header, config.windowWidthM)
    val window = ScreenWindow(index, content, null, index < config.maxLayers, null, windowSpec, content.chrome)
    window.pending = placement
    mounted.add(window)
    if (sceneReady) spawn(window)
    return window
  }

  /**
   * Mounts a window that shows [screen] of [session] and follows its panes, tabs and size.
   * [chrome] sets the header (for example a `trailingSlot` with your own indicator).
   */
  public fun mount(
    session: XmuxSession,
    screen: ScreenId,
    placement: Placement = Placement.InFrontOfHead,
    chrome: WindowChrome = WindowChrome.Default,
  ): ScreenWindow {
    val index = mounted.size
    lateinit var window: ScreenWindow
    val binding = ScreenBinding(session, screen, font, terminals) { focused = window }
    binding.tick()
    window = ScreenWindow(index, null, screen, index < config.maxLayers, binding, specFor(binding, chrome), chrome)
    window.pending = placement
    mounted.add(window)
    if (sceneReady) spawn(window)
    return window
  }

  /**
   * Keeps one window per screen of [session]: new screens are mounted ([placement] gets the
   * index and count, [chrome] the screen), closed ones unmounted. Null stops following.
   */
  public fun follow(
    session: XmuxSession?,
    placement: (index: Int, count: Int) -> Placement = followPlacement,
    chrome: (screen: ScreenId) -> WindowChrome = followChrome,
  ) {
    following = session
    followPlacement = placement
    followChrome = chrome
  }

  private fun syncScreens(session: XmuxSession) {
    val screens = session.topology.value.screens
    if (screens.isEmpty()) return
    val shown = mounted.filter { it.session === session }.mapNotNull { it.screen }.toSet()
    val missing = screens.filter { it.id !in shown }
    for ((i, s) in missing.withIndex()) mount(session, s.id, followPlacement(shown.size + i, screens.size), followChrome(s.id))
  }

  /**
   * The nearest xmux window a world-space ray hits within [maxDistanceM], and the pane, terminal
   * and cell under it; null when it hits none. Each window is intersected at its current pose and
   * size, so windows the user moved or resized are handled. Only xmux windows are considered:
   * if your own panels can be in front, test them first. Call on the main thread, typically from
   * `onSceneTick`.
   */
  public fun hitTest(origin: Vector3, direction: Vector3, maxDistanceM: Float = 10f): WindowHit? {
    // The scene raycast (Scene.lineSegmentIntersect) is not used: on a Quest 3 it passed through
    // screen windows it had hit moments before, so windows are intersected geometrically.
    var best: WindowHit? = null
    var bestDistance = maxDistanceM
    for (w in mounted) {
      val entity = w.entity ?: continue
      val pose = entity.tryGetComponent<Transform>()?.transform ?: continue
      val size = entity.tryGetComponent<IsdkPanelDimensions>()?.dimensions
      val ray = PanelHit.intersect(origin, direction, pose, size?.x ?: w.spec.widthM, size?.y ?: w.spec.heightM) ?: continue
      if (ray.distance > bestDistance) continue
      best = w.hitAt(ray.u, ray.v, ray.point) ?: continue
      bestDistance = ray.distance
    }
    return best
  }

  /** The window and cell for a [HitInfo] from your own raycast or panel input listener, if it is on an xmux window. */
  public fun hitTest(hit: HitInfo): WindowHit? {
    val window = mounted.firstOrNull { it.entity != null && it.entity == hit.entity } ?: return null
    val uv = hit.textureCoordinate
    return window.hitAt(uv.x, uv.y, hit.point)
  }

  /**
   * Makes [window] the one keyboard input goes to and, for a cmux screen window, focuses [pane]
   * (here and in cmux). Use it when you move focus by ray or gesture rather than by a tap.
   */
  public fun focus(window: ScreenWindow, pane: PaneId? = null) {
    require(window in mounted) { "window is not mounted" }
    focused = window
    if (pane != null) window.binding?.focusPane(pane)
  }

  /**
   * The terminal keyboard input goes to: the focused pane of [focused], or of the first cmux
   * screen window when nothing has been focused yet. Write text or keys to it directly.
   */
  public fun focusedTerminal(): TerminalHandle? {
    val window = focused?.takeIf { it.binding != null } ?: mounted.firstOrNull { it.binding != null } ?: return null
    val id = window.focusedTerminal ?: return null
    return window.session?.terminal(id)
  }

  public fun unmount(window: ScreenWindow) {
    window.entity?.destroy()
    window.entity = null
    window.binding?.dispose()
    if (focused === window) focused = null
    mounted.remove(window)
  }

  /**
   * Sends a key press to the focused window's focused pane. Forward the activity's
   * `dispatchKeyEvent` here; returns true when xmux used the event.
   */
  public fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN) return false
    val window = focused ?: mounted.firstOrNull { it.binding != null } ?: return false
    return window.binding?.key(event) ?: false
  }

  private fun specFor(binding: ScreenBinding, chrome: WindowChrome): PanelSpec {
    val size = binding.size
    val header = chrome.headerHeightDp + 1
    val w = size.width.coerceAtLeast(spec.widthPx / 4)
    val h = size.height.coerceAtLeast(spec.heightPx / 4) + header
    return PanelSpec(w, h, w * metresPerPx)
  }

  /**
   * Resizes a cmux screen window to about [widthM] x [heightM] (header included) the way a corner
   * drag does with [SpatialConfig.resizeGrids]: [onGridResize] gets the grids that fill that size,
   * and the window follows them. Call on the main thread.
   */
  public fun resizeWindow(window: ScreenWindow, widthM: Float, heightM: Float) {
    require(config.resizeGrids) { "SpatialConfig.resizeGrids is off" }
    require(window in mounted && window.binding != null) { "not a mounted cmux screen window" }
    val panel = PxSize((widthM / metresPerPx).roundToInt(), (heightM / metresPerPx).roundToInt())
    window.laidOutBody = PxSize(panel.width, panel.height - window.chrome.headerHeightDp - 1)
  }

  /**
   * A Relayout resize leaves the panel at the user's size with the old grids in it. Ask for the
   * grids that fill it; when nothing would change (less than a cell), or no new grid arrives in
   * time, re-create the panel at its grids' size.
   */
  private fun settleGridResize(window: ScreenWindow) {
    val binding = window.binding ?: return
    if (window.entity == null) return
    val body = window.laidOutBody
    if (body == null) {
      if (window.gridResizeDeadline != 0L && SystemClock.uptimeMillis() > window.gridResizeDeadline) {
        Log.i(TAG, "screen ${window.screen?.value}: no new grid arrived; restoring the panel")
        window.gridResizeDeadline = 0L
        respawn(window)
      }
      return
    }
    window.laidOutBody = null
    val expected = bodySize(window.spec, window.chrome)
    val cell = binding.font
    if (abs(body.width - expected.width) < cell.cellWidth / 2 && abs(body.height - expected.height) < cell.cellHeight / 2) return
    if (body.width <= 0 || body.height <= 0) return
    val sizes = binding.gridsFor(body)
    Log.i(TAG, "screen ${window.screen?.value}: resized to ${body.width}x${body.height} px (was ${expected.width}x${expected.height}); grids $sizes")
    if (sizes.isNotEmpty()) {
      window.snappedBody = null
      window.gridResizeDeadline = SystemClock.uptimeMillis() + GRID_RESIZE_TIMEOUT_MS
      onGridResize(window, sizes)
    } else if (window.snappedBody != body) {
      window.snappedBody = body
      respawn(window)
    }
  }

  private fun bodySize(spec: PanelSpec, chrome: WindowChrome): PxSize = PxSize(spec.widthPx, spec.heightPx - chrome.headerHeightDp - 1)

  /** Re-creates a screen window's panel at its new size, keeping its pose. */
  private fun respawn(window: ScreenWindow) {
    window.gridResizeDeadline = 0L
    window.laidOutBody = null
    val pose = window.pose()
    window.entity?.destroy()
    window.entity = null
    window.screenView = null
    window.binding?.view = null
    window.spec = specFor(window.binding!!, window.chrome)
    spawn(window)
    if (pose != null) window.setPose(pose)
  }

  private fun spawn(window: ScreenWindow) {
    val registrationId = nextRegistration++
    activity.registerPanel(registration(registrationId, window))
    window.entity =
      Entity.create(
        listOf(
          Panel(registrationId),
          // Fallback pose until the head pose is known: +Z in front of the origin, identity
          // rotation faces a viewer at the origin.
          Transform(Pose(Vector3(0f, 1.3f, config.spawnDistanceM), Quaternion(0f, 0f, 0f))),
          IsdkGrabbable(movementType = IsdkGrabMovementType.AxialBillboard),
          if (config.resizeGrids && window.binding != null) {
            // The panel's pixels follow its size (no stretching); settleGridResize turns them into grids.
            IsdkPanelResize(
              resizeMode = ResizeMode.Relayout,
              minDimensions = Vector2(0.2f, 0.14f),
              maxDimensions = Vector2(4f, 3f),
              preserveAspectRatio = false,
            )
          } else {
            IsdkPanelResize(
              resizeMode = ResizeMode.Simple,
              minDimensions = Vector2(0.2f, 0.14f),
              maxDimensions = Vector2(4f, 3f),
              preserveAspectRatio = true,
            )
          },
        ),
      )
  }

  private fun registration(id: Int, window: ScreenWindow): PanelRegistration {
    val spec = window.spec
    return ComposeViewPanelRegistration(
      id,
      composeViewCreator = { _, ctx ->
        ComposeView(ctx).apply {
          setContent {
            val content = window.content
            val binding = window.binding
            if (content != null) {
              TerminalWindow(content.state, content.grid, font, Modifier.fillMaxSize(), content.chrome) {
                window.view = it
              }
            } else if (binding != null) {
              WindowFrame(
                binding.state,
                Modifier.fillMaxSize(),
                window.chrome,
                body = { c -> ScreenView(c, font) },
                onViewCreated = {
                  val v = it as ScreenView
                  window.screenView = v
                  binding.view = v
                  binding.tick()
                  if (config.resizeGrids) {
                    v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                      if (window.screenView === view) window.laidOutBody = PxSize(view.width, view.height)
                    }
                  }
                },
              )
            }
          }
        }
      },
      settingsCreator = {
        UIPanelSettings(
          shape = QuadShapeOptions(width = spec.widthM, height = spec.heightM),
          display = DpDisplayOptions(spec.widthPx.toFloat(), spec.heightPx.toFloat(), 160),
          rendering =
            UIPanelRenderOptions(
              if (window.isLayer) {
                PanelRenderMode.Layer(layerBlendType = PanelShapeLayerBlendType.ALPHA_BLEND, enableLayerFeatheredEdge = true)
              } else PanelRenderMode.Mesh(),
            ),
          style = PanelStyleOptions(themeResourceId = R.style.XmuxPanelThemeTransparent),
        )
      },
    )
  }

  /**
   * Mounts a plain Compose panel (a pairing screen, settings) of [widthPx] x [heightPx] panel
   * pixels at the shared text scale. Returns its entity; destroy it to remove the panel.
   */
  public fun mountPanel(widthPx: Int, heightPx: Int, placement: Placement = Placement.InFrontOfHead, content: @Composable () -> Unit): Entity {
    val registrationId = nextRegistration++
    val widthM = widthPx * metresPerPx
    val heightM = heightPx * metresPerPx
    activity.registerPanel(
      ComposeViewPanelRegistration(
        registrationId,
        composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { content() } } },
        settingsCreator = {
          UIPanelSettings(
            shape = QuadShapeOptions(width = widthM, height = heightM),
            display = DpDisplayOptions(widthPx.toFloat(), heightPx.toFloat(), 160),
            rendering = UIPanelRenderOptions(PanelRenderMode.Layer(layerBlendType = PanelShapeLayerBlendType.ALPHA_BLEND, enableLayerFeatheredEdge = true)),
            style = PanelStyleOptions(themeResourceId = R.style.XmuxPanelThemeTransparent),
          )
        },
      ),
    )
    val entity =
      Entity.create(
        listOf(
          Panel(registrationId),
          Transform(Pose(Vector3(0f, 1.3f, config.spawnDistanceM), Quaternion(0f, 0f, 0f))),
          IsdkGrabbable(movementType = IsdkGrabMovementType.AxialBillboard),
        ),
      )
    val head = headPose()
    if (head != null) entity.setComponent(Transform(WindowLayout.resolve(placement, head, config, PanelSpec(widthPx, heightPx, widthM))))
    return entity
  }

  private fun headPose(): Pose? {
    val head =
      Query.where { has(AvatarAttachment.id) }
        .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }
        .eval()
        .firstOrNull() ?: return null
    val pose = head.tryGetComponent<Transform>()?.transform ?: return null
    return if (WindowLayout.isUsableHeadPose(pose)) pose else null
  }

  public companion object {
    public const val BASE_REGISTRATION_ID: Int = 10_000
    private const val GRID_RESIZE_TIMEOUT_MS = 3_000L
    private const val TAG = "XmuxSpatial"
  }
}
