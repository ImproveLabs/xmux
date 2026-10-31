package dev.xmux.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * One cmux screen: a [TerminalGridView] per pane, arranged by the screen's [LayoutTree], with
 * dividers, a tab strip over panes that have several tabs, headers for collapsed stack members
 * and an outline around the focused pane.
 */
public class ScreenView(context: Context, public val font: TerminalFont) : ViewGroup(context) {
  /** A pane as the view shows it: the active tab's grid plus the tab titles. */
  public class Pane(
    public val id: Int,
    public val grid: CellGrid,
    public val tabs: List<String> = emptyList(),
    public val activeTab: Int = 0,
    public val title: String = tabs.getOrNull(activeTab) ?: "",
  )

  public data class Hit(val pane: Int, val row: Int, val col: Int)

  /** Input the screen receives; the embedder routes it to cmux. */
  public interface Listener {
    /** A press or release over a pane ([MotionEvent.ACTION_DOWN] / [MotionEvent.ACTION_UP]). */
    public fun onPointer(hit: Hit, action: Int) {}

    /** A tap on a tab in a pane's tab strip. */
    public fun onTab(pane: Int, index: Int) {}

    /** Wheel or touchpad scroll over a pane: rows, negative scrolls up. */
    public fun onScroll(hit: Hit, rows: Int) {}

    /** Text from the soft keyboard. */
    public fun onText(text: String) {}

    /** A key event from the soft keyboard or a hardware keyboard while this view has focus. */
    public fun onKey(event: KeyEvent): Boolean = false
  }

  public var listener: Listener? = null

  public var bodyColor: Int = 0xFF1E1E1E.toInt()
  public var dividerColor: Int = 0xFF3A3A3A.toInt()
  public var focusColor: Int = 0xFF4A90D9.toInt()
  public var tabColor: Int = 0xFF262626.toInt()
  public var activeTabColor: Int = 0xFF333333.toInt()
  public var labelColor: Int = 0xFFB0B0B0.toInt()

  private var tree: LayoutTree? = null
  private var panes: Map<Int, Pane> = emptyMap()
  private val views = HashMap<Int, TerminalGridView>()
  private var rects: Map<Int, PxRect> = emptyMap()
  private val fill = Paint()
  private val outline = Paint().apply {
    style = Paint.Style.STROKE
    strokeWidth = 2f
  }
  private val label = Paint(font.paint).apply { textSize = font.paint.textSize * 0.8f }

  public var focusedPane: Int = 0
    set(value) {
      if (field != value) {
        field = value
        invalidate()
      }
    }

  init {
    setWillNotDraw(false)
    isFocusable = true
    isFocusableInTouchMode = true
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      tabAt(event.x, event.y)?.let { (pane, index) ->
        requestFocus()
        listener?.onTab(pane, index)
        return true
      }
    }
    val hit = hit(event.x, event.y) ?: return false
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        requestFocus()
        listener?.onPointer(hit, MotionEvent.ACTION_DOWN)
      }
      MotionEvent.ACTION_UP -> listener?.onPointer(hit, MotionEvent.ACTION_UP)
    }
    return true
  }

  override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
      val hit = hit(event.x, event.y) ?: return false
      val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
      if (v != 0f) listener?.onScroll(hit, if (v > 0) -3 else 3)
      return true
    }
    return super.onGenericMotionEvent(event)
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = listener?.onKey(event) == true || super.onKeyDown(keyCode, event)

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    // Like other Android terminals: no suggestions or autocorrect, every character goes through.
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
    return object : BaseInputConnection(this, false) {
      override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (text.isNotEmpty()) listener?.onText(text.toString())
        return true
      }

      override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { listener?.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) }
        return true
      }

      override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) listener?.onKey(event)
        return true
      }
    }
  }

  /** Replaces what the screen shows; views of panes that stay are kept. */
  public fun update(tree: LayoutTree, panes: List<Pane>, focusedPane: Int) {
    this.tree = tree
    this.panes = panes.associateBy { it.id }
    for (id in views.keys.toList() - this.panes.keys) removeView(views.remove(id))
    for (p in panes) {
      val view = views[p.id]
      if (view == null) {
        val v = TerminalGridView(context, p.grid, font).apply { bodyColor = this@ScreenView.bodyColor }
        views[p.id] = v
        addView(v)
      } else view.grid = p.grid
    }
    this.focusedPane = focusedPane
    requestLayout()
    invalidate()
  }

  public fun gridView(pane: Int): TerminalGridView? = views[pane]

  public fun refreshIfDirty() {
    for (v in views.values) v.refreshIfDirty()
  }

  private fun stripHeight(p: Pane?): Int = if (p != null && p.tabs.size > 1) TAB_STRIP_PX else 0

  private fun paneSize(id: Int): PxSize {
    val p = panes[id] ?: return PxSize(0, 0)
    return paneSize(font, p, views[id]?.paddingPx ?: DEFAULT_PADDING_PX)
  }

  /** The tab under a point in a pane's tab strip, as (pane, tab index). */
  public fun tabAt(x: Float, y: Float): Pair<Int, Int>? {
    for ((id, rect) in rects) {
      val pane = panes[id] ?: continue
      if (pane.tabs.size < 2 || !rect.contains(x.toInt(), y.toInt()) || y >= rect.top + TAB_STRIP_PX) continue
      val index = ((x - rect.left) / (rect.width.toFloat() / pane.tabs.size)).toInt().coerceIn(0, pane.tabs.size - 1)
      return id to index
    }
    return null
  }

  /** The screen's size with every pane at its grid's natural size. */
  public fun naturalSize(): PxSize = tree?.let { ScreenLayoutEngine.measure(it, ::paneSize) } ?: PxSize(0, 0)

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    for (v in views.values) v.measure(MeasureSpec.makeMeasureSpec(v.contentWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(v.contentHeight, MeasureSpec.EXACTLY))
    val size = naturalSize()
    setMeasuredDimension(resolveSize(size.width, widthMeasureSpec), resolveSize(size.height, heightMeasureSpec))
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    val tree = tree ?: return
    rects = ScreenLayoutEngine.place(tree, ::paneSize)
    for ((id, v) in views) {
      val rect = rects[id]
      val strip = stripHeight(panes[id])
      if (rect == null || rect.height < v.contentHeight + strip) {
        // Not in the layout, or a collapsed stack member: only its header shows.
        v.visibility = View.GONE
        continue
      }
      v.visibility = View.VISIBLE
      v.layout(rect.left, rect.top + strip, rect.left + v.contentWidth, rect.top + strip + v.contentHeight)
    }
  }

  override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)
    val tree = tree ?: return
    fill.color = dividerColor
    for (d in ScreenLayoutEngine.dividers(tree, ::paneSize)) {
      canvas.drawRect(d.left.toFloat(), d.top.toFloat(), d.right.toFloat(), d.bottom.toFloat(), fill)
    }
    for ((id, rect) in rects) {
      val pane = panes[id] ?: continue
      val view = views[id]
      if (view != null && view.visibility == View.GONE) drawHeader(canvas, rect, pane.title)
      else if (pane.tabs.size > 1) drawTabs(canvas, rect, pane)
    }
    if (panes.size > 1) {
      rects[focusedPane]?.let { r ->
        outline.color = focusColor
        canvas.drawRect(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f, outline)
      }
    }
  }

  private fun drawHeader(canvas: Canvas, rect: PxRect, title: String) {
    fill.color = tabColor
    canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), fill)
    label.color = labelColor
    canvas.drawText(title, rect.left + 10f, rect.top + rect.height * 0.7f, label)
  }

  private fun drawTabs(canvas: Canvas, rect: PxRect, pane: Pane) {
    val w = rect.width.toFloat() / pane.tabs.size
    for ((i, title) in pane.tabs.withIndex()) {
      fill.color = if (i == pane.activeTab) activeTabColor else tabColor
      val x = rect.left + i * w
      canvas.drawRect(x, rect.top.toFloat(), x + w - 1f, (rect.top + TAB_STRIP_PX).toFloat(), fill)
      label.color = labelColor
      canvas.drawText(title.ifEmpty { "tab ${i + 1}" }, x + 10f, rect.top + TAB_STRIP_PX * 0.7f, label)
    }
  }

  /** The pane and cell under a point in this view's coordinates. */
  public fun hit(x: Float, y: Float): Hit? {
    for ((id, v) in views) {
      if (v.visibility != View.VISIBLE) continue
      if (x < v.left || x >= v.right || y < v.top || y >= v.bottom) continue
      val (row, col) = v.cellAt(x - v.left, y - v.top) ?: return Hit(id, -1, -1)
      return Hit(id, row, col)
    }
    for ((id, rect) in rects) if (rect.contains(x.toInt(), y.toInt())) return Hit(id, -1, -1)
    return null
  }

  public companion object {
    public const val TAB_STRIP_PX: Int = 30
    public const val DEFAULT_PADDING_PX: Float = 10f

    /** A pane's size: its grid at [font]'s cell size, plus a tab strip when it has several tabs. */
    public fun paneSize(font: TerminalFont, pane: Pane, paddingPx: Float = DEFAULT_PADDING_PX): PxSize =
      PxSize(
        (paddingPx * 2 + pane.grid.cols * font.cellWidth).toInt(),
        (paddingPx * 2 + pane.grid.rows * font.cellHeight).toInt() + if (pane.tabs.size > 1) TAB_STRIP_PX else 0,
      )

    /** The screen's natural size, computable before any view exists. */
    public fun measure(font: TerminalFont, tree: LayoutTree, panes: List<Pane>): PxSize {
      val byId = panes.associateBy { it.id }
      return ScreenLayoutEngine.measure(tree) { id -> byId[id]?.let { paneSize(font, it) } ?: PxSize(0, 0) }
    }
  }
}
