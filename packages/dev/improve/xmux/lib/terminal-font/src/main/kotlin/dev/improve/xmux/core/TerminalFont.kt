package dev.improve.xmux.core

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.text.TextRunShaper
import kotlin.math.ceil
import kotlin.math.roundToInt

public class Glyph(public val font: Font, public val id: Int)

/**
 * The bundled monospace font plus the cell metrics derived from it.
 *
 * Glyph ids are resolved once per codepoint through the platform shaper (which also performs
 * system font fallback for symbols, CJK and emoji) and then drawn with `Canvas.drawGlyphs`.
 */
public class TerminalFont(context: Context, sizePx: Float, assetPath: String = DEFAULT_ASSET) {
  public val font: Font = Font.Builder(context.assets, assetPath).build()
  public val typeface: Typeface =
    Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).setSystemFallback("monospace").build()

  /** Prototype paint; views copy it. */
  public val paint: Paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
      typeface = this@TerminalFont.typeface
      textSize = sizePx
    }

  /** Integer cell size so glyph origins land on whole pixels. */
  public val cellWidth: Float
  public val cellHeight: Float
  /** Baseline offset from the top of a cell. */
  public val baseline: Float

  private val cache = HashMap<Int, Glyph?>()

  init {
    val fm = paint.fontMetrics
    cellWidth = paint.measureText("M").roundToInt().toFloat()
    val ascent = -fm.ascent
    val natural = ascent + fm.descent
    cellHeight = ceil(natural)
    baseline = ascent + (cellHeight - natural) / 2f
  }

  /** Returns the glyph for [cp], or null when the shaper produced more than one glyph. */
  public fun glyph(cp: Int): Glyph? {
    if (cache.containsKey(cp)) return cache[cp]
    val s = String(Character.toChars(cp))
    val shaped = TextRunShaper.shapeTextRun(s, 0, s.length, 0, s.length, 0f, 0f, false, paint)
    val g = if (shaped.glyphCount() == 1) Glyph(shaped.getFont(0), shaped.getGlyphId(0)) else null
    cache[cp] = g
    return g
  }

  public companion object {
    public const val DEFAULT_ASSET: String = "JetBrainsMono-Regular.ttf"
  }
}
