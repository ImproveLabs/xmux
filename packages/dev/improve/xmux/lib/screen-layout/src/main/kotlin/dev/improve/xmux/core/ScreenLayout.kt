package dev.improve.xmux.core

/** A screen's pane arrangement, as cmux lays it out (see `LayoutNode` on the wire). */
public sealed interface LayoutTree {
  public data class Leaf(public val pane: Int) : LayoutTree

  /** [horizontal]: children side by side; otherwise stacked top to bottom. */
  public data class Split(
    public val horizontal: Boolean,
    public val ratio: Float,
    public val first: LayoutTree,
    public val second: LayoutTree,
  ) : LayoutTree

  /** Panes collapsed into one stack; only [expanded] shows its terminal, the others show a header. */
  public data class Stack(public val panes: List<Int>, public val expanded: Int) : LayoutTree

  /** Horizontally scrolling columns. */
  public data class Columns(public val widths: List<Float>, public val columns: List<LayoutTree>) : LayoutTree
}

public data class PxSize(public val width: Int, public val height: Int)

public data class PxRect(public val left: Int, public val top: Int, public val right: Int, public val bottom: Int) {
  public val width: Int get() = right - left
  public val height: Int get() = bottom - top

  public fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

/**
 * Lays a screen out from its panes' natural sizes. On the Mac, cmux sizes every terminal to its
 * pane, so placing panes at their own grid sizes (side by side for a horizontal split, stacked
 * for a vertical one) reproduces the Mac's arrangement without stretching any grid.
 */
public object ScreenLayoutEngine {
  public const val DIVIDER_PX: Int = 2
  /** Height of the header row of a collapsed stack member. */
  public const val STACK_HEADER_PX: Int = 28

  public fun measure(tree: LayoutTree, size: (Int) -> PxSize): PxSize =
    when (tree) {
      is LayoutTree.Leaf -> size(tree.pane)
      is LayoutTree.Split -> {
        val a = measure(tree.first, size)
        val b = measure(tree.second, size)
        if (tree.horizontal) PxSize(a.width + DIVIDER_PX + b.width, maxOf(a.height, b.height))
        else PxSize(maxOf(a.width, b.width), a.height + DIVIDER_PX + b.height)
      }
      is LayoutTree.Stack -> {
        val e = size(tree.expanded)
        PxSize(e.width, e.height + (tree.panes.size - 1) * STACK_HEADER_PX)
      }
      is LayoutTree.Columns -> {
        val sizes = tree.columns.map { measure(it, size) }
        PxSize(
          sizes.sumOf { it.width } + DIVIDER_PX * (sizes.size - 1).coerceAtLeast(0),
          sizes.maxOfOrNull { it.height } ?: 0,
        )
      }
    }

  /**
   * Where every pane goes, with the tree's top-left at ([left], [top]). Collapsed stack members
   * get a header-high rect.
   */
  public fun place(tree: LayoutTree, size: (Int) -> PxSize, left: Int = 0, top: Int = 0): Map<Int, PxRect> {
    val out = LinkedHashMap<Int, PxRect>()
    placeInto(tree, size, left, top, out)
    return out
  }

  private fun placeInto(tree: LayoutTree, size: (Int) -> PxSize, left: Int, top: Int, out: MutableMap<Int, PxRect>) {
    when (tree) {
      is LayoutTree.Leaf -> {
        val s = size(tree.pane)
        out[tree.pane] = PxRect(left, top, left + s.width, top + s.height)
      }
      is LayoutTree.Split -> {
        val a = measure(tree.first, size)
        placeInto(tree.first, size, left, top, out)
        if (tree.horizontal) placeInto(tree.second, size, left + a.width + DIVIDER_PX, top, out)
        else placeInto(tree.second, size, left, top + a.height + DIVIDER_PX, out)
      }
      is LayoutTree.Stack -> {
        val e = size(tree.expanded)
        var y = top
        for (pane in tree.panes) {
          if (pane == tree.expanded) {
            out[pane] = PxRect(left, y, left + e.width, y + e.height)
            y += e.height
          } else {
            out[pane] = PxRect(left, y, left + e.width, y + STACK_HEADER_PX)
            y += STACK_HEADER_PX
          }
        }
      }
      is LayoutTree.Columns -> {
        var x = left
        for (column in tree.columns) {
          placeInto(column, size, x, top, out)
          x += measure(column, size).width + DIVIDER_PX
        }
      }
    }
  }

  /** The dividers between siblings, for drawing. */
  public fun dividers(tree: LayoutTree, size: (Int) -> PxSize, left: Int = 0, top: Int = 0): List<PxRect> {
    val out = ArrayList<PxRect>()
    dividersInto(tree, size, left, top, out)
    return out
  }

  private fun dividersInto(tree: LayoutTree, size: (Int) -> PxSize, left: Int, top: Int, out: MutableList<PxRect>) {
    when (tree) {
      is LayoutTree.Split -> {
        val whole = measure(tree, size)
        val a = measure(tree.first, size)
        dividersInto(tree.first, size, left, top, out)
        if (tree.horizontal) {
          out.add(PxRect(left + a.width, top, left + a.width + DIVIDER_PX, top + whole.height))
          dividersInto(tree.second, size, left + a.width + DIVIDER_PX, top, out)
        } else {
          out.add(PxRect(left, top + a.height, left + whole.width, top + a.height + DIVIDER_PX))
          dividersInto(tree.second, size, left, top + a.height + DIVIDER_PX, out)
        }
      }
      is LayoutTree.Columns -> {
        val whole = measure(tree, size)
        var x = left
        for ((i, column) in tree.columns.withIndex()) {
          dividersInto(column, size, x, top, out)
          x += measure(column, size).width
          if (i < tree.columns.size - 1) out.add(PxRect(x, top, x + DIVIDER_PX, top + whole.height))
          x += DIVIDER_PX
        }
      }
      else -> {}
    }
  }
}
