package dev.improve.xmux.screen.layout

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
  fun fitGivesALonePaneTheWholeTarget() {
    assertEquals(mapOf(1 to PxSize(640, 300)), ScreenLayoutEngine.fit(LayoutTree.Leaf(1), size, PxSize(640, 300)))
  }

  @Test
  fun fitKeepsSplitProportionsAndFillsTheTarget() {
    // 100 and 80 wide side by side: twice the width keeps the 5:4 share, both take the full height.
    val side = LayoutTree.Split(true, 0.5f, LayoutTree.Leaf(1), LayoutTree.Leaf(2))
    val target = PxSize(2 * (100 + 80) + d, 120)
    val fitted = ScreenLayoutEngine.fit(side, size, target)
    assertEquals(mapOf(1 to PxSize(200, 120), 2 to PxSize(160, 120)), fitted)
    assertEquals(target, ScreenLayoutEngine.measure(side) { fitted.getValue(it) })

    val nested = LayoutTree.Split(true, 0.5f, LayoutTree.Leaf(1), LayoutTree.Split(false, 0.5f, LayoutTree.Leaf(2), LayoutTree.Leaf(3)))
    val big = PxSize(500, 333)
    assertEquals(big, ScreenLayoutEngine.measure(nested) { ScreenLayoutEngine.fit(nested, size, big).getValue(it) })
  }

  @Test
  fun fitUsesTheRatioBeforePanesHaveSizesAndSkipsCollapsedStackMembers() {
    val empty = { _: Int -> PxSize(0, 0) }
    val split = LayoutTree.Split(false, 0.25f, LayoutTree.Leaf(1), LayoutTree.Leaf(2))
    assertEquals(mapOf(1 to PxSize(80, 24), 2 to PxSize(80, 74)), ScreenLayoutEngine.fit(split, empty, PxSize(80, 100)))

    val stack = LayoutTree.Stack(listOf(1, 2, 3), expanded = 2)
    val h = ScreenLayoutEngine.STACK_HEADER_PX
    assertEquals(mapOf(2 to PxSize(300, 200 - 2 * h)), ScreenLayoutEngine.fit(stack, size, PxSize(300, 200)))
  }

  @Test
  fun columnsSitSideBySideWithDividers() {
    val tree = LayoutTree.Columns(listOf(0.5f, 0.5f), listOf(LayoutTree.Leaf(1), LayoutTree.Leaf(3)))
    assertEquals(PxSize(100 + d + 60, 50), ScreenLayoutEngine.measure(tree, size))
    assertEquals(1, ScreenLayoutEngine.dividers(tree, size).size)
  }
}
