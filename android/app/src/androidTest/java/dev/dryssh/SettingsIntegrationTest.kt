package dev.dryssh

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.connectbot.terminal.VTermKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsIntegrationTest {
	@get:Rule val compose = createEmptyComposeRule()

	@Test fun fontPaletteAndMappingsPersistAndHandlePhysicalKeyEvents() {
		SessionFixture().use { fixture ->
			val repository = fixture.service.repository
			val original = JSONObject(repository.config.value).getJSONObject("settings")
			var font: File? = null
			try {
				fixture.connect()
				compose.onNodeWithText("Settings").performClick()
				fun fill(label: String, value: String) {
					val field = compose.onNode(hasText(label) and hasSetTextAction())
					field.performScrollTo().performTextClearance()
					field.performTextInput(value)
				}
				fill("Foreground", "C0FFEE")
				fill("Background", "112233")
				fill("ANSI 1", "BBAACC")
				compose.onNodeWithText("Save settings").assertIsDisplayed()
				compose.onNodeWithText("Keys").assertIsDisplayed()
				val instrumentation = InstrumentationRegistry.getInstrumentation()
				val monitor = instrumentation.addMonitor(
					IntentFilter(Intent.ACTION_GET_CONTENT).apply {
						addCategory(Intent.CATEGORY_OPENABLE)
						addDataType("*/*")
					},
					ActivityResult(
						Activity.RESULT_OK,
						Intent().setData(
							Uri.parse("content://dev.dryssh.test.documents/unifont.otf")
						).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					),
					true
				)
				try {
					compose.onNodeWithText("Import font").performScrollTo().performClick()
					try {
						compose.waitUntil(10_000) {
							compose.onAllNodesWithText("Imported font").fetchSemanticsNodes().isNotEmpty()
						}
					} catch (
						failure: Throwable
					) {
						throw AssertionError(compose.onAllNodes(isRoot()).onLast().printToString(), failure)
					}
				} finally {
					instrumentation.removeMonitor(monitor)
				}
				compose.onNodeWithText("Keys").performClick()
				compose.onNodeWithText("Add mapping").performScrollTo().performClick()
				fill("Input key", "f6")
				compose.onNodeWithText("text").performClick()
				fill("Paste text", "printf '%s%s\\n' 'https://example.' 'org/mapped-key'")
				compose.onNodeWithText("Apply action").performClick()
				compose.onNodeWithText("Save settings").performClick()
				fixture.awaitCondition {
					JSONObject(repository.config.value).getJSONObject("settings").getLong("foreground") ==
						0xffc0ffeeL
				}
				val settings = JSONObject(
					AppRepository(instrumentation.targetContext).config.value
				).getJSONObject("settings")
				assertEquals(0xff112233L, settings.getLong("background"))
				assertEquals(0xffbbaaccL, settings.getJSONArray("ansi").getLong(1))
				font = File(repository.fonts, settings.getString("font_ref"))
				val paint = Paint().apply {
					typeface = Typeface.createFromFile(font!!)
					textSize = 32f
				}
				assertTrue(paint.hasGlyph("中"))
				assertTrue(paint.hasGlyph("文"))
				assertTrue(paint.hasGlyph("A"))
				assertEquals(paint.measureText("AA"), paint.measureText("中"), 0.5f)
				fixture.activity.recreate()
				compose.waitForIdle()
				lateinit var input: View
				fixture.activity.onActivity { activity ->
					fun find(view: View): View? {
						if (view.javaClass.simpleName == "ImeInputView") return view
						if (view is ViewGroup) {
							for (i in 0 until view.childCount) {
								find(view.getChildAt(i))?.let {
									return it
								}
							}
						}
						return null
					}
					input = find(activity.window.decorView)!!
					input.requestFocus()
					input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F6))
					input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F6))
				}
				instrumentation.runOnMainSync { fixture.service.terminal.dispatchKey(0, VTermKey.ENTER) }
				fixture.awaitCondition {
					fixture.service.terminal.getUrls().any {
						it.url ==
							"https://example.org/mapped-key"
					}
				}
				val modifiers = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
				instrumentation.runOnMainSync {
					input.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, 0, modifiers))
				}
				compose.onNodeWithText("Local editor").assertIsDisplayed()
				compose.onNodeWithText("Keep draft").performClick()
			} finally {
				repository.update(JSONObject(repository.config.value).put("settings", original))
				font?.delete()
			}
		}
	}
}
