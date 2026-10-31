package dev.xmux.demo

import dev.xmux.core.Attr
import dev.xmux.core.CellGrid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic, continuously changing screen content laid out like one cmux screen with two split
 * panes: a spinning ASCII torus on the left, live meters and a scrolling log on the right, a
 * prompt row that types commands, and a status bar. It stands in for the Mac bridge until cmux
 * render patches are wired in; everything it does is `CellGrid.put/write`.
 */
class DemoScreen(private val grid: CellGrid, private val targetFps: Int, seed: Int = 0) {
  private val cols = grid.cols
  private val rows = grid.rows
  private val paneTop = 1
  private val paneBottom = rows - 2
  private val leftWidth = cols / 2
  private val dividerCol = leftWidth
  private val rightStart = leftWidth + 2
  private val rightWidth = cols - rightStart

  private val torusTop = paneTop + 1
  private val torusW = leftWidth
  private val torusH = paneBottom - torusTop + 1
  private val zbuf = FloatArray(torusW * torusH)
  private val lumBuf = IntArray(torusW * torusH)
  private var angleA = seed * 0.7f
  private var angleB = seed * 0.3f
  private val spinA = 0.04f * (1f + (seed % 5) * 0.15f)
  private val spinB = 0.02f * (1f + (seed % 3) * 0.2f)

  private val meterTop = paneTop + 1
  private val logHeader = meterTop + METER_NAMES.size + 1
  private val logTop = logHeader + 1
  private val logRows = paneBottom - logTop + 1
  private val meters = FloatArray(METER_NAMES.size) { 0.2f + 0.6f * it / METER_NAMES.size }
  private val log = ArrayDeque<LogLine>()
  private val rnd = Random(7L + seed)
  private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

  private var frame = 0L
  private var staticDrawn = false
  private var typed = 0
  private var typePause = 0
  private var commandIndex = seed % COMMANDS.size
  private var lastTickNs = 0L
  private var fps = 0f

  private class LogLine(val time: String, val level: String, val color: Int, val text: String)

  fun tick(nowNs: Long) {
    if (lastTickNs != 0L) {
      val dt = (nowNs - lastTickNs) / 1e9f
      if (dt > 0f) fps = if (fps == 0f) 1f / dt else fps * 0.9f + 0.1f / dt
    }
    lastTickNs = nowNs
    if (!staticDrawn) {
      drawStatic()
      staticDrawn = true
    }
    drawPrompt()
    drawTorus()
    if (frame % 4 == 0L) drawMeters()
    if (frame % 8 == 0L) pushLog()
    drawStatus()
    val blinkPeriod = (targetFps / 2).coerceAtLeast(1)
    grid.setCursorVisible((frame / blinkPeriod) % 2 == 0L)
    frame++
  }

  private fun drawStatic() {
    for (r in paneTop..paneBottom) grid.put(r, dividerCol, '│'.code, DIVIDER)
    grid.write(paneTop, 0, " torus.c", LABEL, attrs = Attr.BOLD)
    grid.write(paneTop, rightStart, " resources", LABEL, attrs = Attr.BOLD)
    grid.write(logHeader, rightStart, " build.log", LABEL, attrs = Attr.BOLD)
  }

  private fun drawPrompt() {
    val cmd = COMMANDS[commandIndex]
    if (typePause > 0) {
      typePause--
      if (typePause == 0) {
        typed = 0
        commandIndex = (commandIndex + 1) % COMMANDS.size
      }
    } else if (frame % 3 == 0L && typed < cmd.length) {
      typed++
      if (typed == cmd.length) typePause = targetFps * 2
    }
    var c = grid.write(0, 0, "~/dev/xmux", PATH)
    c = grid.write(0, c, " main", BRANCH)
    c = grid.write(0, c, " ❯ ", PROMPT, attrs = Attr.BOLD)
    c = grid.write(0, c, cmd.substring(0, typed))
    grid.fill(0, c, cols)
    grid.setCursor(0, c)
  }

  private fun drawTorus() {
    zbuf.fill(0f)
    lumBuf.fill(-1)
    val cA = cos(angleA)
    val sA = sin(angleA)
    val cB = cos(angleB)
    val sB = sin(angleB)
    var theta = 0f
    while (theta < TWO_PI) {
      val ct = cos(theta)
      val st = sin(theta)
      var phi = 0f
      while (phi < TWO_PI) {
        val cp = cos(phi)
        val sp = sin(phi)
        val circleX = R2 + R1 * ct
        val circleY = R1 * st
        val x = circleX * (cB * cp + sA * sB * sp) - circleY * cA * sB
        val y = circleX * (sB * cp - sA * cB * sp) + circleY * cA * cB
        val z = K2 + cA * circleX * sp + circleY * sA
        val ooz = 1f / z
        val xp = (torusW / 2f + K1X * ooz * x).toInt()
        val yp = (torusH / 2f - K1Y * ooz * y).toInt()
        if (xp in 0 until torusW && yp in 0 until torusH) {
          val l = cp * ct * sB - cA * ct * sp - sA * st + cB * (cA * st - ct * sA * sp)
          if (l > 0f) {
            val i = yp * torusW + xp
            if (ooz > zbuf[i]) {
              zbuf[i] = ooz
              lumBuf[i] = (l * 8f).toInt().coerceIn(0, LUM.length - 1)
            }
          }
        }
        phi += 0.02f
      }
      theta += 0.07f
    }
    for (yp in 0 until torusH) {
      val row = torusTop + yp
      for (xp in 0 until torusW) {
        val li = lumBuf[yp * torusW + xp]
        if (li < 0) grid.put(row, xp, ' '.code) else grid.put(row, xp, LUM[li].code, torusColor(li))
      }
    }
    angleA += spinA
    angleB += spinB
  }

  private fun torusColor(li: Int): Int {
    val t = li / (LUM.length - 1f)
    val r = (120 + 135 * t).toInt()
    val g = (70 + 150 * t).toInt()
    val b = (30 + 100 * t).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }

  private fun drawMeters() {
    val barW = rightWidth - 5 - 5
    for ((i, name) in METER_NAMES.withIndex()) {
      val row = meterTop + i
      meters[i] = (meters[i] + (rnd.nextFloat() - 0.5f) * 0.12f).coerceIn(0.02f, 1f)
      var c = grid.write(row, rightStart, name.padEnd(5), LABEL)
      val filled = (meters[i] * barW).toInt()
      val color = when {
        meters[i] < 0.6f -> GREEN
        meters[i] < 0.85f -> YELLOW
        else -> RED
      }
      for (k in 0 until barW) {
        if (k < filled) grid.put(row, c + k, '█'.code, color) else grid.put(row, c + k, '░'.code, TRACK)
      }
      c += barW
      grid.write(row, c, String.format(Locale.US, " %3d%%", (meters[i] * 100).toInt()), VALUE)
    }
  }

  private fun pushLog() {
    val roll = rnd.nextInt(100)
    val (level, color) = when {
      roll < 70 -> "INFO " to GREEN
      roll < 85 -> "WARN " to YELLOW
      roll < 95 -> "DEBUG" to DIM
      else -> "ERROR" to RED
    }
    val text = LOG_MESSAGES[rnd.nextInt(LOG_MESSAGES.size)] + " " + (1 + rnd.nextInt(240)) + "ms"
    log.addLast(LogLine(clock.format(Date()), level, color, text))
    while (log.size > logRows) log.removeFirst()
    val textWidth = rightWidth - 15
    for (r in 0 until logRows) {
      val row = logTop + r
      val line = log.getOrNull(r)
      if (line == null) {
        grid.fill(row, rightStart, cols)
        continue
      }
      var c = grid.write(row, rightStart, line.time, DIM)
      c = grid.write(row, c + 1, line.level, line.color, attrs = if (line.level == "ERROR") Attr.BOLD else 0)
      c = grid.write(row, c + 1, line.text.take(textWidth))
      grid.fill(row, c, cols)
    }
  }

  private fun drawStatus() {
    val row = rows - 1
    var c = grid.write(row, 0, " cmux ", BODY, ACCENT, Attr.BOLD)
    c = grid.write(row, c, " main ▸ screen 1 ", STATUS_FG, STATUS_BG)
    c = grid.write(row, c, "│ ${cols}×${rows} ", STATUS_DIM, STATUS_BG)
    c = grid.write(row, c, "│ ${clock.format(Date())} ", STATUS_DIM, STATUS_BG)
    c = grid.write(row, c, "│ frame $frame ", STATUS_DIM, STATUS_BG)
    c = grid.write(row, c, String.format(Locale.US, "│ %.0f fps ", fps), STATUS_DIM, STATUS_BG)
    grid.fill(row, c, cols, bg = STATUS_BG)
    val tail = " layer · alpha-blend "
    grid.write(row, cols - tail.length, tail, STATUS_DIM, STATUS_BG)
  }

  private companion object {
    const val TWO_PI = 6.2831855f
    const val R1 = 1f
    const val R2 = 2f
    const val K2 = 5f
    const val K1X = 20f
    const val K1Y = 9f
    const val LUM = ".,-~:;=!*#$@"

    const val BODY = 0xFF1E1E1E.toInt()
    const val LABEL = 0xFF9E9E9E.toInt()
    const val VALUE = 0xFFBDBDBD.toInt()
    const val DIM = 0xFF7A7A7A.toInt()
    const val DIVIDER = 0xFF4A4A4A.toInt()
    const val TRACK = 0xFF3C3C3C.toInt()
    const val PATH = 0xFF6FB3D2.toInt()
    const val BRANCH = 0xFF9CCC65.toInt()
    const val PROMPT = 0xFFE06C9F.toInt()
    const val GREEN = 0xFF7DCB6B.toInt()
    const val YELLOW = 0xFFE5C07B.toInt()
    const val RED = 0xFFE06C75.toInt()
    const val ACCENT = 0xFF8AB4F8.toInt()
    const val STATUS_BG = 0xFF2F3A46.toInt()
    const val STATUS_FG = 0xFFE0E0E0.toInt()
    const val STATUS_DIM = 0xFFA8B4C0.toInt()

    val METER_NAMES = listOf("cpu0", "cpu1", "cpu2", "cpu3", "mem", "net")
    val COMMANDS = listOf(
      "git log --oneline | head -n 5",
      "cargo build --release -p xmux-bridge",
      "npx cmux server start --session quest",
      "ls -la ~/dev/xmux/quest",
    )
    val LOG_MESSAGES = listOf(
      "render patch 3 rows",
      "attach lease ok",
      "compile Grid.kt",
      "session.events delta",
      "viewer.resize 80x24",
      "layout split ratio .5",
      "input.write 12 bytes",
      "snapshot 24 rows",
      "fps sample 72.0",
      "panel layer submit",
    )
  }
}
