package dev.xmux.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellGridTest {
  private fun fresh(): CellGrid = CellGrid(10, 3).also { it.clearDirty() }

  @Test
  fun putMarksOnlyChangedRowsDirty() {
    val g = fresh()
    g.put(1, 2, 'x'.code)
    assertEquals(listOf(false, true, false), g.dirtyRows.toList())
    g.clearDirty()
    g.put(1, 2, 'x'.code) // identical write is a no-op
    assertFalse(g.anyDirty())
    g.put(1, 2, 'x'.code, fg = 0xFF00FF00.toInt())
    assertTrue(g.dirtyRows[1])
  }

  @Test
  fun writeReturnsNextColumnAndClipsAtRightEdge() {
    val g = fresh()
    val next = g.write(0, 7, "abcdef")
    assertEquals(13, next)
    assertEquals("       abc", g.rowText(0).padEnd(10))
    assertFalse(g.dirtyRows[1])
  }

  @Test
  fun writeHandlesSupplementaryCodepointsAsOneCell() {
    val g = fresh()
    g.write(0, 0, "a😀b")
    assertEquals(0x1F600, g.codepoints[1])
    assertEquals('b'.code, g.codepoints[2])
  }

  @Test
  fun outOfRangeWritesAreIgnored() {
    val g = fresh()
    g.put(-1, 0, 'x'.code)
    g.put(3, 0, 'x'.code)
    g.put(0, 10, 'x'.code)
    assertFalse(g.anyDirty())
  }

  @Test
  fun cursorMoveMarksOldAndNewRows() {
    val g = fresh()
    g.setCursor(2, 4)
    assertEquals(listOf(true, false, true), g.dirtyRows.toList())
    g.clearDirty()
    g.setCursor(2, 4)
    assertFalse(g.anyDirty())
    g.setCursorVisible(false)
    assertEquals(listOf(false, false, true), g.dirtyRows.toList())
  }

  @Test
  fun fillAndRowTextSkipWideSpacers() {
    val g = fresh()
    g.put(0, 0, 0x6F22, attrs = 0)
    g.put(0, 1, 0, attrs = Attr.WIDE_SPACER)
    g.put(0, 2, 'a'.code)
    assertEquals("漢a", g.rowText(0))
    g.fill(0, 0, 10)
    assertEquals("", g.rowText(0))
  }
}
