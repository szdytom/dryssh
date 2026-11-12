package dev.dryssh

import android.view.KeyEvent
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.VTermKey
import org.json.JSONArray
import org.json.JSONObject

object TerminalActions {
	private val keys =
		mapOf(
			"escape" to VTermKey.ESCAPE, "tab" to VTermKey.TAB, "enter" to VTermKey.ENTER,
			"backspace" to VTermKey.BACKSPACE, "delete" to VTermKey.DEL, "insert" to VTermKey.INS,
			"home" to VTermKey.HOME, "end" to VTermKey.END, "up" to VTermKey.UP, "down" to VTermKey.DOWN,
			"left" to VTermKey.LEFT, "right" to VTermKey.RIGHT, "page_up" to VTermKey.PAGEUP,
			"page_down" to VTermKey.PAGEDOWN
		) +
			(1..12).associate { "f$it" to VTermKey.FUNCTION_0 + it }
	private val androidKeys = mapOf(
		"escape" to KeyEvent.KEYCODE_ESCAPE, "tab" to KeyEvent.KEYCODE_TAB,
		"enter" to KeyEvent.KEYCODE_ENTER, "backspace" to KeyEvent.KEYCODE_DEL,
		"delete" to KeyEvent.KEYCODE_FORWARD_DEL,
		"insert" to KeyEvent.KEYCODE_INSERT, "home" to KeyEvent.KEYCODE_MOVE_HOME,
		"end" to KeyEvent.KEYCODE_MOVE_END,
		"up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
		"left" to KeyEvent.KEYCODE_DPAD_LEFT,
		"right" to KeyEvent.KEYCODE_DPAD_RIGHT, "page_up" to KeyEvent.KEYCODE_PAGE_UP,
		"page_down" to KeyEvent.KEYCODE_PAGE_DOWN,
		"space" to KeyEvent.KEYCODE_SPACE
	)
	fun matches(event: KeyEvent, bindings: JSONArray): JSONObject? {
		if (event.isMetaPressed || event.isFunctionPressed) return null
		for (i in 0 until bindings.length()) {
			val binding = bindings.getJSONObject(i)
			val key = binding.getString("key")
			val code = androidKeys[key] ?: KeyEvent.keyCodeFromString("KEYCODE_${key.uppercase()}")
			val modifiers = binding.getJSONObject("modifiers")
			if (event.keyCode == code && event.isCtrlPressed == modifiers.getBoolean("ctrl") &&
				event.isAltPressed == modifiers.getBoolean("alt") &&
				event.isShiftPressed == modifiers.getBoolean("shift")
			) {
				return binding.getJSONObject("action")
			}
		}
		return null
	}
	fun perform(action: JSONObject, terminal: TerminalEmulator, editor: () -> Unit) {
		when (action.getString("kind")) {
			"editor" -> editor()
			"control" -> terminal.dispatchCharacter(4, action.getString("character")[0].code)
			"text" -> terminal.pasteText(action.getString("text"))
			"key" -> {
				val key = action.getString("key")
				keys[key]?.let { terminal.dispatchKey(0, it) }
					?: terminal.dispatchCharacter(0, if (key == "space") 32 else key[0].code)
			}
		}
	}
}
