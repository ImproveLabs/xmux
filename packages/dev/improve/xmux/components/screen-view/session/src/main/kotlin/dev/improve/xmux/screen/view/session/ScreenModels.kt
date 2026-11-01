package dev.improve.xmux.screen.view.session

import dev.improve.xmux.client.LayoutNode
import dev.improve.xmux.client.Screen
import dev.improve.xmux.client.TerminalId
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.screen.layout.LayoutTree
import dev.improve.xmux.screen.view.ScreenView

/** Topology screens -> what [ScreenView] draws. */
public object ScreenModels {
  public fun tree(node: LayoutNode): LayoutTree =
    when (node) {
      is LayoutNode.Leaf -> LayoutTree.Leaf(node.pane.value)
      is LayoutNode.Split -> LayoutTree.Split(node.horizontal, node.ratio, tree(node.first), tree(node.second))
      is LayoutNode.Stack -> LayoutTree.Stack(node.panes.map { it.value }, node.expanded.value)
      is LayoutNode.Viewport -> LayoutTree.Columns(node.widths, node.columns.map { tree(it) })
    }

  /** The panes of [screen], each showing its active tab's terminal grid from [session]. */
  public fun panes(screen: Screen, session: XmuxSession): List<ScreenView.Pane> =
    screen.panes.mapNotNull { pane ->
      val terminal = pane.activeTerminal ?: return@mapNotNull null
      val handle = session.terminal(terminal)
      val active = pane.tabs.indexOfFirst { it.id == pane.activeTab }.coerceAtLeast(0)
      val titles = pane.tabs.map { it.title.ifEmpty { session.topology.value.terminals[it.terminal]?.title ?: "" } }
      ScreenView.Pane(pane.id.value, handle.grid.value, titles, active)
    }

  /** Terminals [screen] shows (one per pane: the active tab's). */
  public fun terminals(screen: Screen): Set<TerminalId> = screen.panes.mapNotNull { it.activeTerminal }.toSet()
}
