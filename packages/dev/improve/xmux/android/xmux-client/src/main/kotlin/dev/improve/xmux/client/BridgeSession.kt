package dev.improve.xmux.client

import dev.improve.xmux.core.CellGrid
import dev.improve.xmux.core.KeyInput
import dev.improve.xmux.proto.AttachTerminal
import dev.improve.xmux.proto.DetachTerminal
import dev.improve.xmux.proto.FocusPane
import dev.improve.xmux.proto.FocusTab
import dev.improve.xmux.proto.Frame
import dev.improve.xmux.proto.Hello
import dev.improve.xmux.proto.Input
import dev.improve.xmux.proto.KeyPress
import dev.improve.xmux.proto.Ping
import dev.improve.xmux.proto.Resize
import dev.improve.xmux.proto.ResyncTerminal
import dev.improve.xmux.proto.Rows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.random.Random
import dev.improve.xmux.proto.MouseAction as WireMouseAction
import dev.improve.xmux.proto.MouseEvent as WireMouseEvent

/**
 * [XmuxSession] over any [BridgeConnection]: the `Hello`/`Welcome` handshake and pairing, the
 * topology, one [RowApplier] per open terminal, input, resync on gaps and checksum mismatches,
 * pings, stall detection and reconnects with backoff (terminals re-attach and resync from
 * snapshots).
 *
 * Everything runs on [scope]; give it the main dispatcher on Android so grids change on the
 * thread that draws them.
 */
public class BridgeSession(
  private val scope: CoroutineScope,
  private val connect: suspend () -> BridgeConnection,
  private val store: PairingStore,
  private val deviceName: String,
  private val clientName: String = "xmux-android",
  private val policy: Policy = Policy(),
  private val random: Random = Random.Default,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : XmuxSession {
  public data class Policy(
    val initialBackoffMs: Long = 500,
    val maxBackoffMs: Long = 10_000,
    val pingMs: Long = 2_000,
    /** No frame for this long while online means [ConnectionState.Stalled]. */
    val stallMs: Long = 6_000,
  )

  private class Fatal(message: String) : Exception(message)

  private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
  override val state: StateFlow<ConnectionState> = _state.asStateFlow()
  private val _topology = MutableStateFlow(Topology.EMPTY)
  override val topology: StateFlow<Topology> = _topology.asStateFlow()
  private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  override val events: Flow<SessionEvent> = _events.asSharedFlow()
  override var sessionName: String? = null
    private set

  private val handles = LinkedHashMap<TerminalId, Handle>()
  private var outbound: Channel<ByteArray>? = null
  private var closed = false
  private var pairingCode: String? = null
  private var lastFrameAt = 0L
  private var reachedOnline = false
  private val idempotencyBase = random.nextLong() and 0x7FFF_FFFF_0000_0000L
  private var idempotency = 0L
  private val loop: Job = scope.launch { run() }

  private suspend fun run() {
    var attempt = 0
    while (!closed) {
      reachedOnline = false
      val reason =
        try {
          connection()
          "connection closed"
        } catch (e: CancellationException) {
          throw e
        } catch (e: Fatal) {
          _state.value = ConnectionState.Closed(e.message)
          return
        } catch (e: Exception) {
          e.message ?: e.toString()
        }
      if (closed) break
      if (reachedOnline) attempt = 0
      attempt++
      _state.value = ConnectionState.Reconnecting(attempt, reason)
      delay(backoff(attempt))
    }
  }

  private fun backoff(attempt: Int): Long =
    (policy.initialBackoffMs shl (attempt - 1).coerceAtMost(10)).coerceAtMost(policy.maxBackoffMs)

  private fun newCode(): String = (0 until 6).joinToString("") { random.nextInt(10).toString() }

  /** One connection's life; ends with an exception (closed, failed, refused). */
  private suspend fun connection(): Unit =
    coroutineScope {
      _state.value = ConnectionState.Connecting
      val conn = connect()
      var online = false
      val queue = Channel<ByteArray>(Channel.UNLIMITED)
      try {
        val fingerprint = conn.peerFingerprint
        val token = store.token(fingerprint)
        val code = if (token == null) (pairingCode ?: newCode()).also { pairingCode = it } else ""
        conn.send(
          encode(
            Frame.newBuilder()
              .setHello(
                Hello.newBuilder()
                  .setClient(clientName)
                  .setProtocolVersion(FrameCodec.PROTOCOL_VERSION)
                  .setToken(token ?: "")
                  .setPairingCode(code)
                  .setDeviceName(deviceName),
              )
              .build(),
          ),
        )
        lastFrameAt = clock()
        conn.incoming.collect { decoded ->
          lastFrameAt = clock()
          val frame = decoded.frame
          when (frame.payloadCase) {
            Frame.PayloadCase.WELCOME -> {
              val w = frame.welcome
              if (w.protocolVersion != FrameCodec.PROTOCOL_VERSION) throw Fatal("the bridge speaks protocol ${w.protocolVersion}, this app ${FrameCodec.PROTOCOL_VERSION}")
              if (!w.paired) {
                _state.value = ConnectionState.Pairing(code)
                return@collect
              }
              if (w.token.isNotEmpty()) store.setToken(fingerprint, w.token)
              pairingCode = null
              sessionName = w.sessionName
              online = true
              reachedOnline = true
              outbound = queue
              _state.value = ConnectionState.Online
              launch { for (bytes in queue) conn.send(bytes) }
              launch { watchdog() }
              for (h in handles.values) send(attachFrame(h.id))
            }
            Frame.PayloadCase.BYE -> {
              val reason = frame.bye.reason
              when {
                token != null && reason.contains("not paired") -> {
                  // The bridge forgot us (`xmux-bridge unpair`): pair again.
                  store.setToken(fingerprint, null)
                  throw IOException(reason)
                }
                _state.value is ConnectionState.Pairing || reason.contains("protocol") -> throw Fatal(reason)
                else -> throw IOException(reason)
              }
            }
            else -> if (online) onFrame(frame)
          }
        }
        throw IOException("the bridge closed the connection")
      } finally {
        outbound = null
        queue.close()
        conn.close()
        for (h in handles.values) h.onDisconnected()
      }
    }

  private suspend fun watchdog() {
    var nonce = 0L
    while (true) {
      delay(policy.pingMs)
      send(Frame.newBuilder().setPing(Ping.newBuilder().setNonce(++nonce).setSentNs(clock() * 1_000_000)).build())
      val quiet = clock() - lastFrameAt
      val s = _state.value
      if (quiet > policy.stallMs && (s is ConnectionState.Online || s is ConnectionState.Stalled)) {
        _state.value = ConnectionState.Stalled(quiet)
      }
    }
  }

  private fun onFrame(frame: Frame) {
    if (_state.value is ConnectionState.Stalled) _state.value = ConnectionState.Online
    when (frame.payloadCase) {
      Frame.PayloadCase.TOPOLOGY -> setTopology(TopologyMapper.map(frame.topology))
      Frame.PayloadCase.ROWS -> handles[TerminalId(frame.rows.terminal)]?.onRows(frame.rows)
      Frame.PayloadCase.CURSOR -> handles[TerminalId(frame.cursor.terminal)]?.onCursor(frame.cursor)
      Frame.PayloadCase.CHECKSUM -> handles[TerminalId(frame.checksum.terminal)]?.onChecksum(frame.checksum)
      Frame.PayloadCase.ATTACH_STATE -> {
        val a = frame.attachState
        val status = AttachStatus.entries.getOrElse(a.statusValue) { AttachStatus.Error }
        handles[TerminalId(a.terminal)]?.onAttach(status)
        _events.tryEmit(SessionEvent.Attach(TerminalId(a.terminal), status, a.message.ifEmpty { null }))
      }
      Frame.PayloadCase.PONG -> {
        val rtt = (clock() - frame.pong.sentNs / 1_000_000).toInt()
        _events.tryEmit(SessionEvent.Latency(rtt.coerceAtLeast(0)))
      }
      else -> {}
    }
  }

  private fun setTopology(next: Topology) {
    val prev = _topology.value
    _topology.value = next
    val before = prev.screens.associateBy { it.id }
    val after = next.screens.associateBy { it.id }
    for (id in after.keys - before.keys) _events.tryEmit(SessionEvent.ScreenAdded(id))
    for (id in before.keys - after.keys) _events.tryEmit(SessionEvent.ScreenRemoved(id))
    for ((id, s) in after) {
      val old = before[id] ?: continue
      if (old.title != s.title) _events.tryEmit(SessionEvent.ScreenRenamed(id, s.title))
      if (old.focusedPane != s.focusedPane) _events.tryEmit(SessionEvent.FocusChanged(id, s.focusedPane))
    }
  }

  private fun encode(frame: Frame): ByteArray = FrameCodec.encode(frame, 0, 0)

  /** Queues a frame while online; drops it otherwise (input typed while offline is not replayed). */
  private fun send(frame: Frame) {
    outbound?.trySend(encode(frame))
  }

  private fun attachFrame(id: TerminalId): Frame =
    Frame.newBuilder().setAttachTerminal(AttachTerminal.newBuilder().setTerminal(id.value)).build()

  private fun nextIdempotency(): Long = idempotencyBase + (++idempotency)

  override fun terminal(id: TerminalId): TerminalHandle =
    handles.getOrPut(id) {
      Handle(id).also { send(attachFrame(id)) }
    }

  override fun focusDirection(screen: ScreenId, direction: Direction) {
    send(
      Frame.newBuilder()
        .setFocusDirection(
          dev.improve.xmux.proto.FocusDirection.newBuilder().setScreen(screen.value).setDirectionValue(direction.ordinal),
        )
        .build(),
    )
  }

  override fun focusPane(pane: PaneId) {
    send(Frame.newBuilder().setFocusPane(FocusPane.newBuilder().setPane(pane.value)).build())
  }

  override fun focusTab(tab: TabId) {
    send(Frame.newBuilder().setFocusTab(FocusTab.newBuilder().setTab(tab.value)).build())
  }

  override fun close() {
    if (closed) return
    closed = true
    loop.cancel()
    outbound = null
    _state.value = ConnectionState.Closed(null)
  }

  private inner class Handle(override val id: TerminalId) : TerminalHandle {
    private val applier = RowApplier()
    private val _grid = MutableStateFlow(placeholder())
    override val grid: StateFlow<CellGrid> = _grid.asStateFlow()
    private val _cursor = MutableStateFlow(Cursor(0, 0, true))
    override val cursor: StateFlow<Cursor> = _cursor.asStateFlow()
    private val _attach = MutableStateFlow(AttachStatus.Attaching)
    override val attach: StateFlow<AttachStatus> = _attach.asStateFlow()

    private fun placeholder(): CellGrid {
      val info = _topology.value.terminals[id]
      return CellGrid((info?.cols ?: 80).coerceAtLeast(1), (info?.rows ?: 24).coerceAtLeast(1))
    }

    fun onRows(rows: Rows) {
      when (applier.apply(rows)) {
        RowApplier.Result.NeedsSnapshot -> resync()
        RowApplier.Result.Resized, RowApplier.Result.Applied -> {
          val g = applier.grid!!
          if (_grid.value !== g) _grid.value = g
        }
      }
    }

    fun onCursor(c: dev.improve.xmux.proto.Cursor) {
      applier.apply(c)
      _cursor.value = Cursor(c.row, c.col, c.visible)
    }

    fun onChecksum(c: dev.improve.xmux.proto.Checksum) {
      if (!applier.check(c)) resync()
    }

    fun onAttach(status: AttachStatus) {
      _attach.value = status
    }

    fun onDisconnected() {
      applier.invalidate()
      _attach.value = AttachStatus.Stalled
    }

    private fun resync() {
      send(Frame.newBuilder().setResyncTerminal(ResyncTerminal.newBuilder().setTerminal(id.value)).build())
    }

    private fun input(build: Input.Builder.() -> Unit) {
      val input = Input.newBuilder().setTerminal(id.value).setIdempotency(nextIdempotency()).apply(build)
      send(Frame.newBuilder().setInput(input).build())
    }

    override fun write(text: String) {
      if (text.isNotEmpty()) input { setText(text) }
    }

    override fun keys(vararg keys: Key) {
      if (keys.isEmpty()) return
      input {
        for (k in keys) addKeys(KeyPress.newBuilder().setKey(k.name).setMods(k.modifiers.fold(0) { m, x -> m or x.bit }))
      }
    }

    override fun send(input: KeyInput) {
      when (input) {
        is KeyInput.Text -> write(input.text)
        is KeyInput.Key -> keys(Key(input.name, Modifier.entries.filter { input.mods and it.bit != 0 }.toSet()))
      }
    }

    override fun mouse(event: MouseEvent) {
      input {
        setMouse(
          WireMouseEvent.newBuilder()
            .setAction(
              when (event.action) {
                MouseAction.Press -> WireMouseAction.MOUSE_ACTION_PRESS
                MouseAction.Release -> WireMouseAction.MOUSE_ACTION_RELEASE
                MouseAction.Move -> WireMouseAction.MOUSE_ACTION_MOVE
                MouseAction.Scroll -> WireMouseAction.MOUSE_ACTION_SCROLL
              },
            )
            .setButton(event.button)
            .setRow(event.row)
            .setCol(event.col)
            .setMods(event.modifiers.fold(0) { m, x -> m or x.bit })
            .setDx(event.dx)
            .setDy(event.dy),
        )
      }
    }

    override fun resize(cols: Int, rows: Int) {
      send(Frame.newBuilder().setResize(Resize.newBuilder().setTerminal(id.value).setCols(cols).setRows(rows)).build())
    }

    override fun close() {
      if (handles.remove(id) != null) {
        send(Frame.newBuilder().setDetachTerminal(DetachTerminal.newBuilder().setTerminal(id.value)).build())
      }
    }
  }
}
