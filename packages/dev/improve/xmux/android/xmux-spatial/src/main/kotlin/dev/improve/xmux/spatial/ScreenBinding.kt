package dev.improve.xmux.spatial

import android.view.KeyEvent
import android.view.MotionEvent
import dev.improve.xmux.client.AttachStatus
import dev.improve.xmux.client.MouseAction
import dev.improve.xmux.client.MouseEvent
import dev.improve.xmux.client.PaneId
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.client.TerminalHandle
import dev.improve.xmux.client.TerminalId
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.key.encoder.KeyEncoder
import dev.improve.xmux.screen.layout.PxSize
import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.screen.view.session.ScreenModels
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.window.frame.TerminalWindowState

/**
 * Keeps one window in step with one cmux screen: its panes and layout, its title and spinner,
 * and its natural size (the caller re-creates the panel when that changes). Input on the window
 * goes to the focused pane's terminal.
 */
internal class ScreenBinding(
  val session: XmuxSession,
  val screen: ScreenId,
  val font: TerminalFont,
  private val terminals: TerminalRefs,
  private val onFocus: () -> Unit,
) : ScreenView.Listener {
  val state = TerminalWindowState("")
  var view: ScreenView? = null
    set(value) {
      field = value
      value?.listener = this
      lastKey = null
    }

  /** The screen no longer exists in the session. */
  var gone: Boolean = false
    private set
  var size: PxSize = PxSize(0, 0)
    private set

  private var used: Set<TerminalId> = emptySet()
  private var lastKey: Any? = null
  private var focusedPane: Int = 0

  /** Updates the view from the session; returns true when the window's size changed. */
  fun tick(): Boolean {
    val topology = session.topology.value
    val s = topology.screen(screen)
    if (s == null) {
      gone = topology.revision > 0
      return false
    }
    val now = ScreenModels.terminals(s)
    for (t in now - used) terminals.acquire(session, t)
    for (t in used - now) terminals.release(session, t)
    used = now
    val tree = ScreenModels.tree(s.layout)
    val panes = ScreenModels.panes(s, session)
    focusedPane = s.focusedPane.value
    val key = listOf(topology.revision, panes.map { System.identityHashCode(it.grid) }, focusedPane, view)
    if (key != lastKey) {
      lastKey = key
      view?.update(tree, panes, focusedPane)
      state.title = s.title
    }
    state.loading = now.any { session.terminal(it).attach.value != AttachStatus.Attached }
    val next = ScreenView.measure(font, tree, panes)
    if (next != size) {
      size = next
      return true
    }
    return false
  }

  fun refreshIfDirty() {
    view?.refreshIfDirty()
  }

  /**
   * The grid each terminal needs for this screen to fill [body] pixels, for only the terminals
   * whose grid differs. Every tab of a pane gets the pane's size, so switching tabs does not
   * resize the window again.
   */
  fun gridsFor(body: PxSize): Map<TerminalId, GridSize> {
    val topology = session.topology.value
    val s = topology.screen(screen) ?: return emptyMap()
    val fitted = GridFit.grids(ScreenModels.tree(s.layout), ScreenModels.panes(s, session), body, font.cellWidth, font.cellHeight)
    val out = LinkedHashMap<TerminalId, GridSize>()
    for (pane in s.panes) {
      val size = fitted[pane.id.value] ?: continue
      for (tab in pane.tabs) {
        val current = if (tab.terminal == pane.activeTerminal) session.terminal(tab.terminal).grid.value.let { GridSize(it.cols, it.rows) }
          else topology.terminals[tab.terminal]?.let { GridSize(it.cols, it.rows) }
        if (current != size) out[tab.terminal] = size
      }
    }
    return out
  }

  /** Releases every terminal this window showed. */
  fun dispose() {
    for (t in used) terminals.release(session, t)
    used = emptySet()
  }

  /** The focused pane (the last one tapped or focused from code, else cmux's), once the screen is known. */
  val focusedPaneId: PaneId?
    get() {
      val s = session.topology.value.screen(screen) ?: return null
      return (s.panes.firstOrNull { it.id.value == focusedPane } ?: s.panes.firstOrNull())?.id
    }

  /** The active terminal of [focusedPaneId]. */
  fun focusedTerminalId(): TerminalId? = focusedPaneId?.let { terminalOf(it) }

  /** The active terminal of [pane], if the pane is on this screen. */
  fun terminalOf(pane: PaneId): TerminalId? =
    session.topology.value.screen(screen)?.panes?.firstOrNull { it.id == pane }?.activeTerminal

  /** Focuses [pane] here and in cmux. */
  fun focusPane(pane: PaneId) {
    if (pane.value == focusedPane) return
    focusedPane = pane.value
    view?.focusedPane = pane.value
    session.focusPane(pane)
  }

  private fun focusedTerminal(): TerminalHandle? = focusedTerminalId()?.let { session.terminal(it) }

  fun key(event: KeyEvent): Boolean {
    val input = KeyEncoder.encode(event) ?: return false
    val terminal = focusedTerminal() ?: return false
    terminal.send(input)
    return true
  }

  override fun onPointer(hit: ScreenView.Hit, action: Int) {
    if (action == MotionEvent.ACTION_DOWN) {
      onFocus()
      focusPane(PaneId(hit.pane))
    }
    if (hit.row < 0) return
    val terminal = paneTerminal(hit.pane) ?: return
    val mouseAction = if (action == MotionEvent.ACTION_DOWN) MouseAction.Press else MouseAction.Release
    terminal.mouse(MouseEvent(mouseAction, 0, hit.row, hit.col))
  }

  override fun onScroll(hit: ScreenView.Hit, rows: Int) {
    if (hit.row < 0) return
    paneTerminal(hit.pane)?.mouse(MouseEvent(MouseAction.Scroll, 0, hit.row, hit.col, dy = rows))
  }

  override fun onText(text: String) {
    focusedTerminal()?.write(text)
  }

  override fun onKey(event: KeyEvent): Boolean = key(event)

  private fun paneTerminal(pane: Int): TerminalHandle? {
    val s = session.topology.value.screen(screen) ?: return null
    return s.panes.firstOrNull { it.id.value == pane }?.activeTerminal?.let { session.terminal(it) }
  }

  override fun onTab(pane: Int, index: Int) {
    onFocus()
    val tab = session.topology.value.screen(screen)?.panes?.firstOrNull { it.id.value == pane }?.tabs?.getOrNull(index) ?: return
    session.focusTab(tab.id)
  }
}

/** How many windows show each terminal; the last one to stop showing it detaches. */
internal class TerminalRefs {
  private val counts = HashMap<Pair<XmuxSession, TerminalId>, Int>()

  fun acquire(session: XmuxSession, terminal: TerminalId) {
    val key = session to terminal
    counts[key] = (counts[key] ?: 0) + 1
    session.terminal(terminal)
  }

  fun release(session: XmuxSession, terminal: TerminalId) {
    val key = session to terminal
    val n = (counts[key] ?: return) - 1
    if (n <= 0) {
      counts.remove(key)
      session.terminal(terminal).close()
    } else counts[key] = n
  }
}
