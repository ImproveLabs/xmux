package dev.xmux.spatial

import android.view.KeyEvent
import android.view.MotionEvent
import dev.xmux.client.AttachStatus
import dev.xmux.client.MouseAction
import dev.xmux.client.MouseEvent
import dev.xmux.client.PaneId
import dev.xmux.client.ScreenId
import dev.xmux.client.ScreenModels
import dev.xmux.client.TerminalHandle
import dev.xmux.client.TerminalId
import dev.xmux.client.XmuxSession
import dev.xmux.compose.TerminalWindowState
import dev.xmux.core.KeyEncoder
import dev.xmux.core.PxSize
import dev.xmux.core.ScreenView
import dev.xmux.core.TerminalFont

/**
 * Keeps one window in step with one cmux screen: its panes and layout, its title and spinner,
 * and its natural size (the caller re-creates the panel when that changes). Input on the window
 * goes to the focused pane's terminal.
 */
internal class ScreenBinding(
  val session: XmuxSession,
  val screen: ScreenId,
  private val font: TerminalFont,
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

  /** Releases every terminal this window showed. */
  fun dispose() {
    for (t in used) terminals.release(session, t)
    used = emptySet()
  }

  private fun focusedTerminal(): TerminalHandle? {
    val s = session.topology.value.screen(screen) ?: return null
    val pane = s.panes.firstOrNull { it.id.value == focusedPane } ?: s.panes.firstOrNull() ?: return null
    return pane.activeTerminal?.let { session.terminal(it) }
  }

  fun key(event: KeyEvent): Boolean {
    val input = KeyEncoder.encode(event) ?: return false
    val terminal = focusedTerminal() ?: return false
    terminal.send(input)
    return true
  }

  override fun onPointer(hit: ScreenView.Hit, action: Int) {
    if (action == MotionEvent.ACTION_DOWN) {
      onFocus()
      if (hit.pane != focusedPane) {
        focusedPane = hit.pane
        view?.focusedPane = hit.pane
        session.focusPane(PaneId(hit.pane))
      }
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
