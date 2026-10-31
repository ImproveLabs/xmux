package dev.xmux.client.testing

import dev.xmux.client.BridgeConnection
import dev.xmux.client.DecodedFrame
import dev.xmux.client.FrameCodec
import dev.xmux.proto.Frame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * An in-memory [BridgeConnection] for tests and demos: push frames in with [deliver] (or a
 * recorded `.xmframes` stream with [deliverStream]), read back everything the client sent
 * through [sent], end the connection with [disconnect].
 */
public class FakeBridge(override val peerFingerprint: String = "fake-fingerprint") : BridgeConnection {
  private val frames = Channel<DecodedFrame>(Channel.UNLIMITED)
  private val outbound = ArrayList<ByteArray>()

  override val incoming: Flow<DecodedFrame> = frames.receiveAsFlow()

  /** Everything the client has sent, decoded, in order. */
  public val sent: List<Frame>
    get() = synchronized(outbound) { outbound.map { FrameCodec.decode(it).frame } }

  public var closed: Boolean = false
    private set

  /** Delivers an already-encoded wire frame as if it came from the bridge. */
  public fun deliver(encoded: ByteArray) {
    frames.trySend(FrameCodec.decode(encoded))
  }

  public fun deliver(frame: Frame) {
    deliver(FrameCodec.encode(frame, 0, 0))
  }

  /** Delivers every frame of a concatenated stream (a `.xmframes` file). */
  public fun deliverStream(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
      val decoded = FrameCodec.decode(bytes, offset)
      frames.trySend(decoded)
      offset += decoded.consumed
    }
  }

  /** Ends the connection from the bridge's side. */
  public fun disconnect() {
    frames.close()
  }

  override suspend fun send(encoded: ByteArray) {
    synchronized(outbound) { outbound.add(encoded) }
  }

  override fun close() {
    closed = true
    frames.close()
  }
}
