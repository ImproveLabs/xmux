package dev.improve.xmux.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.PanelRegistration
import dev.improve.xmux.client.BridgeAdvert
import dev.improve.xmux.client.BridgeTarget
import dev.improve.xmux.client.ConnectionState
import dev.improve.xmux.client.XmuxClient
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.compose.BridgeChoice
import dev.improve.xmux.compose.ConnectPanel
import dev.improve.xmux.compose.ConnectStatus
import dev.improve.xmux.compose.TerminalWindowState
import dev.improve.xmux.compose.parseAddress
import dev.improve.xmux.core.CellGrid
import dev.improve.xmux.demo.DemoScreen
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.SpatialConfig
import dev.improve.xmux.spatial.WindowContent
import dev.improve.xmux.spatial.XmuxSpatial
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The standalone XMUX app: a connect panel (bridges found on the network, or an address), the
 * pairing code while the Mac approves the headset, then one window per cmux screen. With no Mac
 * at hand, a demo window shows what it looks like.
 *
 * `adb shell am start -n dev.xmux.quest/dev.improve.xmux.app.MainActivity --es address 192.168.1.20:7272`
 * connects straight away. Over USB, `adb reverse tcp:7272 tcp:7272` and `--es address 127.0.0.1:7272`
 * reach the bridge without a network.
 */
class MainActivity : AppSystemActivity() {
  private val xmux by lazy { XmuxSpatial(this, SpatialConfig()) }
  private val client by lazy { XmuxClient(this) }
  private val prefs by lazy { getSharedPreferences("dev.improve.xmux.app", MODE_PRIVATE) }
  private val scope = MainScope()

  private var status by mutableStateOf<ConnectStatus>(ConnectStatus.Idle)
  private val bridges = mutableStateListOf<BridgeChoice>()
  private val adverts = HashMap<String, BridgeAdvert>()
  private var session: XmuxSession? = null
  private var sessionJob: Job? = null
  private var discovery: Job? = null
  private var panel: Entity? = null
  private var autoConnect: String? = null

  private var demo: DemoScreen? = null
  private var demoWindow: ScreenWindow? = null
  private val handler = Handler(Looper.getMainLooper())
  private val demoTicker =
    object : Runnable {
      override fun run() {
        val d = demo ?: return
        d.tick(System.nanoTime())
        handler.postDelayed(this, 33L)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    autoConnect = intent?.getStringExtra("address") ?: prefs.getString(LAST_ADDRESS, null)
    prefs.getString(LAST_ADDRESS, null)?.let { bridges.add(BridgeChoice("Last used", it)) }
    super.onCreate(savedInstanceState)
  }

  override fun registerFeatures(): List<SpatialFeature> = xmux.registerFeatures()

  override fun registerPanels(): List<PanelRegistration> = xmux.registerPanels()

  override fun onSceneReady() {
    super.onSceneReady()
    xmux.onSceneReady()
    panel =
      xmux.mountPanel(PANEL_WIDTH, PANEL_HEIGHT, Placement.InFrontOfHead) {
        ConnectPanel(status, bridges, ::connect, ::disconnect, onDemo = ::startDemo)
      }
    startDiscovery()
    autoConnect?.let { connect(it) }
  }

  override fun onSceneTick() {
    super.onSceneTick()
    xmux.onSceneTick()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean = xmux.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

  private fun startDiscovery() {
    discovery?.cancel()
    discovery =
      scope.launch {
        runCatching {
          client.discover().collect { advert ->
            val address = "${advert.host}:${advert.port}"
            adverts[address] = advert
            bridges.removeAll { it.address == address }
            bridges.add(0, BridgeChoice(advert.name, address, advert.session?.let { "cmux session $it" } ?: ""))
          }
        }
      }
  }

  private fun connect(address: String) {
    val (host, port) = parseAddress(address, BridgeTarget.DEFAULT_PORT) ?: return
    disconnect()
    stopDemo()
    prefs.edit().putString(LAST_ADDRESS, address).apply()
    val target = BridgeTarget(host, port, adverts[address]?.fingerprint)
    val s = client.connect(target, scope)
    session = s
    xmux.follow(s)
    status = ConnectStatus.Connecting(address)
    sessionJob =
      scope.launch {
        s.state.combine(s.topology) { state, topology -> state to topology.screens.size }.collect { (state, screens) ->
          status =
            when (state) {
              ConnectionState.Connecting -> ConnectStatus.Connecting(address)
              is ConnectionState.Pairing -> ConnectStatus.Pairing(state.code)
              ConnectionState.Online, is ConnectionState.Stalled -> ConnectStatus.Online(s.sessionName ?: "cmux", screens)
              is ConnectionState.Reconnecting -> ConnectStatus.Reconnecting(state.attempt, state.reason)
              is ConnectionState.Closed -> state.reason?.let { ConnectStatus.Failed(it) } ?: ConnectStatus.Idle
            }
        }
      }
  }

  private fun disconnect() {
    sessionJob?.cancel()
    sessionJob = null
    val s = session ?: run {
      status = ConnectStatus.Idle
      return
    }
    xmux.follow(null)
    for (w in xmux.windows.filter { it.session === s }) xmux.unmount(w)
    s.close()
    session = null
    status = ConnectStatus.Idle
  }

  private fun startDemo() {
    if (demoWindow != null) return
    val grid = CellGrid(80, 24)
    demo = DemoScreen(grid, 30)
    val state = TerminalWindowState("xmux demo").apply { loading = false }
    demoWindow = xmux.mount(WindowContent(state, grid), Placement.InFrontOfHead)
    handler.post(demoTicker)
  }

  private fun stopDemo() {
    demo = null
    handler.removeCallbacks(demoTicker)
    demoWindow?.let { xmux.unmount(it) }
    demoWindow = null
  }

  override fun onDestroy() {
    stopDemo()
    disconnect()
    scope.cancel()
    super.onDestroy()
  }

  private companion object {
    const val LAST_ADDRESS = "last_address"
    const val PANEL_WIDTH = 720
    const val PANEL_HEIGHT = 560
  }
}
