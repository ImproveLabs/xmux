package dev.xmux.client

import dev.xmux.core.Attr
import dev.xmux.core.CellGrid
import dev.xmux.core.CursorShape
import dev.xmux.proto.Checksum
import dev.xmux.proto.Cursor
import dev.xmux.proto.Row
import dev.xmux.proto.Rows
import dev.xmux.proto.Run

/**
 * Applies one terminal's `Rows`, `Cursor` and `Checksum` frames to a [CellGrid], with the same
 * rules as the bridge's Rust `Grid` (and checked against the same conformance fixtures):
 *
 * - `full_reset` replaces everything and starts a generation, at whatever size it says;
 * - otherwise a frame must be the next sequence of the current generation, or it is dropped and
 *   the caller asks the bridge for a snapshot ([Result.NeedsSnapshot]);
 * - runs map to cells by `width_hint`: 0 one narrow cell per code point, 2 one wide cell per code
 *   point (lead plus spacer), 1 and 3 the whole text as one narrow or wide cell.
 */
public class RowApplier {
  public enum class Result { Applied, Resized, NeedsSnapshot }

  public var grid: CellGrid? = null
    private set
  public var generation: Int = 0
    private set
  public var sequence: Int = 0
    private set
  public var synced: Boolean = false
    private set

  private var lines: Array<List<Run>> = emptyArray()

  public fun apply(rows: Rows): Result {
    var resized = false
    if (rows.fullReset) {
      val current = grid
      if (current == null || current.cols != rows.cols || current.rows != rows.rows) {
        grid = CellGrid(rows.cols.coerceAtLeast(1), rows.rows.coerceAtLeast(1))
        resized = true
      } else {
        for (r in 0 until current.rows) current.fill(r, 0, current.cols)
      }
      lines = Array(rows.rows) { emptyList() }
      generation = rows.generation
      synced = true
    } else if (!synced || rows.generation != generation || rows.sequence != sequence + 1) {
      synced = false
      return Result.NeedsSnapshot
    }
    sequence = rows.sequence
    val g = grid!!
    g.defaultFg = if (rows.defaultFg != 0) rows.defaultFg else CellGrid.DEFAULT_FG
    g.defaultBg = rows.defaultBg
    for (row in rows.dirtyList) {
      if (row.index !in 0 until g.rows) continue
      writeRow(g, row)
      lines[row.index] = row.runsList
    }
    return if (resized) Result.Resized else Result.Applied
  }

  public fun apply(cursor: Cursor) {
    val g = grid ?: return
    g.setCursor(cursor.row, cursor.col)
    g.setCursorVisible(cursor.visible)
    g.setCursorShape(
      when (cursor.shapeValue) {
        1 -> CursorShape.Underline
        2 -> CursorShape.Bar
        else -> CursorShape.Block
      },
    )
  }

  /**
   * Compares the bridge's checksum with this grid's. Returns false on a mismatch (the caller
   * resyncs); true when it matches or does not apply to the current state.
   */
  public fun check(checksum: Checksum): Boolean {
    if (!synced || checksum.generation != generation || checksum.sequence != sequence) return true
    if (checksum.fnv1A == checksum()) return true
    synced = false
    return false
  }

  public fun checksum(): Long = Fnv1a.rows(lines.withIndex().map { (i, runs) -> i to runs })

  /** Marks the grid as needing a snapshot (after a reconnect, say). */
  public fun invalidate() {
    synced = false
  }

  private fun writeRow(g: CellGrid, row: Row) {
    val r = row.index
    var col = 0
    runs@ for (run in row.runsList) {
      when (run.widthHint) {
        1, 3 -> {
          val wide = run.widthHint == 3
          if (col + (if (wide) 2 else 1) > g.cols) break@runs
          g.putCluster(r, col, run.text, run.fg, run.bg, run.attrs)
          col++
          if (wide) {
            g.put(r, col, ' '.code, run.fg, run.bg, run.attrs or Attr.WIDE_SPACER)
            col++
          }
        }
        else -> {
          val wide = run.widthHint == 2
          var i = 0
          val text = run.text
          while (i < text.length) {
            if (col + (if (wide) 2 else 1) > g.cols) break@runs
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            g.put(r, col, cp, run.fg, run.bg, run.attrs)
            col++
            if (wide) {
              g.put(r, col, ' '.code, run.fg, run.bg, run.attrs or Attr.WIDE_SPACER)
              col++
            }
          }
        }
      }
    }
    while (col < g.cols) {
      g.put(r, col, ' '.code, 0, 0, 0)
      col++
    }
  }
}
