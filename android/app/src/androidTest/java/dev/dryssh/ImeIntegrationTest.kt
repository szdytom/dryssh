package dev.dryssh

import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeIntegrationTest {
	private fun findIme(view: View): View? {
		if (view.javaClass.simpleName == "ImeInputView") return view
		if (view is ViewGroup) {
			for (index in 0 until view.childCount) {
				findIme(view.getChildAt(index))?.let { return it }
			}
		}
		return null
	}

	@Test(timeout = 60_000)
	fun compositionStaysLocalAndVoiceEditsCommitOnceBeforeShortcut() {
		SessionFixture().use { fixture ->
			fixture.connect()
			val terminal = fixture.service.terminal
			fixture.instrumentation.runOnMainSync {
				terminal.pasteText(
					"export LC_ALL=C.UTF-8; printf '%s%s\\n' 'https://example.' 'org/ime-ready'"
				)
				terminal.dispatchKey(0, VTermKey.ENTER)
			}
			fixture.awaitCondition {
				terminal.getUrls().any {
					it.url ==
						"https://example.org/ime-ready"
				}
			}
			lateinit var connection: InputConnection
			lateinit var ime: View
			val info = EditorInfo()
			fixture.activity.onActivity {
				ime = findIme(it.window.decorView)!!
				ime.requestFocus()
				connection = ime.onCreateInputConnection(info)!!
			}
			assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
			fixture.instrumentation.runOnMainSync {
				assertNotNull(connection.getExtractedText(null, 0))
				terminal.pasteText("value='")
				connection.setComposingText("https://composition.example/", 1)
			}
			fixture.instrumentation.waitForIdleSync()
			Thread.sleep(150)
			assertTrue(terminal.getUrls().none { it.url.startsWith("https://composition.example") })
			fixture.instrumentation.runOnMainSync {
				connection.setComposingText("你好", 1)
				connection.setComposingText("您好", 1)
				connection.deleteSurroundingText(0, 0)
				connection.setComposingText("您好", 1)
				connection.commitText("您好", 1)
				connection.finishComposingText()
				connection.commitText("世界", 1)
				connection.deleteSurroundingText(1, 0)
				connection.commitText("界", 1)
				connection.setComposingText("终", 1)
				connection.finishComposingText()
			}
			Thread.sleep(450)
			fixture.instrumentation.runOnMainSync {
				assertEquals("您好世界终", connection.getExtractedText(null, 0)!!.text.toString())
				if (Build.VERSION.SDK_INT >= 34) connection.replaceText(0, 2, "诸位", 1, null)
				ime.dispatchKeyEvent(
					KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, 0, KeyEvent.META_CTRL_ON)
				)
				ime.dispatchKeyEvent(
					KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_E, 0, KeyEvent.META_CTRL_ON)
				)
				terminal.pasteText(
					"'; printf 'https://example.org/ime-%s\\n' \"\$(printf %s \"\$value\" | od -An -tx1 | tr -d ' \\n')\""
				)
				terminal.dispatchKey(0, VTermKey.ENTER)
			}
			val expected = if (Build.VERSION.SDK_INT >= 34) "诸位世界终" else "您好世界终"
			val hex = expected.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
			fixture.awaitCondition {
				terminal.getUrls().any {
					it.url ==
						"https://example.org/ime-$hex"
				}
			}
			assertTrue(terminal.getUrls().none { it.url.startsWith("https://composition.example") })
		}
	}
}
