package dev.improve.xmux.preview

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.client.BridgeTarget
import dev.improve.xmux.client.ConnectionState
import dev.improve.xmux.client.MouseAction
import dev.improve.xmux.client.MouseEvent
import dev.improve.xmux.client.PaneId
import dev.improve.xmux.client.TerminalHandle
import dev.improve.xmux.client.XmuxClient
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.connect.panel.ConnectPanel
import dev.improve.xmux.connect.panel.ConnectStatus
import dev.improve.xmux.connect.panel.parseAddress
import dev.improve.xmux.demo.DemoScreen
import dev.improve.xmux.key.encoder.KeyEncoder
import dev.improve.xmux.screen.layout.PxSize
import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.screen.view.session.ScreenModels
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.terminal.grid.TerminalGridView
import dev.improve.xmux.terminal.window.TerminalWindow
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.xmux.window.frame.WindowFrame
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.min

/**
 * The window a Quest panel shows, on a phone or emulator: one cmux screen from a bridge (the
 * active one), or the demo. Density is forced to 1 so dp == px like a 160 dpi panel, and the
 * window is scaled down to fit.
 *
 * `adb shell am start -n dev.xmux.preview/dev.improve.xmux.preview.PreviewActivity --es address 10.0.2.2:7272`
 * (the emulator's address for the Mac), or `--ez demo true`.
 */
class PreviewActivity : ComponentActivity() {
  private val font by lazy { TerminalFont(this, 25f) }
  private val scope = MainScope()
  private val handler = Handler(Looper.getMainLooper())
  private var running = false

  private var status by mutableStateOf<ConnectStatus>(ConnectStatus.Idle)
  private var screenSize by mutableStateOf<PxSize?>(null)
  private val windowState = TerminalWindowState("xmux")
  private var session: XmuxSession? = null
  private var screenView: ScreenView? = null
  private var lastKey: Any? = null
  private var focusedPane = 0

  private var demo: DemoScreen? = null
  private var demoView: TerminalGridView? = null

  private val ticker =
    object : Runnable {
      override fun run() {
        if (!running) return
        demo?.tick(System.nanoTime())
        demoView?.refreshIfDirty()
        updateScreen()
        handler.postDelayed(this, 33L)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val isDemo = intent?.getBooleanExtra("demo", false) == true
    val demoGrid = CellGrid(80, 24)
    if (isDemo) {
      demo = DemoScreen(demoGrid, 30)
      windowState.title = "xmux — demo"
      windowState.loading = false
    }
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF8C9BA5)), contentAlignment = Alignment.Center) {
          val size = screenSize
          when {
            isDemo -> {
              val probe = TerminalGridView(this@PreviewActivity, demoGrid, font)
              val w = probe.contentWidth
              val h = probe.contentHeight + WindowChrome.Default.headerHeightDp + 1
              val scale = min(1f, min(maxWidth.value / w, maxHeight.value / h))
              Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
                TerminalWindow(windowState, demoGrid, font, Modifier.requiredSize(w.dp, h.dp)) { demoView = it }
              }
            }
            status is ConnectStatus.Online && size != null -> {
              val w = size.width
              val h = size.height + WindowChrome.Default.headerHeightDp + 1
              val scale = min(1f, min(maxWidth.value / w, maxHeight.value / h))
              Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
                WindowFrame(windowState, Modifier.requiredSize(w.dp, h.dp), body = { c -> ScreenView(c, font).also(::bind) })
              }
            }
            else -> ConnectPanel(status, emptyList(), ::connect, ::disconnect, Modifier.width(720.dp))
          }
        }
      }
    }
    if (!isDemo) {
      (intent?.getStringExtra("address") ?: "10.0.2.2:${BridgeTarget.DEFAULT_PORT}").let(::connect)
    }
  }

  private fun bind(view: ScreenView) {
    screenView = view
    lastKey = null
    view.requestFocus()
    view.listener =
      object : ScreenView.Listener {
        override fun onPointer(hit: ScreenView.Hit, action: Int) {
          if (action == MotionEvent.ACTION_DOWN && hit.pane != focusedPane) {
            focusedPane = hit.pane
            view.focusedPane = hit.pane
            session?.focusPane(PaneId(hit.pane))
          }
          if (hit.row >= 0) {
            terminalOf(hit.pane)?.mouse(MouseEvent(if (action == MotionEvent.ACTION_DOWN) MouseAction.Press else MouseAction.Release, 0, hit.row, hit.col))
          }
        }

        override fun onTab(pane: Int, index: Int) {
          val s = session ?: return
          val screen = s.topology.value.let { it.activeScreen?.let(it::screen) ?: it.screens.firstOrNull() } ?: return
          screen.panes.firstOrNull { it.id.value == pane }?.tabs?.getOrNull(index)?.let { s.focusTab(it.id) }
        }

        override fun onText(text: String) {
          terminalOf(focusedPane)?.write(text)
        }

        override fun onKey(event: KeyEvent): Boolean = sendKey(event)
      }
  }

  private fun terminalOf(pane: Int): TerminalHandle? {
    val s = session ?: return null
    val t = s.topology.value
    val screen = t.activeScreen?.let(t::screen) ?: t.screens.firstOrNull() ?: return null
    val p = screen.panes.firstOrNull { it.id.value == pane } ?: screen.panes.firstOrNull() ?: return null
    return p.activeTerminal?.let(s::terminal)
  }

  private fun sendKey(event: KeyEvent): Boolean {
    val input = KeyEncoder.encode(event) ?: return false
    terminalOf(focusedPane)?.send(input) ?: return false
    return true
  }

  /** Keys the focused screen view did not take (it handles its own while it has focus). */
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = (session != null && sendKey(event)) || super.onKeyDown(keyCode, event)

  private fun updateScreen() {
    val s = session ?: return
    val state = s.state.value
    status =
      when (state) {
        ConnectionState.Connecting -> ConnectStatus.Connecting(status.let { (it as? ConnectStatus.Connecting)?.address ?: "bridge" })
        is ConnectionState.Pairing -> ConnectStatus.Pairing(state.code)
        ConnectionState.Online, is ConnectionState.Stalled -> ConnectStatus.Online(s.sessionName ?: "cmux", s.topology.value.screens.size)
        is ConnectionState.Reconnecting -> ConnectStatus.Reconnecting(state.attempt, state.reason)
        is ConnectionState.Closed -> state.reason?.let { ConnectStatus.Failed(it) } ?: ConnectStatus.Idle
      }
    val topology = s.topology.value
    val screen = topology.activeScreen?.let(topology::screen) ?: topology.screens.firstOrNull() ?: return
    val tree = ScreenModels.tree(screen.layout)
    val panes = ScreenModels.panes(screen, s)
    if (focusedPane == 0) focusedPane = screen.focusedPane.value
    screenSize = ScreenView.measure(font, tree, panes)
    windowState.title = "${s.sessionName ?: "cmux"} — ${screen.title}"
    windowState.loading = ScreenModels.terminals(screen).any { s.terminal(it).attach.value != dev.improve.xmux.client.AttachStatus.Attached }
    val key = listOf(topology.revision, panes.map { System.identityHashCode(it.grid) }, screenView)
    if (key != lastKey) {
      lastKey = key
      screenView?.update(tree, panes, screen.focusedPane.value)
    }
    screenView?.refreshIfDirty()
  }

  private fun connect(address: String) {
    val (host, port) = parseAddress(address, BridgeTarget.DEFAULT_PORT) ?: return
    disconnect()
    status = ConnectStatus.Connecting(address)
    session = XmuxClient(this).connect(BridgeTarget(host, port), scope, deviceName = "xmux preview")
  }

  private fun disconnect() {
    session?.close()
    session = null
    screenView = null
    screenSize = null
    status = ConnectStatus.Idle
  }

  override fun onResume() {
    super.onResume()
    running = true
    handler.post(ticker)
  }

  override fun onPause() {
    running = false
    handler.removeCallbacks(ticker)
    super.onPause()
  }

  override fun onDestroy() {
    disconnect()
    scope.cancel()
    super.onDestroy()
  }
}
