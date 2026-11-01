package dev.improve.xmux.spatial

import dev.improve.xmux.screen.layout.LayoutTree
import dev.improve.xmux.screen.layout.PxSize
import dev.improve.xmux.screen.layout.ScreenLayoutEngine
import dev.improve.xmux.screen.view.ScreenView

/** A terminal grid size in cells. */
public data class GridSize(public val cols: Int, public val rows: Int)

/**
 * Turns a resized window body back into terminal grids: the inverse of how [ScreenView] sizes a
 * screen from its panes' grids. Every pane is rounded down to whole cells, so the screen's natural
 * size after the resize never exceeds the body the user made.
 */
internal object GridFit {
  const val MIN_COLS: Int = 10
  const val MIN_ROWS: Int = 3
  /** The bridge clamps every requested dimension to this. */
  const val MAX_CELLS: Int = 1000

  /** Grid per pane id for [panes] laid out by [tree] to fill [body] pixels. */
  fun grids(
    tree: LayoutTree,
    panes: List<ScreenView.Pane>,
    body: PxSize,
    cellWidth: Float,
    cellHeight: Float,
    paddingPx: Float = ScreenView.DEFAULT_PADDING_PX,
  ): Map<Int, GridSize> {
    val byId = panes.associateBy { it.id }
    val natural = { id: Int -> byId[id]?.let { paneSize(it, cellWidth, cellHeight, paddingPx) } ?: PxSize(0, 0) }
    return ScreenLayoutEngine.fit(tree, natural, body).mapNotNull { (id, size) ->
      val pane = byId[id] ?: return@mapNotNull null
      val strip = if (pane.tabs.size > 1) ScreenView.TAB_STRIP_PX else 0
      val cols = ((size.width - paddingPx * 2) / cellWidth).toInt().coerceIn(MIN_COLS, MAX_CELLS)
      val rows = ((size.height - strip - paddingPx * 2) / cellHeight).toInt().coerceIn(MIN_ROWS, MAX_CELLS)
      id to GridSize(cols, rows)
    }.toMap()
  }

  /** Same arithmetic as [ScreenView.paneSize], without a font, so it can be tested. */
  private fun paneSize(pane: ScreenView.Pane, cellWidth: Float, cellHeight: Float, paddingPx: Float): PxSize =
    PxSize(
      (paddingPx * 2 + pane.grid.cols * cellWidth).toInt(),
      (paddingPx * 2 + pane.grid.rows * cellHeight).toInt() + if (pane.tabs.size > 1) ScreenView.TAB_STRIP_PX else 0,
    )
}
