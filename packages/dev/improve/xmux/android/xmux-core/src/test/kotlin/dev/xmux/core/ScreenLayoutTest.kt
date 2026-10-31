package dev.xmux.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenLayoutTest {
  private val sizes = mapOf(1 to PxSize(100, 50), 2 to PxSize(80, 40), 3 to PxSize(60, 30))
  private val size = { id: Int -> sizes.getValue(id) }
  private val d = ScreenLayoutEngine.DIVIDER_PX

  @Test
  fun horizontalSplitsSitSideBySideAndVerticalOnesStack() {
    val side = LayoutTree.Split(true, 0.5f, LayoutTree.Leaf(1), LayoutTree.Leaf(2))
    assertEquals(PxSize(100 + d + 80, 50), ScreenLayoutEngine.measure(side, size))
    assertEquals(PxRect(100 + d, 0, 100 + d + 80, 40), ScreenLayoutEngine.place(side, size).getValue(2))

    val stacked = LayoutTree.Split(false, 0.5f, LayoutTree.Leaf(1), LayoutTree.Leaf(2))
    assertEquals(PxSize(100, 50 + d + 40), ScreenLayoutEngine.measure(stacked, size))
    assertEquals(PxRect(0, 50 + d, 80, 50 + d + 40), ScreenLayoutEngine.place(stacked, size).getValue(2))
    assertEquals(listOf(PxRect(0, 50, 100, 50 + d)), ScreenLayoutEngine.dividers(stacked, size))
  }

  @Test
  fun nestedSplitsCompose() {
    val tree = LayoutTree.Split(true, 0.5f, LayoutTree.Leaf(1), LayoutTree.Split(false, 0.5f, LayoutTree.Leaf(2), LayoutTree.Leaf(3)))
    assertEquals(PxSize(100 + d + 80, 50 /* max(50, 40 + d + 30) */.coerceAtLeast(40 + d + 30)), ScreenLayoutEngine.measure(tree, size))
    val rects = ScreenLayoutEngine.place(tree, size)
    assertEquals(PxRect(100 + d, 40 + d, 100 + d + 60, 40 + d + 30), rects.getValue(3))
  }

  @Test
  fun stacksShowOnlyTheExpandedPaneBelowHeaders() {
    val tree = LayoutTree.Stack(listOf(1, 2, 3), expanded = 2)
    val h = ScreenLayoutEngine.STACK_HEADER_PX
    assertEquals(PxSize(80, 40 + 2 * h), ScreenLayoutEngine.measure(tree, size))
    val rects = ScreenLayoutEngine.place(tree, size)
    assertEquals(PxRect(0, 0, 80, h), rects.getValue(1))
    assertEquals(PxRect(0, h, 80, h + 40), rects.getValue(2))
    assertEquals(PxRect(0, h + 40, 80, 2 * h + 40), rects.getValue(3))
  }

  @Test
  fun columnsSitSideBySideWithDividers() {
    val tree = LayoutTree.Columns(listOf(0.5f, 0.5f), listOf(LayoutTree.Leaf(1), LayoutTree.Leaf(3)))
    assertEquals(PxSize(100 + d + 60, 50), ScreenLayoutEngine.measure(tree, size))
    assertEquals(1, ScreenLayoutEngine.dividers(tree, size).size)
  }
}
