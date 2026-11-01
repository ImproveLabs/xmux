package dev.improve.xmux.cell.grid

/** Pure style resolution shared by the renderer and its tests. */
public object CellStyle {
  /** Bits that change how glyphs are drawn and therefore split glyph batches. */
  public const val STYLE_MASK: Int = Attr.BOLD or Attr.ITALIC or Attr.DIM

  /** True for colors that mean "the terminal's default" (alpha 0). */
  public fun isDefault(color: Int): Boolean = color ushr 24 == 0

  /** [fg] with the default resolved. */
  public fun resolveFg(fg: Int, defaultFg: Int): Int = if (isDefault(fg)) defaultFg else fg

  /**
   * Foreground actually drawn for a cell, after defaults, inverse video and invisibility.
   * [background] is what shows behind a cell with the default background (the terminal's default
   * background, or the window body).
   */
  public fun effectiveFg(fg: Int, bg: Int, attrs: Int, defaultFg: Int, background: Int): Int {
    val cellBg = if (isDefault(bg)) background else bg
    if (attrs and Attr.INVISIBLE != 0) {
      return if (attrs and Attr.INVERSE != 0) resolveFg(fg, defaultFg) else cellBg
    }
    return if (attrs and Attr.INVERSE != 0) cellBg else resolveFg(fg, defaultFg)
  }

  /** Background to paint for a cell; [CellGrid.DEFAULT_BG] (0) means "nothing to paint". */
  public fun effectiveBg(fg: Int, bg: Int, attrs: Int, defaultFg: Int): Int =
    if (attrs and Attr.INVERSE != 0) resolveFg(fg, defaultFg) else if (isDefault(bg)) CellGrid.DEFAULT_BG else bg

  public fun styleBits(attrs: Int): Int = attrs and STYLE_MASK
}
