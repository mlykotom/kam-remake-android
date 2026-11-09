package org.kamremake.android

import android.view.KeyEvent

/** Android key codes -> Windows virtual key codes used by the game */
internal object KeyMap {
    fun toVK(keyCode: Int): Int {
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return 'A'.code + (keyCode - KeyEvent.KEYCODE_A)
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return '0'.code + (keyCode - KeyEvent.KEYCODE_0)
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return 112 + (keyCode - KeyEvent.KEYCODE_F1)
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) return 96 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> 8
            KeyEvent.KEYCODE_TAB -> 9
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 16
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 17
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 18
            KeyEvent.KEYCODE_BREAK -> 19
            KeyEvent.KEYCODE_CAPS_LOCK -> 20
            KeyEvent.KEYCODE_ESCAPE -> 27
            KeyEvent.KEYCODE_SPACE -> 32
            KeyEvent.KEYCODE_PAGE_UP -> 33
            KeyEvent.KEYCODE_PAGE_DOWN -> 34
            KeyEvent.KEYCODE_MOVE_END -> 35
            KeyEvent.KEYCODE_MOVE_HOME -> 36
            KeyEvent.KEYCODE_DPAD_LEFT -> 37
            KeyEvent.KEYCODE_DPAD_UP -> 38
            KeyEvent.KEYCODE_DPAD_RIGHT -> 39
            KeyEvent.KEYCODE_DPAD_DOWN -> 40
            KeyEvent.KEYCODE_INSERT -> 45
            KeyEvent.KEYCODE_FORWARD_DEL -> 46
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 106
            KeyEvent.KEYCODE_NUMPAD_ADD -> 107
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 109
            KeyEvent.KEYCODE_NUMPAD_DOT -> 110
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 111
            KeyEvent.KEYCODE_SEMICOLON -> 186
            KeyEvent.KEYCODE_EQUALS -> 187
            KeyEvent.KEYCODE_COMMA -> 188
            KeyEvent.KEYCODE_MINUS -> 189
            KeyEvent.KEYCODE_PERIOD -> 190
            KeyEvent.KEYCODE_SLASH -> 191
            KeyEvent.KEYCODE_GRAVE -> 192
            KeyEvent.KEYCODE_LEFT_BRACKET -> 219
            KeyEvent.KEYCODE_BACKSLASH -> 220
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 221
            KeyEvent.KEYCODE_APOSTROPHE -> 222
            else -> 0
        }
    }

    fun shiftFlags(e: KeyEvent): Int = shiftFlags(e.isShiftPressed, e.isCtrlPressed, e.isAltPressed)

    fun shiftFlags(shift: Boolean, ctrl: Boolean, alt: Boolean): Int {
        var s = 0
        if (shift) s = s or 1
        if (ctrl) s = s or 2
        if (alt) s = s or 4
        return s
    }
}
