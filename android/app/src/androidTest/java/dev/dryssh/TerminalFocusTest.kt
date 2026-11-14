package dev.dryssh

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalFocusTest {
	@get:Rule val compose = createEmptyComposeRule()

	private fun findInput(view: View): View? {
		if (view.javaClass.simpleName == "ImeInputView") return view
		if (view is ViewGroup) {
			for (index in 0 until view.childCount) {
				findInput(view.getChildAt(index))?.let { return it }
			}
		}
		return null
	}

	@Test fun hardwareInputOwnsFocusWithoutImeAndReturnsAfterModalWindows() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val output = ByteArrayOutputStream()
		val terminal = TerminalEmulatorFactory.create(Looper.getMainLooper(), onKeyboardInput = {
			synchronized(output) { output.write(it) }
		})
		instrumentation.setInTouchMode(false)
		try {
			ActivityScenario.launch(MainActivity::class.java).use { scenario ->
				scenario.onActivity { activity ->
					activity.setContent {
						MaterialTheme {
							var menu by remember { mutableStateOf(false) }
							var dialog by remember { mutableStateOf(false) }
							Column {
								TextButton(onClick = { menu = true }) { Text("Menu") }
								DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
									DropdownMenuItem(text = { Text("Dismiss menu") }, onClick = { menu = false })
								}
								TextButton(onClick = { dialog = true }) { Text("Editor") }
								Terminal(
									terminal,
									Modifier.weight(1f).testTag("terminal"),
									keyboardEnabled = true,
									showSoftKeyboard = false
								)
							}
							if (dialog) {
								ScreenAlertDialog(
									onDismissRequest = { dialog = false },
									title = { Text("Local editor") },
									text = {
										var text by remember { mutableStateOf("") }
										TextField(text, { text = it }, Modifier.testTag("editor"))
									},
									confirmButton = { TextButton(onClick = { dialog = false }) { Text("Close") } }
								)
							}
						}
					}
				}
				fun assertTerminalInput() {
					compose.waitUntil(5_000) {
						var focused = false
						scenario.onActivity {
							val input = findInput(it.window.decorView)
							focused = input?.hasFocus() == true && input.hasWindowFocus() &&
								!it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
						}
						focused
					}
					scenario.onActivity {
						assertFalse(it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
						synchronized(output) { output.reset() }
						for (key in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP)) {
							for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
								it.dispatchKeyEvent(KeyEvent(action, key))
							}
						}
						assertTrue(findInput(it.window.decorView)!!.hasFocus())
					}
					compose.waitUntil(5_000) {
						synchronized(output) { output.toString("UTF-8") == "a\t\u001b[A" }
					}
				}
				assertTerminalInput()
				compose.onNodeWithText("Menu").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
				scenario.onActivity { assertFalse(findInput(it.window.decorView)!!.hasFocus()) }
				compose.onNodeWithTag("terminal").performTouchInput { click() }
				assertTerminalInput()
				scenario.moveToState(Lifecycle.State.CREATED)
				scenario.moveToState(Lifecycle.State.RESUMED)
				assertTerminalInput()
				compose.onNodeWithText("Menu").performClick()
				compose.onNodeWithText("Dismiss menu").performClick()
				assertTerminalInput()
				compose.onNodeWithText("Editor").performClick()
				compose.onNodeWithTag("editor").performClick().performTextInput("dialog input")
				assertEquals("a\t\u001b[A", synchronized(output) { output.toString("UTF-8") })
				compose.onNodeWithText("Close").performClick()
				assertTerminalInput()
			}
		} finally {
			instrumentation.setInTouchMode(true)
			terminal.close()
		}
	}
}
