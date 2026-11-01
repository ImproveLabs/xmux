package dev.improve.xmux.spatial

import dev.improve.xmux.terminal.font.TerminalFont

/**
 * Pixel and physical size of a window panel. The texture is sized exactly to the grid plus the
 * header (panels run at 160 dpi, so dp == px), and the physical height follows the pixel aspect.
 */
public data class PanelSpec(
  public val widthPx: Int,
  public val heightPx: Int,
  public val widthM: Float,
) {
  public val heightM: Float get() = widthM * heightPx / widthPx

  public companion object {
    public fun of(font: TerminalFont, cols: Int, rows: Int, paddingPx: Float, headerPx: Int, widthM: Float): PanelSpec =
      of(font.cellWidth, font.cellHeight, cols, rows, paddingPx, headerPx, widthM)

    /** Pure variant for tests. */
    public fun of(cellWidth: Float, cellHeight: Float, cols: Int, rows: Int, paddingPx: Float, headerPx: Int, widthM: Float): PanelSpec {
      val w = (paddingPx * 2 + cols * cellWidth).toInt()
      val h = (paddingPx * 2 + rows * cellHeight).toInt() + headerPx + 1
      return PanelSpec(w, h, widthM)
    }
  }
}
