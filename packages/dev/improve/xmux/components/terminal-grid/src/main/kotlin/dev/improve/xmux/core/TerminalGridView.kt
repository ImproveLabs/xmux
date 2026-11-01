package dev.improve.xmux.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.fonts.Font
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws a [CellGrid] with `Canvas.drawGlyphs`.
 *
 * Glyphs are batched for the whole frame by (font, color, style): monospace cells never overlap,
 * so draw order within a frame is free and a screen with N distinct colors costs about N draw
 * calls regardless of how colors are interleaved. Backgrounds are rects per run, decorations are
 * lines, the cursor is drawn last. HWUI re-records the whole display list on every invalidate
 * (dirty rectangles are ignored since API 21), so the view invalidates once per changed frame
 * and skips frames where nothing changed.
 */
public class TerminalGridView(context: Context, grid: CellGrid, public val font: TerminalFont) : View(context) {
  /** The grid drawn; replaced when the terminal is resized. */
  public var grid: CellGrid = grid
    set(value) {
      if (field === value) return
      field = value
      value.markAllDirty()
      requestLayout()
      invalidate()
    }

  public var paddingPx: Float = 10f
  /** Color behind cells with the default (transparent) background; used for inverse video. */
  public var bodyColor: Int = 0xFF1E1E1E.toInt()
  public var cursorColor: Int = 0xFFD4D4D4.toInt()

  private val textPaint = Paint(font.paint)
  private val fillPaint = Paint()
  private val linePaint = Paint().apply { strokeWidth = 2f }
  private val batches = ArrayList<Batch>()
  private val scratchIds = IntArray(1)
  private val scratchPos = FloatArray(2)

  public var drawnFrames: Long = 0L
    private set
  /** Draw calls issued by the last onDraw (glyph batches + background rects + lines + cursor). */
  public var lastDrawCalls: Int = 0
    private set

  public val contentWidth: Int
    get() = (paddingPx * 2 + grid.cols * font.cellWidth).toInt()
  public val contentHeight: Int
    get() = (paddingPx * 2 + grid.rows * font.cellHeight).toInt()

  private class Batch(val font: Font, val color: Int, val style: Int) {
    var ids = IntArray(128)
    var pos = FloatArray(256)
    var n = 0

    fun add(id: Int, x: Float, y: Float) {
      if (n == ids.size) {
        ids = ids.copyOf(n * 2)
        pos = pos.copyOf(n * 4)
      }
      ids[n] = id
      pos[n * 2] = x
      pos[n * 2 + 1] = y
      n++
    }
  }

  init {
    setWillNotDraw(false)
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    setMeasuredDimension(
      resolveSize(contentWidth, widthMeasureSpec),
      resolveSize(contentHeight, heightMeasureSpec),
    )
  }

  public fun refreshIfDirty() {
    if (grid.anyDirty()) invalidate()
  }

  /** The cell under a point in view coordinates, as (row, col), or null outside the grid. */
  public fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
    val col = floor((x - paddingPx) / font.cellWidth).toInt()
    val row = floor((y - paddingPx) / font.cellHeight).toInt()
    return if (row in 0 until grid.rows && col in 0 until grid.cols) row to col else null
  }

  /** What shows behind cells with the default background. */
  private val background: Int
    get() = if (grid.defaultBg != CellGrid.DEFAULT_BG) grid.defaultBg else bodyColor

  override fun onDraw(canvas: Canvas) {
    val ch = font.cellHeight
    val clip = canvas.clipBounds
    val first = floor((clip.top - paddingPx) / ch).toInt().coerceIn(0, grid.rows - 1)
    val last = ceil((clip.bottom - paddingPx) / ch).toInt().coerceIn(0, grid.rows - 1)
    var calls = 0
    for (b in batches) b.n = 0
    if (grid.defaultBg != CellGrid.DEFAULT_BG) {
      // The terminal has its own background (the Mac's theme): paint the whole grid with it.
      fillPaint.color = grid.defaultBg
      canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
      calls++
    }
    for (row in first..last) calls += drawBackgrounds(canvas, row)
    for (row in first..last) calls += collectGlyphs(canvas, row)
    for (b in batches) {
      if (b.n == 0) continue
      applyStyle(b.color, b.style)
      canvas.drawGlyphs(b.ids, 0, b.pos, 0, b.n, b.font, textPaint)
      calls++
    }
    for (row in first..last) calls += drawDecorations(canvas, row)
    if (grid.cursorVisible && grid.cursorRow in first..last) calls += drawCursor(canvas)
    grid.clearDirty()
    lastDrawCalls = calls
    drawnFrames++
  }

  private fun fgAt(i: Int): Int = CellStyle.effectiveFg(grid.fg[i], grid.bg[i], grid.attrs[i], grid.defaultFg, background)

  private fun bgAt(i: Int): Int = CellStyle.effectiveBg(grid.fg[i], grid.bg[i], grid.attrs[i], grid.defaultFg)

  private fun applyStyle(color: Int, style: Int) {
    textPaint.color = color
    textPaint.isFakeBoldText = style and Attr.BOLD != 0
    textPaint.textSkewX = if (style and Attr.ITALIC != 0) -0.2f else 0f
    if (style and Attr.DIM != 0) textPaint.alpha = 140
  }

  private fun batchFor(f: Font, color: Int, style: Int): Batch {
    for (b in batches) if (b.font === f && b.color == color && b.style == style) return b
    val b = Batch(f, color, style)
    batches.add(b)
    return b
  }

  private fun drawBackgrounds(canvas: Canvas, row: Int): Int {
    val cw = font.cellWidth
    val top = paddingPx + row * font.cellHeight
    val base = row * grid.cols
    val cols = grid.cols
    var calls = 0
    var c = 0
    while (c < cols) {
      val bg = bgAt(base + c)
      var e = c + 1
      while (e < cols && bgAt(base + e) == bg) e++
      if (bg != CellGrid.DEFAULT_BG) {
        fillPaint.color = bg
        canvas.drawRect(paddingPx + c * cw, top, paddingPx + e * cw, top + font.cellHeight, fillPaint)
        calls++
      }
      c = e
    }
    return calls
  }

  private fun collectGlyphs(canvas: Canvas, row: Int): Int {
    val cw = font.cellWidth
    val baseline = paddingPx + row * font.cellHeight + font.baseline
    val base = row * grid.cols
    var calls = 0
    var last: Batch? = null
    for (c in 0 until grid.cols) {
      val i = base + c
      val cp = grid.codepoints[i]
      val a = grid.attrs[i]
      if (cp == ' '.code || a and Attr.WIDE_SPACER != 0) continue
      val color = fgAt(i)
      val style = CellStyle.styleBits(a)
      val x = paddingPx + c * cw
      val cluster = grid.clusters[i]
      if (cluster != null) {
        // A grapheme of several code points (combining marks, emoji sequences): the text path
        // shapes it as one unit.
        applyStyle(color, style)
        canvas.drawText(cluster, x, baseline, textPaint)
        calls++
        continue
      }
      val g = font.glyph(cp)
      if (g == null) {
        // Multi-glyph shaping result (rare): draw immediately through the text path.
        applyStyle(color, style)
        canvas.drawText(String(Character.toChars(cp)), x, baseline, textPaint)
        calls++
        continue
      }
      var b = last
      if (b == null || b.font !== g.font || b.color != color || b.style != style) {
        b = batchFor(g.font, color, style)
        last = b
      }
      b.add(g.id, x, baseline)
    }
    return calls
  }

  private fun drawDecorations(canvas: Canvas, row: Int): Int {
    val cw = font.cellWidth
    val top = paddingPx + row * font.cellHeight
    val baseline = top + font.baseline
    val base = row * grid.cols
    var calls = 0
    for (c in 0 until grid.cols) {
      val i = base + c
      val a = grid.attrs[i]
      if (a and (Attr.UNDERLINE or Attr.STRIKE) == 0) continue
      val x = paddingPx + c * cw
      linePaint.color = fgAt(i)
      if (a and Attr.UNDERLINE != 0) {
        canvas.drawLine(x, baseline + 3f, x + cw, baseline + 3f, linePaint)
        calls++
      }
      if (a and Attr.STRIKE != 0) {
        val y = top + font.cellHeight * 0.55f
        canvas.drawLine(x, y, x + cw, y, linePaint)
        calls++
      }
    }
    return calls
  }

  /** The cursor: a block drawn as inverse video over its cell, or an underline or bar. */
  private fun drawCursor(canvas: Canvas): Int {
    if (grid.cursorCol !in 0 until grid.cols) return 0
    val cw = font.cellWidth
    val ch = font.cellHeight
    val top = paddingPx + grid.cursorRow * ch
    val x = paddingPx + grid.cursorCol * cw
    fillPaint.color = cursorColor
    when (grid.cursorShape) {
      CursorShape.Underline -> {
        canvas.drawRect(x, top + ch - 3f, x + cw, top + ch, fillPaint)
        return 1
      }
      CursorShape.Bar -> {
        canvas.drawRect(x, top, x + 3f, top + ch, fillPaint)
        return 1
      }
      CursorShape.Block -> {}
    }
    canvas.drawRect(x, top, x + cw, top + ch, fillPaint)
    var calls = 1
    val i = grid.cursorRow * grid.cols + grid.cursorCol
    val cp = grid.codepoints[i]
    if (cp != ' '.code && grid.attrs[i] and Attr.WIDE_SPACER == 0) {
      applyStyle(background, 0)
      val baseline = top + font.baseline
      val cluster = grid.clusters[i]
      val g = if (cluster == null) font.glyph(cp) else null
      if (g != null) {
        scratchIds[0] = g.id
        scratchPos[0] = x
        scratchPos[1] = baseline
        canvas.drawGlyphs(scratchIds, 0, scratchPos, 0, 1, g.font, textPaint)
      } else canvas.drawText(cluster ?: String(Character.toChars(cp)), x, baseline, textPaint)
      calls++
    }
    return calls
  }
}
