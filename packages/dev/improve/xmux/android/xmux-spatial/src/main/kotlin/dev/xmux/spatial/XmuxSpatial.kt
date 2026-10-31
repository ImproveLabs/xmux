package dev.xmux.spatial

import androidx.compose.foundation.layout.fillMaxSize
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
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.ResizeMode
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
import dev.xmux.compose.TerminalWindow
import dev.xmux.compose.TerminalWindowState
import dev.xmux.compose.WindowChrome
import dev.xmux.core.CellGrid
import dev.xmux.core.TerminalFont
import dev.xmux.core.TerminalGridView
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import dev.xmux.client.ScreenId
import dev.xmux.client.XmuxSession
import dev.xmux.compose.WindowFrame
import dev.xmux.core.ScreenView

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

  public val state: TerminalWindowState get() = content?.state ?: binding!!.state
  public val session: XmuxSession? get() = binding?.session

  /** Redraws the panel if anything it shows changed since the last frame. */
  public fun refreshIfDirty() {
    view?.refreshIfDirty()
    binding?.refreshIfDirty()
  }

  public fun setPose(pose: Pose) {
    entity?.setComponent(Transform(pose))
  }

  public fun pose(): Pose? = entity?.tryGetComponent<Transform>()?.transform
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

  public val windows: List<ScreenWindow> get() = mounted

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
    val window = ScreenWindow(index, content, null, index < config.maxLayers, null, spec)
    window.pending = placement
    mounted.add(window)
    if (sceneReady) spawn(window)
    return window
  }

  /** Mounts a window that shows [screen] of [session] and follows its panes, tabs and size. */
  public fun mount(session: XmuxSession, screen: ScreenId, placement: Placement = Placement.InFrontOfHead): ScreenWindow {
    val index = mounted.size
    lateinit var window: ScreenWindow
    val binding = ScreenBinding(session, screen, font, terminals) { focused = window }
    binding.tick()
    window = ScreenWindow(index, null, screen, index < config.maxLayers, binding, specFor(binding))
    window.pending = placement
    mounted.add(window)
    if (sceneReady) spawn(window)
    return window
  }

  /**
   * Keeps one window per screen of [session]: new screens are mounted ([placement] gets the
   * index and count), closed ones unmounted. Null stops following.
   */
  public fun follow(session: XmuxSession?, placement: (index: Int, count: Int) -> Placement = followPlacement) {
    following = session
    followPlacement = placement
  }

  private fun syncScreens(session: XmuxSession) {
    val screens = session.topology.value.screens
    if (screens.isEmpty()) return
    val shown = mounted.filter { it.session === session }.mapNotNull { it.screen }.toSet()
    val missing = screens.filter { it.id !in shown }
    for ((i, s) in missing.withIndex()) mount(session, s.id, followPlacement(shown.size + i, screens.size))
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

  private fun specFor(binding: ScreenBinding): PanelSpec {
    val size = binding.size
    val header = WindowChrome.Default.headerHeightDp + 1
    val w = size.width.coerceAtLeast(spec.widthPx / 4)
    val h = size.height.coerceAtLeast(spec.heightPx / 4) + header
    return PanelSpec(w, h, w * metresPerPx)
  }

  /** Re-creates a screen window's panel at its new size, keeping its pose. */
  private fun respawn(window: ScreenWindow) {
    val pose = window.pose()
    window.entity?.destroy()
    window.entity = null
    window.screenView = null
    window.binding?.view = null
    window.spec = specFor(window.binding!!)
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
          IsdkPanelResize(
            resizeMode = ResizeMode.Simple,
            minDimensions = Vector2(0.2f, 0.14f),
            maxDimensions = Vector2(4f, 3f),
            preserveAspectRatio = true,
          ),
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
                WindowChrome.Default,
                body = { c -> ScreenView(c, font) },
                onViewCreated = {
                  val v = it as ScreenView
                  window.screenView = v
                  binding.view = v
                  binding.tick()
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
  }
}
