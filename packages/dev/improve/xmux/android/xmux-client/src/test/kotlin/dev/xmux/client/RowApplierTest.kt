package dev.xmux.client

import dev.xmux.core.Attr
import dev.xmux.proto.Checksum
import dev.xmux.proto.Row
import dev.xmux.proto.Rows
import dev.xmux.proto.Run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RowApplierTest {
  private fun run(text: String, hint: Int = 0, fg: Int = 0) = Run.newBuilder().setText(text).setWidthHint(hint).setFg(fg).build()

  private fun rows(gen: Int, seq: Int, reset: Boolean, cols: Int = 6, rows: Int = 2, vararg dirty: Pair<Int, List<Run>>): Rows =
    Rows.newBuilder()
      .setTerminal(1)
      .setGeneration(gen)
      .setSequence(seq)
      .setCols(cols)
      .setRows(rows)
      .setFullReset(reset)
      .addAllDirty(dirty.map { (i, runs) -> Row.newBuilder().setIndex(i).addAllRuns(runs).build() })
      .build()

  @Test
  fun widthHintsMapRunsToCells() {
    val a = RowApplier()
    a.apply(rows(1, 1, true, 8, 1, 0 to listOf(run("a"), run("你好", 2), run("é", 1), run("🇨🇦", 3))))
    val g = a.grid!!
    assertEquals(listOf("a", "你", "", "好", "", "é", "🇨🇦", ""), (0 until 8).map { g.cellText(0, it) })
    assertTrue(g.attrs[g.index(0, 2)] and Attr.WIDE_SPACER != 0)
  }

  @Test
  fun deltasMustFollowInSequenceAndResetsRecover() {
    val a = RowApplier()
    assertEquals(RowApplier.Result.Resized, a.apply(rows(1, 1, true, 6, 2, 0 to listOf(run("one")))))
    val grid = a.grid
    assertEquals(RowApplier.Result.Applied, a.apply(rows(1, 2, false, 6, 2, 1 to listOf(run("two")))))
    assertSame(grid, a.grid, "same size keeps the grid instance")
    assertEquals(RowApplier.Result.NeedsSnapshot, a.apply(rows(1, 4, false, 6, 2, 1 to listOf(run("lost")))))
    assertFalse(a.synced)
    assertEquals(RowApplier.Result.NeedsSnapshot, a.apply(rows(1, 5, false, 6, 2)))
    assertEquals(RowApplier.Result.Applied, a.apply(rows(1, 5, true, 6, 2, 0 to listOf(run("one")), 1 to listOf(run("three")))))
    assertEquals("three", a.grid!!.rowText(1))
    assertEquals(RowApplier.Result.Resized, a.apply(rows(2, 1, true, 9, 3)))
    assertNotSame(grid, a.grid)
    assertEquals(9, a.grid!!.cols)
  }

  @Test
  fun rowsClipAtTheRightEdgeWithoutSplittingWideCells() {
    val a = RowApplier()
    a.apply(rows(1, 1, true, 3, 1, 0 to listOf(run("ab"), run("你", 2), run("c"))))
    assertEquals("ab", a.grid!!.rowText(0))
  }

  @Test
  fun checksumMismatchUnsyncs() {
    val a = RowApplier()
    a.apply(rows(1, 1, true, 3, 1, 0 to listOf(run("abc"))))
    val ok = Checksum.newBuilder().setTerminal(1).setGeneration(1).setSequence(1).setFnv1A(a.checksum()).build()
    assertTrue(a.check(ok))
    assertTrue(a.synced)
    assertTrue(a.check(ok.toBuilder().setSequence(9).build()), "a checksum for another state does not apply")
    assertFalse(a.check(ok.toBuilder().setFnv1A(42).build()))
    assertFalse(a.synced)
  }
}
