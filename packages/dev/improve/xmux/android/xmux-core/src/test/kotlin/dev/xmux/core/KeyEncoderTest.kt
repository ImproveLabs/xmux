package dev.xmux.core

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyEncoderTest {
  @Test
  fun printableCharactersAreText() {
    assertEquals(KeyInput.Text("a"), KeyEncoder.encode(KeyEvent.KEYCODE_A, 0, 'a'.code))
    assertEquals(KeyInput.Text("A"), KeyEncoder.encode(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, 'A'.code))
    assertEquals(KeyInput.Text("é"), KeyEncoder.encode(KeyEvent.KEYCODE_E, 0, 'é'.code))
  }

  @Test
  fun namedKeysCarryModifiers() {
    assertEquals(KeyInput.Key("enter"), KeyEncoder.encode(KeyEvent.KEYCODE_ENTER, 0, '\n'.code))
    assertEquals(KeyInput.Key("backspace"), KeyEncoder.encode(KeyEvent.KEYCODE_DEL, 0, 0))
    assertEquals(KeyInput.Key("left", KeyEncoder.ALT), KeyEncoder.encode(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_ALT_ON, 0))
    assertEquals(KeyInput.Key("f5"), KeyEncoder.encode(KeyEvent.KEYCODE_F5, 0, 0))
    assertEquals(KeyInput.Key("backtab"), KeyEncoder.encode(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON, 0))
  }

  @Test
  fun ctrlAndAltChordsNameTheBaseKey() {
    assertEquals(KeyInput.Key("c", KeyEncoder.CTRL), KeyEncoder.encode(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON, 'c'.code))
    assertEquals(KeyInput.Key("b", KeyEncoder.ALT), KeyEncoder.encode(KeyEvent.KEYCODE_B, KeyEvent.META_ALT_ON, 'b'.code))
  }

  @Test
  fun keysThatPrintNothingAreIgnored() {
    assertNull(KeyEncoder.encode(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON, 0))
  }
}
