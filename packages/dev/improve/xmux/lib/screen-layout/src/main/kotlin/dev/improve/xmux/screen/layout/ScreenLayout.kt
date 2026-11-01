package dev.improve.xmux.screen.layout

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

  /**
   * The size every shown pane gets when the whole tree is resized to [target]: the inverse of
   * [measure]. A split shares its extent along the split axis in proportion to its children's
   * current sizes ([size]), so their relative widths survive, and gives both children its full
   * extent across it. Collapsed stack members are left out (their size never shows).
   */
  public fun fit(tree: LayoutTree, size: (Int) -> PxSize, target: PxSize): Map<Int, PxSize> {
    val out = LinkedHashMap<Int, PxSize>()
    fitInto(tree, size, target.width, target.height, out)
    return out
  }

  private fun fitInto(tree: LayoutTree, size: (Int) -> PxSize, width: Int, height: Int, out: MutableMap<Int, PxSize>) {
    when (tree) {
      is LayoutTree.Leaf -> out[tree.pane] = PxSize(width.coerceAtLeast(0), height.coerceAtLeast(0))
      is LayoutTree.Split -> {
        val a = measure(tree.first, size)
        val b = measure(tree.second, size)
        if (tree.horizontal) {
          val (first, second) = share(width - DIVIDER_PX, listOf(a.width, b.width), listOf(tree.ratio, 1f - tree.ratio))
          fitInto(tree.first, size, first, height, out)
          fitInto(tree.second, size, second, height, out)
        } else {
          val (first, second) = share(height - DIVIDER_PX, listOf(a.height, b.height), listOf(tree.ratio, 1f - tree.ratio))
          fitInto(tree.first, size, width, first, out)
          fitInto(tree.second, size, width, second, out)
        }
      }
      is LayoutTree.Stack -> fitInto(LayoutTree.Leaf(tree.expanded), size, width, height - (tree.panes.size - 1) * STACK_HEADER_PX, out)
      is LayoutTree.Columns -> {
        val widths = share(width - DIVIDER_PX * (tree.columns.size - 1).coerceAtLeast(0), tree.columns.map { measure(it, size).width }, tree.widths)
        for ((column, w) in tree.columns.zip(widths)) fitInto(column, size, w, height, out)
      }
    }
  }

  /** Splits [total] in proportion to [current], or to [fallback] when nothing has a size yet. */
  private fun share(total: Int, current: List<Int>, fallback: List<Float>): List<Int> {
    val available = total.coerceAtLeast(0)
    val weights = if (current.sum() > 0) current.map { it.toFloat() } else fallback.map { it.coerceAtLeast(0f) }
    val sum = weights.sum()
    val parts = weights.map { if (sum > 0f) (available * it / sum).toInt() else available / weights.size }.toMutableList()
    if (parts.isNotEmpty()) parts[parts.lastIndex] += available - parts.sum()
    return parts
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
