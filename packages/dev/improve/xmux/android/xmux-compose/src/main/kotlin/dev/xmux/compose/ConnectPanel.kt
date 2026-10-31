package dev.xmux.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A bridge the panel can offer: a found one, or the last address used. */
public data class BridgeChoice(public val name: String, public val address: String, public val detail: String = "")

/** What the panel shows; apps map their session state to it. */
public sealed interface ConnectStatus {
  public data object Idle : ConnectStatus
  public data class Connecting(public val address: String) : ConnectStatus
  /** Waiting for `xmux-bridge pair` on the Mac. */
  public data class Pairing(public val code: String) : ConnectStatus
  public data class Online(public val session: String, public val screens: Int) : ConnectStatus
  public data class Reconnecting(public val attempt: Int, public val reason: String?) : ConnectStatus
  public data class Failed(public val reason: String) : ConnectStatus
}

private val panelBg = Color(0xFF1E1E1E)
private val fieldBg = Color(0xFF2B2B2B)
private val dim = Color(0xFFA0A0A0)
private val accent = Color(0xFF4A90D9)

/**
 * Pick a bridge (found on the network, or typed in), show the pairing code while the Mac
 * approves it, then a one-line status. Sized for a panel; dp equals panel pixels.
 */
@Composable
public fun ConnectPanel(
  status: ConnectStatus,
  bridges: List<BridgeChoice>,
  onConnect: (address: String) -> Unit,
  onDisconnect: () -> Unit,
  modifier: Modifier = Modifier,
  onDemo: (() -> Unit)? = null,
) {
  val shape = RoundedCornerShape(14.dp)
  Column(
    modifier.clip(shape).background(panelBg).border(1.dp, Color(0x40FFFFFF), shape).padding(28.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("xmux", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    when (status) {
      is ConnectStatus.Idle, is ConnectStatus.Failed -> {
        if (status is ConnectStatus.Failed) Text(status.reason, color = Color(0xFFFF8A80), fontSize = 16.sp, modifier = Modifier.testTag("xmux-error"))
        Text("Connect to the xmux bridge on your Mac", color = dim, fontSize = 18.sp)
        for (b in bridges) {
          Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(fieldBg).clickable { onConnect(b.address) }.padding(16.dp).testTag("xmux-bridge-${b.address}"),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(Modifier.weight(1f)) {
              Text(b.name, color = Color.White, fontSize = 20.sp)
              Text(listOf(b.address, b.detail).filter { it.isNotEmpty() }.joinToString(" · "), color = dim, fontSize = 15.sp)
            }
            Text("Connect", color = accent, fontSize = 18.sp)
          }
        }
        if (bridges.isEmpty()) Text("Looking for bridges on this network…", color = dim, fontSize = 16.sp)
        var address by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          BasicTextField(
            address,
            { address = it.trim() },
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(fieldBg).padding(14.dp).testTag("xmux-address"),
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Color.White),
            decorationBox = { inner ->
              Box {
                if (address.isEmpty()) Text("192.168.1.20 or mac.local:7272", color = dim, fontSize = 18.sp)
                inner()
              }
            },
          )
          Text(
            "Connect",
            color = if (address.isEmpty()) dim else accent,
            fontSize = 18.sp,
            modifier = Modifier.clickable(enabled = address.isNotEmpty()) { onConnect(address) }.padding(8.dp).testTag("xmux-connect"),
          )
        }
        if (onDemo != null) {
          Text("Try the demo without a Mac", color = accent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDemo).testTag("xmux-demo"))
        }
      }
      is ConnectStatus.Connecting, is ConnectStatus.Reconnecting -> {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          CircularProgressIndicator(Modifier.height(20.dp), color = dim, strokeWidth = 2.dp)
          Text(
            when (status) {
              is ConnectStatus.Connecting -> "Connecting to ${status.address}…"
              is ConnectStatus.Reconnecting -> "Reconnecting (attempt ${status.attempt})" + (status.reason?.let { ": $it" } ?: "")
              else -> ""
            },
            color = dim,
            fontSize = 18.sp,
          )
        }
        Text("Cancel", color = accent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDisconnect))
      }
      is ConnectStatus.Pairing -> {
        Text("On your Mac, run", color = dim, fontSize = 18.sp)
        Text("xmux-bridge pair", color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        Text("and type this code:", color = dim, fontSize = 18.sp)
        Text(
          status.code.chunked(3).joinToString(" "),
          color = Color.White,
          fontSize = 56.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.testTag("xmux-pairing-code"),
        )
        Text("Cancel", color = accent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDisconnect))
      }
      is ConnectStatus.Online -> {
        Text("Connected to ${status.session} · ${status.screens} screen${if (status.screens == 1) "" else "s"}", color = Color.White, fontSize = 18.sp, modifier = Modifier.testTag("xmux-online"))
        Spacer(Modifier.height(4.dp))
        Text("Disconnect", color = accent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDisconnect))
      }
    }
  }
}

/** Splits "host:port"; the port defaults to [defaultPort]. IPv6 literals go in brackets. */
public fun parseAddress(address: String, defaultPort: Int): Pair<String, Int>? {
  val a = address.trim()
  if (a.isEmpty()) return null
  if (a.startsWith("[")) {
    val end = a.indexOf(']').takeIf { it > 0 } ?: return null
    val host = a.substring(1, end)
    val port = a.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
    return host to port
  }
  val colon = a.lastIndexOf(':')
  if (colon > 0 && a.indexOf(':') == colon) {
    val port = a.substring(colon + 1).toIntOrNull() ?: return null
    return a.substring(0, colon) to port
  }
  return a to defaultPort
}
