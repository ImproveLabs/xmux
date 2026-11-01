package dev.improve.xmux.client

import dev.improve.xmux.proto.Frame
import dev.improve.xmux.proto.Underline
import dev.improve.xmux.proto.frame
import dev.improve.xmux.proto.row
import dev.improve.xmux.proto.rows
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameCodecTest {
  /** Same content as the bridge's `rows_frame(fill)` test helper. */
  private fun rowsFrame(fill: Int): Frame = frame {
    rows = rows {
      terminal = 7
      generation = 2
      sequence = 99
      cols = 80
      rows = 24
      dirty += row {
        index = 3
        // Qualified: kotlin.run on the DSL receiver would shadow the generated builder.
        runs += dev.improve.xmux.proto.run {
          text = "x".repeat(fill)
          fg = 0xFFD4D4D4.toInt()
          bg = 0
          attrs = 1
          underline = Underline.UNDERLINE_SINGLE
          widthHint = 0
        }
      }
      fullReset = false
    }
  }

  private fun golden(name: String): ByteArray =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing golden $name (run the bridge tests with XMUX_UPDATE_GOLDENS=1)" }.readBytes()

  @Test
  fun decodesTheSmallGoldenWrittenByTheRustBridge() {
    val decoded = FrameCodec.decode(golden("rows-small.bin"))
    assertEquals(Frame.PayloadCase.ROWS.number, decoded.header.kind)
    assertEquals(0, decoded.header.flags)
    assertEquals(7, decoded.header.id)
    assertEquals(99, decoded.header.sequence)
    val rows = decoded.frame.rows
    assertEquals(7, rows.terminal)
    assertEquals(2, rows.generation)
    assertEquals(80, rows.cols)
    val row = rows.dirtyList[0]
    assertEquals(3, row.index)
    val run = row.runsList[0]
    assertEquals("xxx", run.text)
    assertEquals(0xFFD4D4D4.toInt(), run.fg)
    assertEquals(Underline.UNDERLINE_SINGLE, run.underline)
  }

  @Test
  fun decodesTheLz4GoldenWrittenByTheRustBridge() {
    val decoded = FrameCodec.decode(golden("rows-lz4.bin"))
    assertEquals(FrameCodec.FLAG_LZ4, decoded.header.flags and FrameCodec.FLAG_LZ4)
    assertEquals(100, decoded.header.sequence)
    assertEquals("x".repeat(4000), decoded.frame.rows.dirtyList[0].runsList[0].text)
  }

  @Test
  fun kotlinEncodingMatchesTheRustBytesExactly() {
    // Uncompressed frames must be byte-identical: same protobuf field order, same header.
    assertContentEquals(golden("rows-small.bin"), FrameCodec.encode(rowsFrame(3), 7, 99))
  }

  @Test
  fun kotlinLz4FramesDecodeToTheSameContentAsRustOnes() {
    // lz4-java and lz4_flex emit different (valid) block streams, so compare after decoding.
    val mine = FrameCodec.decode(FrameCodec.encode(rowsFrame(4000), 7, 100))
    val theirs = FrameCodec.decode(golden("rows-lz4.bin"))
    assertEquals(theirs.header, mine.header.copy(bodyLength = theirs.header.bodyLength))
    assertEquals(theirs.frame, mine.frame)
  }

  @Test
  fun roundTripsAndReportsConsumedBytes() {
    val a = FrameCodec.encode(rowsFrame(3), 1, 1)
    val b = FrameCodec.encode(rowsFrame(1000), 1, 2)
    val stream = a + b
    val first = FrameCodec.decode(stream, 0)
    assertEquals(a.size, first.consumed)
    assertEquals(rowsFrame(3), first.frame)
    val second = FrameCodec.decode(stream, first.consumed)
    assertEquals(b.size, second.consumed)
    assertEquals(2, second.header.sequence)
    assertEquals(rowsFrame(1000), second.frame)
    assertTrue(b.size < 1000, "lz4 should shrink repetitive text")
  }

  @Test
  fun rejectsTruncationWrongVersionAndKindMismatch() {
    val a = FrameCodec.encode(rowsFrame(3), 1, 1)
    assertFailsWith<FramingException> { FrameCodec.decode(a.copyOf(10)) }
    assertFailsWith<FramingException> { FrameCodec.decode(a.copyOf(a.size - 1)) }
    val badVersion = a.copyOf().also { it[6] = 9 }
    assertFailsWith<FramingException> { FrameCodec.decode(badVersion) }
    val badKind = a.copyOf().also { it[4] = 5 } // claims Cursor, body is Rows
    assertFailsWith<FramingException> { FrameCodec.decode(badKind) }
  }
}
