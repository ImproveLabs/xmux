package dev.improve.xmux.client

import kotlinx.coroutines.flow.Flow

/**
 * One ordered, reliable byte stream to a bridge, already framed. The TLS implementation and the
 * in-memory `FakeBridge` (test fixtures) both implement it, so everything above this interface
 * is testable without a network.
 */
public interface BridgeConnection : AutoCloseable {
  /** Frames from the bridge in arrival order; completes when the connection closes. Collect once. */
  public val incoming: Flow<DecodedFrame>

  /** SHA-256 of the bridge's certificate; keys the pairing token. */
  public val peerFingerprint: String

  /** Sends one already-encoded wire frame (see [FrameCodec.encode]). */
  public suspend fun send(encoded: ByteArray)
}
