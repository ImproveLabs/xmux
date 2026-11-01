package dev.improve.xmux.client

import dev.improve.xmux.client.testing.FakeBridge
import dev.improve.xmux.key.encoder.KeyInput
import dev.improve.xmux.proto.Bye
import dev.improve.xmux.proto.Frame
import dev.improve.xmux.proto.LayoutNode as WireLayoutNode
import dev.improve.xmux.proto.NodeKind
import dev.improve.xmux.proto.Pane as WirePane
import dev.improve.xmux.proto.Row
import dev.improve.xmux.proto.Rows
import dev.improve.xmux.proto.Run
import dev.improve.xmux.proto.Screen as WireScreen
import dev.improve.xmux.proto.Tab as WireTab
import dev.improve.xmux.proto.Terminal as WireTerminal
import dev.improve.xmux.proto.Topology as WireTopology
import dev.improve.xmux.proto.Welcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeSessionTest {
  private class Harness(scope: TestScope, val store: PairingStore = InMemoryPairingStore()) {
    val bridges = ArrayList<FakeBridge>()
    val session =
      BridgeSession(
        scope = scope.backgroundScope,
        connect = { FakeBridge("fp").also { bridges += it } },
        store = store,
        deviceName = "Quest 3",
        random = Random(7),
        clock = { scope.testScheduler.currentTime },
      )
    val bridge: FakeBridge get() = bridges.last()
  }

  private fun welcome(paired: Boolean, token: String = "", version: Int = 1): Frame =
    Frame.newBuilder().setWelcome(Welcome.newBuilder().setPaired(paired).setToken(token).setProtocolVersion(version).setSessionName("main")).build()

  private fun topology(): Frame =
    Frame.newBuilder()
      .setTopology(
        WireTopology.newBuilder()
          .setRevision(1)
          .addScreens(
            WireScreen.newBuilder()
              .setId(2)
              .setTitle("build")
              .setLayout(WireLayoutNode.newBuilder().setKind(NodeKind.NODE_KIND_LEAF).setPane(3))
              .addPanes(WirePane.newBuilder().setId(3).setActiveTab(4).addTabs(WireTab.newBuilder().setId(4).setTerminal(5)))
              .setFocusedPane(3),
          )
          .addTerminals(WireTerminal.newBuilder().setId(5).setCols(10).setRows(2).setTitle("sh")),
      )
      .build()

  private fun rows(gen: Int, seq: Int, reset: Boolean, text: String): Frame =
    Frame.newBuilder()
      .setRows(
        Rows.newBuilder().setTerminal(5).setGeneration(gen).setSequence(seq).setCols(10).setRows(2).setFullReset(reset)
          .addDirty(Row.newBuilder().setIndex(0).addRuns(Run.newBuilder().setText(text))),
      )
      .build()

  private fun TestScope.online(h: Harness) {
    runCurrent()
    h.bridge.deliver(welcome(paired = true))
    h.bridge.deliver(topology())
    runCurrent()
  }

  @Test
  fun pairsWithACodeThenStoresTheToken() =
    runTest {
      val h = Harness(this)
      runCurrent()
      val hello = h.bridge.sent.first().hello
      assertEquals("", hello.token)
      assertTrue(hello.pairingCode.matches(Regex("[0-9]{6}")), hello.pairingCode)
      assertEquals("Quest 3", hello.deviceName)
      assertEquals(FrameCodec.PROTOCOL_VERSION, hello.protocolVersion)

      h.bridge.deliver(welcome(paired = false))
      runCurrent()
      assertEquals(ConnectionState.Pairing(hello.pairingCode), h.session.state.value)

      h.bridge.deliver(welcome(paired = true, token = "secret"))
      runCurrent()
      assertEquals(ConnectionState.Online, h.session.state.value)
      assertEquals("secret", h.store.token("fp"))
      assertEquals("main", h.session.sessionName)
      h.session.close()
    }

  @Test
  fun aStoredTokenIsSentInsteadOfACode() =
    runTest {
      val store = InMemoryPairingStore().apply { setToken("fp", "secret") }
      val h = Harness(this, store)
      runCurrent()
      val hello = h.bridge.sent.first().hello
      assertEquals("secret", hello.token)
      assertEquals("", hello.pairingCode)
      h.session.close()
    }

  @Test
  fun terminalsAttachApplyRowsAndAskForASnapshotAfterAGap() =
    runTest {
      val h = Harness(this)
      online(h)
      val screen = h.session.topology.value.screens.single()
      assertEquals("build", screen.title)
      val handle = h.session.terminal(TerminalId(5))
      assertEquals(10, handle.grid.value.cols, "a placeholder sized from the topology")
      runCurrent()
      assertEquals(5, h.bridge.sent.last().attachTerminal.terminal)

      h.bridge.deliver(rows(1, 1, true, "hello"))
      runCurrent()
      assertEquals("hello", handle.grid.value.rowText(0))
      h.bridge.deliver(rows(1, 3, false, "skipped"))
      runCurrent()
      assertEquals(5, h.bridge.sent.last().resyncTerminal.terminal)
      assertEquals("hello", handle.grid.value.rowText(0))
      h.session.close()
    }

  @Test
  fun inputCarriesTheTerminalAndIncreasingIdempotency() =
    runTest {
      val h = Harness(this)
      online(h)
      val t = h.session.terminal(TerminalId(5))
      t.write("ls\n")
      t.keys(Key("c", setOf(Modifier.Ctrl)))
      t.send(KeyInput.Key("left", 4))
      t.mouse(MouseEvent(MouseAction.Scroll, 0, 3, 4, dy = -2))
      runCurrent()
      val inputs = h.bridge.sent.filter { it.hasInput() }.map { it.input }
      assertEquals(4, inputs.size)
      assertTrue(inputs.all { it.terminal == 5 })
      assertTrue(inputs.zipWithNext().all { (a, b) -> b.idempotency == a.idempotency + 1 })
      assertEquals("ls\n", inputs[0].text)
      assertEquals("c" to 2, inputs[1].getKeys(0).key to inputs[1].getKeys(0).mods)
      assertEquals("left" to 4, inputs[2].getKeys(0).key to inputs[2].getKeys(0).mods)
      assertEquals(-2, inputs[3].mouse.dy)
      h.session.close()
    }

  @Test
  fun reconnectsWithBackoffAndReattachesOpenTerminals() =
    runTest {
      val h = Harness(this)
      online(h)
      h.session.terminal(TerminalId(5))
      runCurrent()
      h.bridges[0].disconnect()
      runCurrent()
      assertIs<ConnectionState.Reconnecting>(h.session.state.value)
      advanceTimeBy(600)
      runCurrent()
      assertEquals(2, h.bridges.size)
      h.bridge.deliver(welcome(paired = true))
      runCurrent()
      assertEquals(ConnectionState.Online, h.session.state.value)
      assertEquals(5, h.bridge.sent.last().attachTerminal.terminal, "open terminals re-attach")
      h.session.close()
    }

  @Test
  fun aRevokedTokenIsForgottenAndTheHeadsetPairsAgain() =
    runTest {
      val store = InMemoryPairingStore().apply { setToken("fp", "old") }
      val h = Harness(this, store)
      runCurrent()
      h.bridge.deliver(Frame.newBuilder().setBye(Bye.newBuilder().setReason("not paired: connect with a six-digit pairing code")).build())
      runCurrent()
      assertNull(store.token("fp"))
      advanceTimeBy(600)
      runCurrent()
      assertEquals(6, h.bridge.sent.first().hello.pairingCode.length)
      h.session.close()
    }

  @Test
  fun aRejectedPairingClosesTheSession() =
    runTest {
      val h = Harness(this)
      runCurrent()
      h.bridge.deliver(welcome(paired = false))
      h.bridge.deliver(Frame.newBuilder().setBye(Bye.newBuilder().setReason("pairing rejected")).build())
      runCurrent()
      assertEquals(ConnectionState.Closed("pairing rejected"), h.session.state.value)
    }

  @Test
  fun silenceWhileOnlineIsAStall() =
    runTest {
      val h = Harness(this)
      online(h)
      advanceTimeBy(8_100)
      runCurrent()
      assertIs<ConnectionState.Stalled>(h.session.state.value)
      h.bridge.deliver(topology())
      runCurrent()
      assertEquals(ConnectionState.Online, h.session.state.value)
      h.session.close()
    }
}
