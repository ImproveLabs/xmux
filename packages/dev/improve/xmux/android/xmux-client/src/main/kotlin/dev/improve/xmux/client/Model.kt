package dev.improve.xmux.client

/** Connection lifecycle as seen by the embedding app. */
public sealed interface ConnectionState {
  public data object Connecting : ConnectionState
  /** The bridge has not paired this headset yet: show [code] and ask the user to run `xmux-bridge pair` on the Mac. */
  public data class Pairing(public val code: String) : ConnectionState
  public data object Online : ConnectionState
  /** No frames for [sinceMs]; the bridge is reachable but the Mac side is not delivering. */
  public data class Stalled(public val sinceMs: Long) : ConnectionState
  public data class Reconnecting(public val attempt: Int, public val reason: String? = null) : ConnectionState
  public data class Closed(public val reason: String?) : ConnectionState
}

public enum class Direction { Left, Right, Up, Down }

public enum class AttachStatus { Attaching, Attached, Stalled, Detached, Error }

public sealed interface SessionEvent {
  public data class ScreenAdded(public val screen: ScreenId) : SessionEvent
  public data class ScreenRemoved(public val screen: ScreenId) : SessionEvent
  public data class ScreenRenamed(public val screen: ScreenId, public val title: String) : SessionEvent
  public data class FocusChanged(public val screen: ScreenId, public val pane: PaneId) : SessionEvent
  public data class Attach(public val terminal: TerminalId, public val status: AttachStatus, public val message: String?) : SessionEvent
  public data class Latency(public val rttMs: Int) : SessionEvent
}

/** cmux layout tree of one screen: split / leaf / stack / viewport nodes. */
public sealed interface LayoutNode {
  /** [horizontal]: children side by side; otherwise stacked. [ratio] is the first child's share. */
  public data class Split(public val horizontal: Boolean, public val ratio: Float, public val first: LayoutNode, public val second: LayoutNode) : LayoutNode
  public data class Leaf(public val pane: PaneId) : LayoutNode
  public data class Stack(public val panes: List<PaneId>, public val expanded: PaneId) : LayoutNode
  public data class Viewport(public val widths: List<Float>, public val columns: List<LayoutNode>) : LayoutNode
}

public data class Tab(public val id: TabId, public val cmuxId: String, public val title: String, public val terminal: TerminalId)

public data class Pane(public val id: PaneId, public val cmuxId: String, public val tabs: List<Tab>, public val activeTab: TabId) {
  public val activeTerminal: TerminalId? get() = (tabs.firstOrNull { it.id == activeTab } ?: tabs.firstOrNull())?.terminal
}

public data class Screen(
  public val id: ScreenId,
  public val cmuxId: String,
  public val title: String,
  public val workspace: WorkspaceId,
  public val layout: LayoutNode,
  public val panes: List<Pane>,
  public val focusedPane: PaneId,
)

public data class Workspace(public val id: WorkspaceId, public val cmuxId: String, public val title: String)

/** A terminal and the grid size it has on the Mac. */
public data class TerminalInfo(
  public val id: TerminalId,
  public val cmuxId: String,
  public val cols: Int,
  public val rows: Int,
  public val title: String,
  public val running: Boolean,
)

public data class Topology(
  public val revision: Int,
  public val workspaces: List<Workspace>,
  public val screens: List<Screen>,
  public val terminals: Map<TerminalId, TerminalInfo>,
  public val activeScreen: ScreenId?,
) {
  public fun screen(id: ScreenId): Screen? = screens.firstOrNull { it.id == id }

  public companion object {
    public val EMPTY: Topology = Topology(0, emptyList(), emptyList(), emptyMap(), null)
  }
}
