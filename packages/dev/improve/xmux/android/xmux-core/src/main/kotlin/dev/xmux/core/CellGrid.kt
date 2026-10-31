package dev.xmux.core

/** Cell attribute bits. Identical to `Run.attrs` on the wire and to the bridge's `attr` module. */
public object Attr {
  public const val BOLD: Int = 1
  public const val ITALIC: Int = 2
  public const val UNDERLINE: Int = 4
  public const val INVERSE: Int = 8
  public const val DIM: Int = 16
  public const val STRIKE: Int = 32
  public const val BLINK: Int = 64
  public const val INVISIBLE: Int = 128
  /** Continuation cell of a wide grapheme; never drawn. */
  public const val WIDE_SPACER: Int = 256
}

/** Cursor shapes, as in `CursorShape` on the wire. */
public enum class CursorShape { Block, Underline, Bar }

/**
 * Fixed-size cell grid for one terminal.
 *
 * Colors are stored as they arrive: `0xAARRGGBB`, where alpha 0 means "the terminal's default"
 * ([defaultFg] / [defaultBg]); the renderer resolves them. A cell holds one code point, or a
 * grapheme of several code points in [clusters]. Writes dedupe against the current cell so only
 * real changes mark a row dirty. All access happens on the main thread.
 */
public class CellGrid(public val cols: Int, public val rows: Int) {
  init {
    require(cols > 0 && rows > 0) { "grid must be at least 1x1, got ${cols}x$rows" }
  }

  public val codepoints: IntArray = IntArray(cols * rows) { ' '.code }
  /** Multi-code-point graphemes by cell index; null where [codepoints] says it all. */
  public val clusters: Array<String?> = arrayOfNulls(cols * rows)
  public val fg: IntArray = IntArray(cols * rows)
  public val bg: IntArray = IntArray(cols * rows)
  public val attrs: IntArray = IntArray(cols * rows)
  public val dirtyRows: BooleanArray = BooleanArray(rows) { true }

  /** What a foreground of 0 means; [DEFAULT_FG] until the bridge says otherwise. */
  public var defaultFg: Int = DEFAULT_FG
    set(value) {
      if (field != value) {
        field = value
        markAllDirty()
      }
    }

  /** What a background of 0 means; 0 itself lets the window body show through. */
  public var defaultBg: Int = DEFAULT_BG
    set(value) {
      if (field != value) {
        field = value
        markAllDirty()
      }
    }

  public var cursorRow: Int = 0
    private set
  public var cursorCol: Int = 0
    private set
  public var cursorVisible: Boolean = true
    private set
  public var cursorShape: CursorShape = CursorShape.Block
    private set

  public fun index(row: Int, col: Int): Int = row * cols + col

  public fun put(row: Int, col: Int, cp: Int, fg: Int = 0, bg: Int = 0, attrs: Int = 0) {
    if (row < 0 || row >= rows || col < 0 || col >= cols) return
    val i = row * cols + col
    if (codepoints[i] == cp && clusters[i] == null && this.fg[i] == fg && this.bg[i] == bg && this.attrs[i] == attrs) return
    codepoints[i] = cp
    clusters[i] = null
    this.fg[i] = fg
    this.bg[i] = bg
    this.attrs[i] = attrs
    dirtyRows[row] = true
  }

  /** Puts a grapheme of several code points into one cell. */
  public fun putCluster(row: Int, col: Int, text: String, fg: Int = 0, bg: Int = 0, attrs: Int = 0) {
    if (row < 0 || row >= rows || col < 0 || col >= cols) return
    val first = if (text.isEmpty()) ' '.code else text.codePointAt(0)
    if (text.codePointCount(0, text.length) <= 1) return put(row, col, first, fg, bg, attrs)
    val i = row * cols + col
    if (clusters[i] == text && this.fg[i] == fg && this.bg[i] == bg && this.attrs[i] == attrs) return
    codepoints[i] = first
    clusters[i] = text
    this.fg[i] = fg
    this.bg[i] = bg
    this.attrs[i] = attrs
    dirtyRows[row] = true
  }

  /** The text of one cell: its grapheme, or "" for the spacer half of a wide cell. */
  public fun cellText(row: Int, col: Int): String {
    val i = row * cols + col
    if (attrs[i] and Attr.WIDE_SPACER != 0) return ""
    return clusters[i] ?: String(Character.toChars(codepoints[i]))
  }

  /** Writes [text] from (row, col), one cell per code point, clipping at the right edge. Returns the next column. */
  public fun write(row: Int, col: Int, text: String, fg: Int = 0, bg: Int = 0, attrs: Int = 0): Int {
    var c = col
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      i += Character.charCount(cp)
      put(row, c, cp, fg, bg, attrs)
      c++
    }
    return c
  }

  public fun fill(row: Int, from: Int, to: Int, cp: Int = ' '.code, fg: Int = 0, bg: Int = 0) {
    for (c in from until to) put(row, c, cp, fg, bg, 0)
  }

  public fun setCursor(row: Int, col: Int) {
    if (row == cursorRow && col == cursorCol) return
    markDirty(cursorRow)
    cursorRow = row
    cursorCol = col
    markDirty(row)
  }

  public fun setCursorVisible(visible: Boolean) {
    if (visible == cursorVisible) return
    cursorVisible = visible
    markDirty(cursorRow)
  }

  public fun setCursorShape(shape: CursorShape) {
    if (shape == cursorShape) return
    cursorShape = shape
    markDirty(cursorRow)
  }

  public fun markDirty(row: Int) {
    if (row in 0 until rows) dirtyRows[row] = true
  }

  public fun markAllDirty() {
    dirtyRows.fill(true)
  }

  public fun anyDirty(): Boolean {
    for (d in dirtyRows) if (d) return true
    return false
  }

  public fun clearDirty() {
    dirtyRows.fill(false)
  }

  /** Text of one row with trailing spaces trimmed; for tests and logs. */
  public fun rowText(row: Int): String {
    val sb = StringBuilder()
    for (c in 0 until cols) sb.append(cellText(row, c))
    return sb.toString().trimEnd()
  }

  public companion object {
    public const val DEFAULT_FG: Int = 0xFFD4D4D4.toInt()
    /** Transparent: the window body color shows through. */
    public const val DEFAULT_BG: Int = 0
  }
}
