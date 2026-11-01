package dev.improve.xmux.compose

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Mutable per-window state the header reflects. */
public class TerminalWindowState(title: String) {
  public var title: String by mutableStateOf(title)
  /** Spinner at the right end of the header while attaching, reconnecting or stalled. */
  public var loading: Boolean by mutableStateOf(true)
}

/**
 * Static window chrome configuration: colors, sizes, header slots and traffic-light actions.
 * Embedders add their own indicators (a mic glyph, a caption) through the slots rather than by
 * forking the header. Panels run at 160 dpi, so dp here equals panel pixels.
 */
public class WindowChrome(
  public val headerHeightDp: Int = 56,
  public val cornerRadiusDp: Int = 14,
  public val bodyColor: Long = 0xFF1E1E1E,
  public val headerColor: Long = 0xFF2B2B2B,
  public val titleColor: Long = 0xFFC8C8C8,
  public val onClose: (() -> Unit)? = null,
  public val onMinimize: (() -> Unit)? = null,
  public val onZoom: (() -> Unit)? = null,
  /** Drawn right after the traffic lights. */
  public val leadingSlot: @Composable () -> Unit = {},
  /** Drawn at the right end, before the loading spinner. */
  public val trailingSlot: @Composable () -> Unit = {},
) {
  public companion object {
    public val Default: WindowChrome = WindowChrome()
  }
}

/** The same window around any body view (a whole cmux screen, for instance). */
@Composable
public fun WindowFrame(
  state: TerminalWindowState,
  modifier: Modifier = Modifier,
  chrome: WindowChrome = WindowChrome.Default,
  body: (Context) -> View,
  onViewCreated: (View) -> Unit = {},
) {
  val shape = RoundedCornerShape(chrome.cornerRadiusDp.dp)
  Column(
    modifier
      .clip(shape)
      .background(Color(chrome.bodyColor))
      .border(1.dp, Color(0x40FFFFFF), shape),
  ) {
    HeaderBar(state, chrome)
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3A3A)))
    AndroidView(
      factory = { ctx -> body(ctx).also(onViewCreated) },
      modifier = Modifier.fillMaxWidth().testTag("xmux-grid"),
    )
  }
}

@Composable
private fun HeaderBar(state: TerminalWindowState, chrome: WindowChrome) {
  Box(
    Modifier
      .fillMaxWidth()
      .height(chrome.headerHeightDp.dp)
      .background(Color(chrome.headerColor))
      .padding(horizontal = 16.dp)
      .testTag("xmux-header"),
  ) {
    Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      TrafficLight(0xFFFF5F57, "xmux-close", chrome.onClose)
      TrafficLight(0xFFFEBC2E, "xmux-minimize", chrome.onMinimize)
      TrafficLight(0xFF28C840, "xmux-zoom", chrome.onZoom)
      chrome.leadingSlot()
    }
    Text(
      state.title,
      Modifier.align(Alignment.Center).testTag("xmux-title"),
      color = Color(chrome.titleColor),
      fontSize = 15.sp,
      fontWeight = FontWeight.Medium,
    )
    Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      chrome.trailingSlot()
      if (state.loading) {
        CircularProgressIndicator(
          Modifier.size(16.dp).testTag("xmux-spinner"),
          color = Color(0xFFA0A0A0),
          strokeWidth = 2.dp,
        )
      }
    }
  }
}

@Composable
private fun TrafficLight(color: Long, tag: String, onClick: (() -> Unit)?) {
  val base = Modifier.size(12.dp).clip(CircleShape).background(Color(color)).testTag(tag)
  Box(if (onClick != null) base.clickable(onClick = onClick) else base)
}
