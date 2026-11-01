package dev.improve.xmux.client

import dev.improve.xmux.proto.NodeKind
import dev.improve.xmux.proto.SplitDirection
import dev.improve.xmux.proto.Topology as WireTopology
import dev.improve.xmux.proto.LayoutNode as WireLayoutNode

/** Wire `Topology` -> [Topology]. */
public object TopologyMapper {
  public fun map(t: WireTopology): Topology =
    Topology(
      revision = t.revision,
      workspaces = t.workspacesList.map { Workspace(WorkspaceId(it.id), it.cmuxId, it.title) },
      screens =
        t.screensList.map { s ->
          Screen(
            id = ScreenId(s.id),
            cmuxId = s.cmuxId,
            title = s.title,
            workspace = WorkspaceId(s.workspace),
            layout = layout(s.layout),
            panes =
              s.panesList.map { p ->
                Pane(
                  id = PaneId(p.id),
                  cmuxId = p.cmuxId,
                  tabs = p.tabsList.map { Tab(TabId(it.id), it.cmuxId, it.title, TerminalId(it.terminal)) },
                  activeTab = TabId(p.activeTab),
                )
              },
            focusedPane = PaneId(s.focusedPane),
          )
        },
      terminals =
        t.terminalsList.associate {
          TerminalId(it.id) to TerminalInfo(TerminalId(it.id), it.cmuxId, it.cols, it.rows, it.title, it.running)
        },
      activeScreen = if (t.activeScreen == 0) null else ScreenId(t.activeScreen),
    )

  public fun layout(n: WireLayoutNode): LayoutNode =
    when (n.kind) {
      NodeKind.NODE_KIND_SPLIT ->
        LayoutNode.Split(
          horizontal = n.direction == SplitDirection.SPLIT_DIRECTION_HORIZONTAL,
          ratio = n.ratio,
          first = layout(n.getChildren(0)),
          second = layout(n.getChildren(1)),
        )
      NodeKind.NODE_KIND_STACK -> LayoutNode.Stack(n.childrenList.map { PaneId(it.pane) }, PaneId(n.pane))
      NodeKind.NODE_KIND_VIEWPORT -> LayoutNode.Viewport(n.widthsList, n.childrenList.map { layout(it) })
      else -> LayoutNode.Leaf(PaneId(n.pane))
    }
}
