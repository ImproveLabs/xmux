package dev.improve.xmux.client

import dev.improve.xmux.core.CellGrid
import dev.improve.xmux.core.KeyInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Cursor of one terminal as last reported by the bridge. */
public data class Cursor(public val row: Int, public val col: Int, public val visible: Boolean)

public enum class Modifier(public val bit: Int) { Shift(1), Ctrl(2), Alt(4), Meta(8) }

/** A key press in cmux's vocabulary ("a", "enter", "f5", ...). */
public data class Key(public val name: String, public val modifiers: Set<Modifier> = emptySet())

public enum class MouseAction { Press, Release, Move, Scroll }

public data class MouseEvent(
  public val action: MouseAction,
  /** 0 left, 1 middle, 2 right. */
  public val button: Int,
  public val row: Int,
  public val col: Int,
  public val modifiers: Set<Modifier> = emptySet(),
  public val dx: Int = 0,
  /** Rows to scroll; negative scrolls up. */
  public val dy: Int = 0,
)

/** One attached cmux terminal: its grid as the renderer observes it, plus input back to cmux. */
public interface TerminalHandle : AutoCloseable {
  public val id: TerminalId
  /** The grid; a new instance when the terminal's size changes. */
  public val grid: StateFlow<CellGrid>
  public val cursor: StateFlow<Cursor>
  public val attach: StateFlow<AttachStatus>

  public fun write(text: String)
  public fun keys(vararg keys: Key)
  public fun mouse(event: MouseEvent)
  /** Sends what a key event encoded to (see `KeyEncoder`). */
  public fun send(input: KeyInput)
  /** Asks cmux to size the terminal to this viewer (opt-in; by default the Mac's grid is never changed). */
  public fun resize(cols: Int, rows: Int)
}

/** A live connection to one cmux session through a bridge. Call from the main thread. */
public interface XmuxSession : AutoCloseable {
  public val state: StateFlow<ConnectionState>
  public val topology: StateFlow<Topology>
  public val events: Flow<SessionEvent>
  /** The cmux session's name once the bridge welcomed us. */
  public val sessionName: String?

  /** Attaches on first use; the handle stays valid until closed or the session ends. */
  public fun terminal(id: TerminalId): TerminalHandle
  public fun focusDirection(screen: ScreenId, direction: Direction)
  public fun focusPane(pane: PaneId)
  public fun focusTab(tab: TabId)
}
