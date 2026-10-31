package dev.xmux.client

import dev.xmux.proto.Frame
import net.jpountz.lz4.LZ4Factory
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parsed 16-byte wire header; see `packages/dev/improve/xmux/schema/xmux.proto` and the bridge's `framing.rs`. */
public data class FrameHeader(
  public val bodyLength: Int,
  /** `Frame.payload` oneof field number (1 = hello, 4 = rows, ...). */
  public val kind: Int,
  public val flags: Int,
  public val version: Int,
  public val id: Int,
  public val sequence: Int,
)

public class DecodedFrame(public val header: FrameHeader, public val frame: Frame, public val consumed: Int)

public class FramingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Wire framing shared with the Rust bridge: header, optional LZ4 block body, one protobuf
 * `Frame`. Byte-for-byte compatibility is pinned by the goldens in `packages/dev/improve/xmux/testing/fixtures/frames/` that the
 * bridge's tests write.
 */
public object FrameCodec {
  public const val HEADER_LENGTH: Int = 16
  public const val PROTOCOL_VERSION: Int = 1
  public const val FLAG_LZ4: Int = 1
  public const val COMPRESS_THRESHOLD: Int = 256
  private const val KIND_MAX: Int = 19

  private val lz4 = LZ4Factory.fastestJavaInstance()

  /** Serializes [frame] into a wire frame, compressing bodies of [COMPRESS_THRESHOLD] bytes or more. */
  public fun encode(frame: Frame, id: Int, sequence: Int): ByteArray {
    val bytes = frame.toByteArray()
    val compress = bytes.size >= COMPRESS_THRESHOLD
    val body: ByteArray =
      if (compress) {
        val compressed = lz4.fastCompressor().compress(bytes)
        ByteBuffer.allocate(4 + compressed.size).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).put(compressed).array()
      } else bytes
    val out = ByteBuffer.allocate(HEADER_LENGTH + body.size).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(body.size)
    out.put(frame.payloadCase.number.toByte())
    out.put(if (compress) FLAG_LZ4.toByte() else 0)
    out.putShort(PROTOCOL_VERSION.toShort())
    out.putInt(id)
    out.putInt(sequence)
    out.put(body)
    return out.array()
  }

  public fun parseHeader(bytes: ByteArray, offset: Int = 0): FrameHeader {
    if (bytes.size - offset < HEADER_LENGTH) throw FramingException("short header: ${bytes.size - offset} bytes")
    val b = ByteBuffer.wrap(bytes, offset, HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
    val bodyLength = b.int
    val kind = b.get().toInt() and 0xFF
    val flags = b.get().toInt() and 0xFF
    val version = b.short.toInt() and 0xFFFF
    val id = b.int
    val sequence = b.int
    if (kind == 0 || kind > KIND_MAX) throw FramingException("unknown payload kind $kind")
    if (version != PROTOCOL_VERSION) throw FramingException("unsupported protocol version $version")
    return FrameHeader(bodyLength, kind, flags, version, id, sequence)
  }

  /** Largest frame body accepted from a stream. */
  public const val MAX_BODY: Int = 16 shl 20

  /** Reads one frame from a stream; null at a clean end of stream. */
  public fun read(input: InputStream): DecodedFrame? {
    val header = ByteArray(HEADER_LENGTH)
    if (!readFully(input, header, 0, HEADER_LENGTH, allowEof = true)) return null
    val h = parseHeader(header)
    if (h.bodyLength < 0 || h.bodyLength > MAX_BODY) throw FramingException("frame of ${h.bodyLength} bytes")
    val bytes = header.copyOf(HEADER_LENGTH + h.bodyLength)
    readFully(input, bytes, HEADER_LENGTH, h.bodyLength, allowEof = false)
    return decode(bytes)
  }

  private fun readFully(input: InputStream, into: ByteArray, offset: Int, length: Int, allowEof: Boolean): Boolean {
    var read = 0
    while (read < length) {
      val n = input.read(into, offset + read, length - read)
      if (n < 0) {
        if (allowEof && read == 0) return false
        throw EOFException("stream ended inside a frame")
      }
      read += n
    }
    return true
  }

  /** Decodes one frame starting at [offset]; [DecodedFrame.consumed] bytes were used. */
  public fun decode(bytes: ByteArray, offset: Int = 0): DecodedFrame {
    val header = parseHeader(bytes, offset)
    val start = offset + HEADER_LENGTH
    val end = start + header.bodyLength
    if (bytes.size < end) throw FramingException("short body: need $end bytes, have ${bytes.size}")
    val frame: Frame =
      try {
        if (header.flags and FLAG_LZ4 != 0) {
          if (header.bodyLength < 4) throw FramingException("lz4 body without length prefix")
          val n = ByteBuffer.wrap(bytes, start, 4).order(ByteOrder.LITTLE_ENDIAN).int
          val raw = ByteArray(n)
          lz4.fastDecompressor().decompress(bytes, start + 4, raw, 0, n)
          Frame.parseFrom(raw)
        } else Frame.parseFrom(ByteBuffer.wrap(bytes, start, header.bodyLength))
      } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
        throw FramingException("protobuf: ${e.message}", e)
      }
    if (frame.payloadCase.number != header.kind) {
      throw FramingException("header kind ${header.kind} does not match payload kind ${frame.payloadCase.number}")
    }
    return DecodedFrame(header, frame, end - offset)
  }
}
