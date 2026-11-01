package dev.improve.xmux.client

import dev.improve.xmux.core.Attr
import dev.improve.xmux.core.CellGrid
import dev.improve.xmux.proto.Frame
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every recorded scenario's frame stream (`frames/<name>.xmframes`, written by the bridge's
 * conformance test) applied through [RowApplier] gives exactly the grids cmux reported at the
 * end of the recording (`expected/<name>.grid.json`) — the same check the Rust grid passes.
 */
class ConformanceTest {
  private val scenarios =
    listOf("burst", "cursor", "full_reset", "resize80to120", "scroll", "split2panes", "static80x24", "styles", "tabs", "unicode")

  private fun resource(name: String): ByteArray =
    javaClass.classLoader!!.getResourceAsStream(name)?.readBytes() ?: fail("missing test resource $name")

  /** The grid in the expected-grid JSON shape (see the bridge's `grid.rs`). */
  private fun expectedOf(g: CellGrid, defaultFg: Int, defaultBg: Int): JSONObject {
    val lines = (0 until g.rows).map { r -> (0 until g.cols).joinToString("") { c -> g.cellText(r, c) } }
    val styles = ArrayList<List<Long>>()
    for (r in 0 until g.rows) {
      var c = 0
      while (c < g.cols) {
        val i = g.index(r, c)
        val key = Triple(g.fg[i], g.bg[i], g.attrs[i] and Attr.WIDE_SPACER.inv())
        var e = c + 1
        while (e < g.cols) {
          val j = g.index(r, e)
          if (Triple(g.fg[j], g.bg[j], g.attrs[j] and Attr.WIDE_SPACER.inv()) != key) break
          e++
        }
        if (key != Triple(0, 0, 0)) {
          styles.add(listOf(r.toLong(), c.toLong(), e.toLong(), key.first.toLong() and 0xFFFFFFFFL, key.second.toLong() and 0xFFFFFFFFL, key.third.toLong()))
        }
        c = e
      }
    }
    return JSONObject().apply {
      put("cols", g.cols)
      put("rows", g.rows)
      put("default_fg", defaultFg.toLong() and 0xFFFFFFFFL)
      put("default_bg", defaultBg.toLong() and 0xFFFFFFFFL)
      put("cursor", JSONObject().put("row", g.cursorRow).put("col", g.cursorCol).put("visible", g.cursorVisible))
      put("lines", lines)
      put("styles", styles)
    }
  }

  @Test
  fun everyScenarioReplaysToTheGridsCmuxReported() {
    for (name in scenarios) {
      val expected = JSONObject(String(resource("$name.grid.json")))
      val terminals = expected.getJSONArray("terminals")
      val appliers = HashMap<Int, RowApplier>()
      val defaults = HashMap<Int, Pair<Int, Int>>()
      var checksums = 0
      val bytes = resource("$name.xmframes")
      var offset = 0
      while (offset < bytes.size) {
        val decoded = FrameCodec.decode(bytes, offset)
        offset += decoded.consumed
        val f = decoded.frame
        when (f.payloadCase) {
          Frame.PayloadCase.ROWS -> {
            val a = appliers.getOrPut(f.rows.terminal) { RowApplier() }
            val result = a.apply(f.rows)
            assertTrue(result != RowApplier.Result.NeedsSnapshot, "$name: terminal ${f.rows.terminal} lost sync")
            defaults[f.rows.terminal] = f.rows.defaultFg to f.rows.defaultBg
          }
          Frame.PayloadCase.CURSOR -> appliers[f.cursor.terminal]?.apply(f.cursor)
          Frame.PayloadCase.CHECKSUM -> {
            val a = appliers.getValue(f.checksum.terminal)
            assertEquals(f.checksum.fnv1A, a.checksum(), "$name: checksum of terminal ${f.checksum.terminal}")
            checksums++
          }
          else -> {}
        }
      }
      assertEquals(terminals.length(), checksums, "$name: one checksum per terminal")
      for (i in 0 until terminals.length()) {
        val want = terminals.getJSONObject(i).getJSONObject("grid")
        val a = appliers[i + 1] ?: fail("$name: no rows for terminal ${i + 1}")
        val (fg, bg) = defaults.getValue(i + 1)
        val got = expectedOf(a.grid!!, fg, bg)
        for (key in listOf("cols", "rows", "default_fg", "default_bg")) {
          assertEquals(want.getLong(key), got.getLong(key), "$name: $key")
        }
        val wantLines = want.getJSONArray("lines")
        val gotLines = got.getJSONArray("lines")
        for (r in 0 until wantLines.length()) {
          assertEquals(wantLines.getString(r), gotLines.getString(r), "$name: row $r of terminal ${i + 1}")
        }
        assertEquals(want.getJSONArray("styles").toString(), got.getJSONArray("styles").toString(), "$name: styles")
        assertEquals(want.getJSONObject("cursor").toString(), got.getJSONObject("cursor").toString(), "$name: cursor")
      }
    }
  }
}
