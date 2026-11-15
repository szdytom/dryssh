package dev.dryssh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test

class SpeechInputIntegrationTest {
	@get:Rule val compose = createAndroidComposeRule<MainActivity>()
	private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
	private val context get() = instrumentation.targetContext

	private fun editor(): EditText {
		fun find(view: View): EditText? {
			if (view is EditText) return view
			if (view is ViewGroup) {
				for (i in 0 until view.childCount) {
					find(view.getChildAt(i))?.let {
						return it
					}
				}
			}
			return null
		}
		return WindowInspector.getGlobalWindowViews().firstNotNullOf { find(it) }
	}

	private fun withService(test: (SshService) -> Unit) {
		val ready = CountDownLatch(1)
		lateinit var service: SshService
		val binding = object : ServiceConnection {
			override fun onServiceConnected(name: ComponentName, binder: IBinder) {
				service = (binder as SshService.LocalBinder).service
				ready.countDown()
			}
			override fun onServiceDisconnected(name: ComponentName) {}
		}
		context.bindService(Intent(context, SshService::class.java), binding, Context.BIND_AUTO_CREATE)
		assertTrue(ready.await(5, TimeUnit.SECONDS))
		try {
			test(service)
		} finally {
			context.unbindService(binding)
		}
	}

	@Test
	fun buttonAndAltReplaceSelectionWaitForFinalAndCancelOnBackground() = withService { service ->
		val repository = service.repository
		val original = JSONObject(repository.config.value).getJSONObject("settings")
		val originalKey = if (original.isNull("speech_api_key_ref")) "" else repository.speechApiKey()
		var starts = 0
		var releases = 0
		var final = CompletableDeferred<Unit>()
		val engine = SpeechEngine { key, stop, onText ->
			assertEquals("voice-test-key", key)
			starts++
			try {
				onText("临时")
				stop.first { it }
				final.await()
				onText("最终。")
			} finally {
				releases++
			}
		}
		try {
			repository.saveSettings(JSONObject(original.toString()), "voice-test-key")
			instrumentation.uiAutomation.grantRuntimePermission(
				context.packageName,
				Manifest.permission.RECORD_AUDIO
			)
			compose.activityRule.scenario.onActivity { activity ->
				service.updateDraft("before old after")
				activity.setContent { MaterialTheme { EditorDialog(service, Typeface.MONOSPACE, engine) {} } }
			}
			compose.onNodeWithContentDescription("Start voice input").assertIsDisplayed()
			instrumentation.runOnMainSync { editor().setSelection(7, 10) }
			compose.onNodeWithContentDescription("Start voice input").performClick()
			compose.waitUntil(5000) { starts == 1 }
			instrumentation.runOnMainSync { assertEquals("before 临时 after", editor().text.toString()) }
			compose.onNodeWithContentDescription("Stop voice input").performClick()
			compose.onNodeWithText("Finishing recognition…").assertIsDisplayed()
			compose.onNodeWithContentDescription("Stop voice input").assertIsNotEnabled()
			instrumentation.runOnMainSync { final.complete(Unit) }
			compose.waitUntil(5000) { releases == 1 }
			instrumentation.runOnMainSync { assertEquals("before 最终。 after", editor().text.toString()) }
			compose.onNodeWithContentDescription("Start voice input").assertIsDisplayed()

			fun key(action: Int, code: Int) {
				instrumentation.runOnMainSync { editor().dispatchKeyEvent(KeyEvent(action, code)) }
			}
			key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT)
			key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT)
			Thread.sleep(ViewConfiguration.getLongPressTimeout() + 100L)
			assertEquals(1, starts)
			instrumentation.runOnMainSync { final = CompletableDeferred() }
			key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT)
			compose.waitUntil(5000) { starts == 2 }
			key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_RIGHT)
			compose.onNodeWithText("Finishing recognition…").assertIsDisplayed()
			instrumentation.runOnMainSync { final.complete(Unit) }
			compose.waitUntil(5000) { releases == 2 }

			key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT)
			key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
			key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)
			key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT)
			Thread.sleep(ViewConfiguration.getLongPressTimeout() + 100L)
			assertEquals(2, starts)

			instrumentation.runOnMainSync { final = CompletableDeferred() }
			compose.onNodeWithContentDescription("Start voice input").performClick()
			compose.waitUntil(5000) { starts == 3 }
			compose.onNodeWithText("Clear draft").performClick()
			compose.onNodeWithText("Finishing recognition…").assertIsDisplayed()
			assertTrue(service.draft.value.isNotEmpty())
			instrumentation.runOnMainSync { final.complete(Unit) }
			compose.waitUntil(5000) { releases == 3 }
			assertEquals("", service.draft.value)

			compose.onNodeWithContentDescription("Start voice input").performClick()
			compose.waitUntil(5000) { starts == 4 }
			instrumentation.runOnMainSync { editor().append("manual") }
			compose.waitUntil(5000) { releases == 4 }
			assertEquals("临时manual", service.draft.value)

			compose.onNodeWithContentDescription("Start voice input").performClick()
			compose.waitUntil(5000) { starts == 5 }
			compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
			compose.waitUntil(5000) { releases == 5 }
			assertTrue(service.draft.value.isNotEmpty())
		} finally {
			repository.saveSettings(original, originalKey)
		}
	}

	@Test
	fun tappingTextStopsRecordingAndKeepDraftWaitsForFinalCorrection() = withService { service ->
		val repository = service.repository
		val original = JSONObject(repository.config.value).getJSONObject("settings")
		val originalKey = if (original.isNull("speech_api_key_ref")) "" else repository.speechApiKey()
		var final = CompletableDeferred<Unit>()
		var stopped = CompletableDeferred<Unit>()
		var open by mutableStateOf(true)
		var kept: String? = null
		val engine = SpeechEngine { _, stop, onText ->
			onText("临时")
			stop.first { it }
			stopped.complete(Unit)
			final.await()
			onText("最终修正。")
		}
		try {
			repository.saveSettings(JSONObject(original.toString()), "voice-test-key")
			instrumentation.uiAutomation.grantRuntimePermission(
				context.packageName,
				Manifest.permission.RECORD_AUDIO
			)
			compose.activityRule.scenario.onActivity { activity ->
				service.updateDraft("before old after")
				activity.setContent {
					MaterialTheme {
						if (open) {
							EditorDialog(service, Typeface.MONOSPACE, engine) {
								kept = service.draft.value
								open = false
							}
						}
					}
				}
			}
			fun start() {
				compose.onNodeWithContentDescription("Start voice input").assertIsDisplayed()
				instrumentation.runOnMainSync { editor().setSelection(7, 10) }
				compose.onNodeWithContentDescription("Start voice input").performClick()
				compose.waitUntil(5000) { service.draft.value == "before 临时 after" }
			}
			start()
			instrumentation.runOnMainSync {
				val input = editor()
				val time = SystemClock.uptimeMillis()
				for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
					val event = MotionEvent.obtain(
						time,
						time,
						action,
						input.totalPaddingLeft + 1f,
						input.totalPaddingTop + input.lineHeight / 2f,
						0
					)
					try {
						input.dispatchTouchEvent(event)
					} finally {
						event.recycle()
					}
				}
			}
			compose.waitUntil(5000) { stopped.isCompleted }
			compose.onNodeWithText("Finishing recognition…").assertIsDisplayed()
			instrumentation.runOnMainSync {
				assertEquals(0, editor().selectionStart)
				final.complete(Unit)
			}
			compose.waitUntil(5000) { service.draft.value == "before 最终修正。 after" }
			instrumentation.runOnMainSync { assertEquals(0, editor().selectionStart) }
			assertEquals(null, kept)

			instrumentation.runOnMainSync {
				final = CompletableDeferred()
				stopped = CompletableDeferred()
				service.updateDraft("before old after")
			}
			compose.waitForIdle()
			start()
			compose.onNodeWithText("Keep draft").performClick()
			compose.waitUntil(5000) { stopped.isCompleted }
			compose.onNodeWithText("Keep draft").assertIsNotEnabled()
			assertEquals(null, kept)
			instrumentation.runOnMainSync { final.complete(Unit) }
			compose.waitUntil(5000) { kept != null }
			assertEquals("before 最终修正。 after", kept)
			compose.onNodeWithText("Local editor").assertDoesNotExist()
		} finally {
			repository.saveSettings(original, originalKey)
		}
	}

	@Test
	fun pasteAndShiftEnterWaitForFinalEvenBeforeTheFirstTranscript() = withService { service ->
		val arguments = InstrumentationRegistry.getArguments()
		val host = arguments.getString("sshHost")
		assumeNotNull(host)
		val repository = service.repository
		val original = JSONObject(repository.config.value).getJSONObject("settings")
		val originalKey = if (original.isNull("speech_api_key_ref")) "" else repository.speechApiKey()
		var final = CompletableDeferred<Unit>()
		var stopped = CompletableDeferred<Unit>()
		var open by mutableStateOf(true)
		var applied: String? = null
		var dismissed = false
		val engine = SpeechEngine { _, stop, onText ->
			stop.first { it }
			stopped.complete(Unit)
			final.await()
			onText("corrected final")
			applied = service.draft.value
		}
		try {
			repository.saveSettings(JSONObject(original.toString()), "voice-test-key")
			instrumentation.uiAutomation.grantRuntimePermission(
				context.packageName,
				Manifest.permission.RECORD_AUDIO
			)
			instrumentation.runOnMainSync {
				service.connect(
					ConnectionRequest(
						"Speech paste test",
						host!!,
						arguments.getString("sshPort", "22222").toInt(),
						"dryssh",
						JSONObject().put("kind", "password").put("password", "dryssh-test"),
						false,
						false
					)
				)
			}
			compose.waitUntil(10000) {
				service.state.value.hostKey != null ||
					service.state.value.state == "connected"
			}
			instrumentation.runOnMainSync { if (service.state.value.hostKey != null) service.trust(true) }
			compose.waitUntil(10000) { service.state.value.state == "connected" }
			compose.activityRule.scenario.onActivity { activity ->
				activity.setContent {
					MaterialTheme {
						if (open) {
							EditorDialog(service, Typeface.MONOSPACE, engine) {
								dismissed = true
								open = false
							}
						}
					}
				}
			}
			for (shortcut in listOf(false, true)) {
				instrumentation.runOnMainSync {
					service.updateDraft("")
					final = CompletableDeferred()
					stopped = CompletableDeferred()
					applied = null
					dismissed = false
					open = true
				}
				compose.onNodeWithContentDescription("Start voice input").performClick()
				compose.onNodeWithText("Paste").assertIsEnabled()
				if (shortcut) {
					instrumentation.runOnMainSync {
						for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
							editor().dispatchKeyEvent(
								KeyEvent(0, 0, action, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON)
							)
						}
					}
				} else {
					compose.onNodeWithText("Paste").performClick()
				}
				compose.waitUntil(5000) { stopped.isCompleted }
				compose.onNodeWithText("Finishing recognition…").assertIsDisplayed()
				assertFalse(dismissed)
				assertEquals(null, applied)
				instrumentation.runOnMainSync { final.complete(Unit) }
				compose.waitUntil(5000) { dismissed }
				assertEquals("corrected final", applied)
				assertEquals("", service.draft.value)
				compose.onNodeWithText("Local editor").assertDoesNotExist()
			}
		} finally {
			instrumentation.runOnMainSync { service.disconnect() }
			repository.saveSettings(original, originalKey)
		}
	}

	@Test fun voiceSettingsEnableOnlyAfterSavingAndCanBeRemoved() {
		val repository = (context.applicationContext as DrySshApplication).repository
		val original = JSONObject(repository.config.value).getJSONObject("settings")
		val originalKey = if (original.isNull("speech_api_key_ref")) "" else repository.speechApiKey()
		try {
			repository.saveSettings(JSONObject(original.toString()), "")
			compose.onNodeWithContentDescription("Editor").performClick()
			compose.onNodeWithContentDescription("Start voice input").assertDoesNotExist()
			compose.onNodeWithText("Keep draft").performClick()
			compose.onNodeWithContentDescription("Settings").performClick()
			compose.onNodeWithText("Voice", useUnmergedTree = true).performClick()
			compose.onNodeWithText("No API key saved. Voice input disabled.").assertIsDisplayed()
			compose.onNodeWithText("Volcengine API key").performTextInput("settings-test-key")
			compose.onNodeWithText("API key not saved yet. Save settings to apply.").assertIsDisplayed()
			compose.onNodeWithText("Doubao 1.0 · hourly").performScrollTo().performClick()
			assertTrue(
				JSONObject(repository.config.value).getJSONObject("settings").isNull("speech_api_key_ref")
			)
			compose.onNodeWithText("Save settings").performClick()
			compose.waitUntil(5000) {
				!JSONObject(repository.config.value).getJSONObject("settings").isNull("speech_api_key_ref")
			}
			assertEquals("settings-test-key", AppRepository(context).speechApiKey())
			assertFalse(repository.config.value.contains("settings-test-key"))
			assertEquals(
				"volc.bigasr.sauc.duration",
				JSONObject(AppRepository(context).config.value)
					.getJSONObject("settings").getString("speech_resource_id")
			)
			compose.onNodeWithContentDescription("Editor").performClick()
			compose.onNodeWithContentDescription("Start voice input").assertIsDisplayed()
			compose.onNodeWithText("Keep draft").performClick()
			compose.onNodeWithContentDescription("Settings").performClick()
			compose.onNodeWithText("Voice", useUnmergedTree = true).performClick()
			compose.waitUntil(5000) {
				compose.onAllNodes(
					hasText("API key saved. Voice input enabled.")
				).fetchSemanticsNodes().isNotEmpty()
			}
			compose.onNodeWithText("API key saved. Voice input enabled.").assertIsDisplayed()
			val field = compose.onNode(
				hasText("Volcengine API key") and hasSetTextAction()
			).fetchSemanticsNode()
			assertTrue(field.config[SemanticsProperties.EditableText].text.isNotEmpty())
			assertTrue(field.config.contains(SemanticsProperties.Password))
			compose.onNodeWithText("Disable voice input").performScrollTo().performClick()
			compose.onNodeWithText("Save settings").performClick()
			compose.waitUntil(5000) {
				JSONObject(repository.config.value).getJSONObject("settings").isNull("speech_api_key_ref")
			}
			compose.onNodeWithContentDescription("Editor").performClick()
			compose.onNodeWithContentDescription("Start voice input").assertDoesNotExist()
		} finally {
			repository.saveSettings(original, originalKey)
		}
	}
}
