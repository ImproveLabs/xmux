package dev.improve.xmux.key.encoder

import android.view.KeyEvent

/** What a key press sends to a terminal: literal text, or a named key with modifiers. */
public sealed interface KeyInput {
  public data class Text(public val text: String) : KeyInput

  /** A key in cmux's vocabulary ("enter", "f5", "c" with ctrl, ...). */
  public data class Key(public val name: String, public val mods: Int = 0) : KeyInput
}

/**
 * Android key events -> terminal input. Printable characters become text (the keyboard layout
 * already applied shift), keys that have names become [KeyInput.Key], and printable characters
 * with ctrl/alt/meta become keys with those modifiers so cmux encodes them (ctrl+c, alt+b).
 */
public object KeyEncoder {
  public const val SHIFT: Int = 1
  public const val CTRL: Int = 2
  public const val ALT: Int = 4
  public const val META: Int = 8

  private val named: Map<Int, String> =
    buildMap {
      put(KeyEvent.KEYCODE_ENTER, "enter")
      put(KeyEvent.KEYCODE_NUMPAD_ENTER, "enter")
      put(KeyEvent.KEYCODE_TAB, "tab")
      put(KeyEvent.KEYCODE_ESCAPE, "escape")
      put(KeyEvent.KEYCODE_DEL, "backspace")
      put(KeyEvent.KEYCODE_FORWARD_DEL, "delete")
      put(KeyEvent.KEYCODE_INSERT, "insert")
      put(KeyEvent.KEYCODE_DPAD_UP, "up")
      put(KeyEvent.KEYCODE_DPAD_DOWN, "down")
      put(KeyEvent.KEYCODE_DPAD_LEFT, "left")
      put(KeyEvent.KEYCODE_DPAD_RIGHT, "right")
      put(KeyEvent.KEYCODE_MOVE_HOME, "home")
      put(KeyEvent.KEYCODE_MOVE_END, "end")
      put(KeyEvent.KEYCODE_PAGE_UP, "pageup")
      put(KeyEvent.KEYCODE_PAGE_DOWN, "pagedown")
      val f = intArrayOf(
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
        KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
      )
      for ((i, code) in f.withIndex()) put(code, "f${i + 1}")
    }

  public fun mods(metaState: Int): Int {
    var m = 0
    if (metaState and KeyEvent.META_SHIFT_ON != 0) m = m or SHIFT
    if (metaState and KeyEvent.META_CTRL_ON != 0) m = m or CTRL
    if (metaState and KeyEvent.META_ALT_ON != 0) m = m or ALT
    if (metaState and KeyEvent.META_META_ON != 0) m = m or META
    return m
  }

  /** [unicodeChar] is `KeyEvent.getUnicodeChar(metaState)` (0 when the key prints nothing). */
  public fun encode(keyCode: Int, metaState: Int, unicodeChar: Int): KeyInput? {
    val mods = mods(metaState)
    named[keyCode]?.let { name ->
      if (name == "tab" && mods == SHIFT) return KeyInput.Key("backtab")
      return KeyInput.Key(name, mods)
    }
    if (unicodeChar == 0 || Character.isISOControl(unicodeChar)) return null
    val text = String(Character.toChars(unicodeChar))
    val chordMods = mods and (CTRL or ALT or META)
    if (chordMods != 0) {
      // The layout gave us the shifted character already; ctrl/alt chords name the base key.
      return KeyInput.Key(text.lowercase(), chordMods)
    }
    return KeyInput.Text(text)
  }

  public fun encode(event: KeyEvent): KeyInput? = encode(event.keyCode, event.metaState, event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON)))
}
