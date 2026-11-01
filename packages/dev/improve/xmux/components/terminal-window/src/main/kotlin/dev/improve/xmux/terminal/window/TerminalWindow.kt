package dev.improve.xmux.terminal.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.terminal.grid.TerminalGridView
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.xmux.window.frame.WindowFrame

/** A macOS-style window: traffic lights, centered title, slots, spinner, and the terminal grid below. */
@Composable
public fun TerminalWindow(
  state: TerminalWindowState,
  grid: CellGrid,
  font: TerminalFont,
  modifier: Modifier = Modifier,
  chrome: WindowChrome = WindowChrome.Default,
  onViewCreated: (TerminalGridView) -> Unit = {},
) {
  WindowFrame(
    state,
    modifier,
    chrome,
    body = { ctx -> TerminalGridView(ctx, grid, font).also { it.bodyColor = chrome.bodyColor.toInt() } },
    onViewCreated = { onViewCreated(it as TerminalGridView) },
  )
}
