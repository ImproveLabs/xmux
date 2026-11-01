package dev.improve.xmux.spatial

import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.screen.layout.LayoutTree
import dev.improve.xmux.screen.layout.PxSize
import dev.improve.xmux.screen.layout.ScreenLayoutEngine
import dev.improve.xmux.screen.view.ScreenView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridFitTest {
  private val cw = 15f
  private val ch = 30f
  private val pad = ScreenView.DEFAULT_PADDING_PX

  private fun pane(id: Int, cols: Int, rows: Int, tabs: Int = 1) = ScreenView.Pane(id, CellGrid(cols, rows), List(tabs) { "tab $it" })

  private fun body(cols: Int, rows: Int) = PxSize((pad * 2 + cols * cw).toInt(), (pad * 2 + rows * ch).toInt())

  @Test
  fun aWiderWindowGivesALonePaneMoreColumns() {
    val panes = listOf(pane(1, 80, 24))
    assertEquals(mapOf(1 to GridSize(120, 24)), GridFit.grids(LayoutTree.Leaf(1), panes, body(120, 24), cw, ch))
    assertEquals(mapOf(1 to GridSize(80, 40)), GridFit.grids(LayoutTree.Leaf(1), panes, body(80, 40), cw, ch))
  }

  @Test
  fun partialCellsRoundDownSoTheScreenNeverOutgrowsTheBody() {
    val panes = listOf(pane(1, 80, 24))
    val target = PxSize(body(100, 30).width + (cw - 1).toInt(), body(100, 30).height + (ch - 1).toInt())
    assertEquals(mapOf(1 to GridSize(100, 30)), GridFit.grids(LayoutTree.Leaf(1), panes, target, cw, ch))
  }

  @Test
  fun splitPanesKeepTheirShareAndTabStripsCostRows() {
    val tree = LayoutTree.Split(true, 0.5f, LayoutTree.Leaf(1), LayoutTree.Leaf(2))
    val panes = listOf(pane(1, 60, 20), pane(2, 60, 20, tabs = 2))
    val one = body(60, 20)
    val target = PxSize(2 * (2 * one.width) + ScreenLayoutEngine.DIVIDER_PX, 2 * one.height)
    val grids = GridFit.grids(tree, panes, target, cw, ch)
    assertEquals(GridSize(cols = (one.width * 2 - pad * 2).div(cw).toInt(), rows = (one.height * 2 - pad * 2).div(ch).toInt()), grids[1])
    // Both panes were equally wide, so they still are.
    assertEquals(grids[1]!!.cols, grids[2]!!.cols)
    assertTrue(grids[2]!!.rows < grids[1]!!.rows, "the tab strip takes rows from the tabbed pane")
  }

  @Test
  fun tinyAndHugeBodiesAreClamped() {
    val panes = listOf(pane(1, 80, 24))
    assertEquals(mapOf(1 to GridSize(GridFit.MIN_COLS, GridFit.MIN_ROWS)), GridFit.grids(LayoutTree.Leaf(1), panes, PxSize(5, 5), cw, ch))
    assertEquals(mapOf(1 to GridSize(GridFit.MAX_CELLS, GridFit.MAX_CELLS)), GridFit.grids(LayoutTree.Leaf(1), panes, PxSize(100_000, 100_000), cw, ch))
  }
}
