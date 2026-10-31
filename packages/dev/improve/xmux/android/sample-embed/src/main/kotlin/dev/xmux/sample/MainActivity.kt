package dev.xmux.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.PanelRegistration
import dev.xmux.compose.TerminalWindowState
import dev.xmux.core.CellGrid
import dev.xmux.demo.AgentScreen
import dev.xmux.spatial.Placement
import dev.xmux.spatial.SpatialConfig
import dev.xmux.spatial.WindowContent
import dev.xmux.spatial.XmuxSpatial

/**
 * Minimal embedding of xmux-spatial, and the stress-test target:
 *   am start -n dev.xmux.sample/.MainActivity --ei count 32 --ei active 4 [--ei fps 30] [--ef font 15] [--ef width 0.5]
 */
class MainActivity : AppSystemActivity() {
  private class Demo(val state: TerminalWindowState, val grid: CellGrid, val screen: AgentScreen)

  private var count = 1
  private var active = Int.MAX_VALUE
  private var fps = 30
  private val demos = ArrayList<Demo>()
  private lateinit var xmux: XmuxSpatial
  private val handler = Handler(Looper.getMainLooper())
  private var running = false
  private var ticks = 0L
  private var lastReportNs = 0L

  private val ticker =
    object : Runnable {
      override fun run() {
        if (!running) return
        val now = System.nanoTime()
        for ((i, d) in demos.withIndex()) {
          if (i >= active && ticks > 2) continue
          d.screen.tick(now)
        }
        for (w in xmux.windows) w.refreshIfDirty()
        ticks++
        if (now - lastReportNs > 5_000_000_000L) {
          lastReportNs = now
          var drawn = 0L
          var calls = 0
          for (w in xmux.windows) {
            drawn += w.view?.drawnFrames ?: 0
            calls += w.view?.lastDrawCalls ?: 0
          }
          Log.i(TAG, "windows=${xmux.windows.size} ticks=$ticks framesDrawnTotal=$drawn drawCallsLastFrameTotal=$calls")
        }
        handler.postDelayed(this, 1000L / fps)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    count = (intent?.getIntExtra("count", 1) ?: 1).coerceIn(1, 256)
    val multi = count > 1
    active = intent?.getIntExtra("active", Int.MAX_VALUE) ?: Int.MAX_VALUE
    fps = (intent?.getIntExtra("fps", 30) ?: 30).coerceIn(1, 72)
    val config =
      SpatialConfig(
        fontPx = intent?.getFloatExtra("font", if (multi) 15f else 25f) ?: 25f,
        windowWidthM = intent?.getFloatExtra("width", if (multi) 0.5f else 1.0f) ?: 1.0f,
        maxLayers = intent?.getIntExtra("layers", 12) ?: 12,
      )
    xmux = XmuxSpatial(this, config)
    for (i in 0 until count) {
      val grid = CellGrid(config.cols, config.rows)
      val screen = AgentScreen(grid, i, count)
      demos.add(Demo(TerminalWindowState(screen.name), grid, screen))
    }
    Log.i(TAG, "count=$count active=$active fps=$fps config=$config")
    super.onCreate(savedInstanceState)
  }

  override fun registerFeatures(): List<SpatialFeature> = xmux.registerFeatures()

  override fun registerPanels(): List<PanelRegistration> = xmux.registerPanels()

  override fun onSceneReady() {
    super.onSceneReady()
    xmux.onSceneReady()
    for ((i, d) in demos.withIndex()) {
      val placement = if (count == 1) Placement.InFrontOfHead else Placement.Arc(i, count)
      xmux.mount(WindowContent(d.state, d.grid), placement)
    }
    Log.i(TAG, "placed ${demos.size} windows")
    running = true
    handler.post(ticker)
    handler.postDelayed({ demos.forEach { it.state.loading = false } }, 3_000)
  }

  override fun onSceneTick() {
    super.onSceneTick()
    xmux.onSceneTick()
  }

  override fun onDestroy() {
    running = false
    handler.removeCallbacksAndMessages(null)
    super.onDestroy()
  }

  private companion object {
    const val TAG = "XMUX"
  }
}
