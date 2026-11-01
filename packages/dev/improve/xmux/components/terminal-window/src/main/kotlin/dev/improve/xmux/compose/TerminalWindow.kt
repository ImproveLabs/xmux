package dev.improve.xmux.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.improve.xmux.core.CellGrid
import dev.improve.xmux.core.TerminalFont
import dev.improve.xmux.core.TerminalGridView

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
