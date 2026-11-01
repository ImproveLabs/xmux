package dev.improve.xmux.cell.grid

import kotlin.test.Test
import kotlin.test.assertEquals

class CellStyleTest {
  private val body = 0xFF1E1E1E.toInt()
  private val defaultFg = 0xFFD4D4D4.toInt()
  private val red = 0xFFFF0000.toInt()
  private val blue = 0xFF0000FF.toInt()

  @Test
  fun plainCellsKeepTheirColorsAndDefaultsResolve() {
    assertEquals(red, CellStyle.effectiveFg(red, blue, 0, defaultFg, body))
    assertEquals(defaultFg, CellStyle.effectiveFg(0, blue, 0, defaultFg, body))
    assertEquals(blue, CellStyle.effectiveBg(red, blue, 0, defaultFg))
    assertEquals(CellGrid.DEFAULT_BG, CellStyle.effectiveBg(red, 0, 0, defaultFg))
  }

  @Test
  fun inverseSwapsAndUsesTheBackgroundForDefaultBackgrounds() {
    assertEquals(blue, CellStyle.effectiveFg(red, blue, Attr.INVERSE, defaultFg, body))
    assertEquals(red, CellStyle.effectiveBg(red, blue, Attr.INVERSE, defaultFg))
    assertEquals(body, CellStyle.effectiveFg(red, 0, Attr.INVERSE, defaultFg, body))
    assertEquals(defaultFg, CellStyle.effectiveBg(0, blue, Attr.INVERSE, defaultFg))
  }

  @Test
  fun invisibleHidesTheGlyphBehindItsBackground() {
    assertEquals(blue, CellStyle.effectiveFg(red, blue, Attr.INVISIBLE, defaultFg, body))
    assertEquals(body, CellStyle.effectiveFg(red, 0, Attr.INVISIBLE, defaultFg, body))
  }

  @Test
  fun styleBitsOnlyKeepGlyphAffectingAttributes() {
    val all = Attr.BOLD or Attr.ITALIC or Attr.UNDERLINE or Attr.INVERSE or Attr.DIM or Attr.STRIKE
    assertEquals(Attr.BOLD or Attr.ITALIC or Attr.DIM, CellStyle.styleBits(all))
  }
}
