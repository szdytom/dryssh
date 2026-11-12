package dev.dryssh

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorIntegrationTest {
	@get:Rule val compose = createEmptyComposeRule()

	private fun findEditor(view: View): EditText? {
		if (view is EditText) return view
		if (view is ViewGroup) {
			for (index in 0 until view.childCount) {
				findEditor(view.getChildAt(index))?.let { return it }
			}
		}
		return null
	}

	private fun editor(): EditText = WindowInspector.getGlobalWindowViews().firstNotNullOf {
		findEditor(it)
	}

	private fun command(fixture: SessionFixture, text: String) {
		fixture.instrumentation.runOnMainSync {
			fixture.service.terminal.pasteText(text)
			fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
		}
	}

	private fun keys(fixture: SessionFixture, text: String) {
		fixture.instrumentation.runOnMainSync {
			for (character in text) fixture.service.terminal.dispatchCharacter(0, character.code)
			fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
		}
	}

	@Test
	fun offlineDraftSurvivesBackgroundAndActivityRecreation() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		ActivityScenario.launch(MainActivity::class.java).use { activity ->
			compose.waitUntil(5_000) {
				compose.onAllNodes(
					androidx.compose.ui.test.hasContentDescription("Editor")
				).fetchSemanticsNodes().isNotEmpty()
			}
			compose.onNodeWithContentDescription("Editor").performClick()
			compose.onNodeWithText("Local editor").assertIsDisplayed()
			val draft = "本地草稿\nkept until explicitly pasted"
			instrumentation.runOnMainSync { editor().setText(draft) }
			activity.moveToState(Lifecycle.State.CREATED)
			Thread.sleep(500)
			activity.moveToState(Lifecycle.State.RESUMED)
			compose.onNodeWithText("Local editor").assertIsDisplayed()
			activity.recreate()
			compose.onNodeWithText("Local editor").assertIsDisplayed()
			instrumentation.runOnMainSync { assertEquals(draft, editor().text.toString()) }
			compose.onNodeWithText("Paste").assertIsNotEnabled()
			compose.onNodeWithText("Keep draft").performClick()
		}
	}

	@Test(timeout = 60_000)
	fun unprotectedMultilineDraftSurvivesRecreationAndPlainPasteAddsNoReturn() {
		SessionFixture().use { fixture ->
			fixture.connect()
			command(
				fixture,
				"bind 'set enable-bracketed-paste off'; printf '%s%s\\n' 'https://example.' 'org/paste-off'"
			)
			fixture.awaitCondition {
				!fixture.service.terminal.bracketedPasteEnabled &&
					fixture.service.terminal.getUrls().any {
						it.url == "https://example.org/paste-off"
					}
			}
			val draft = "printf '%s%s\\n' 'https://example.' 'org/plain-first'\n" +
				"printf '%s%s\\n' 'https://example.' 'org/plain-second'"
			compose.onNodeWithContentDescription("Editor").performClick()
			compose.onNodeWithText("Local editor").assertIsDisplayed()
			fixture.instrumentation.runOnMainSync { editor().setText(draft) }
			compose.onNodeWithText("Paste").assertIsNotEnabled()
			compose.onNodeWithText("Plain paste").assertIsDisplayed()
			compose.onNodeWithText("Keep draft").performClick()
			fixture.activity.recreate()
			assertEquals(draft, fixture.service.draft.value)
			compose.onNodeWithContentDescription("Editor").performClick()
			compose.onNodeWithText("Local editor").assertIsDisplayed()
			fixture.instrumentation.runOnMainSync { assertEquals(draft, editor().text.toString()) }
			compose.onNodeWithText("Plain paste").performClick()
			fixture.awaitCondition {
				fixture.service.terminal.getUrls().any {
					it.url ==
						"https://example.org/plain-first"
				}
			}
			Thread.sleep(250)
			assertTrue(
				fixture.service.terminal.getUrls().none {
					it.url ==
						"https://example.org/plain-second"
				}
			)
			fixture.instrumentation.runOnMainSync {
				fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
			}
			fixture.awaitCondition {
				fixture.service.terminal.getUrls().any {
					it.url ==
						"https://example.org/plain-second"
				}
			}
		}
	}

	@Test(timeout = 60_000)
	fun protectedEditorPasteInTmuxNvimPreservesLinesAndBypassesEnterMapping() {
		SessionFixture().use { fixture ->
			fixture.connect()
			val pid = fixture.shellPid("editor-base")
			val ready = "https://example.org/editor-base-$pid"
			val suffix = System.nanoTime().toString()
			val file = "/tmp/dryssh-editor-$suffix.txt"
			command(fixture, "tmux new-session -s editor$suffix 'nvim --clean $file'")
			fixture.awaitCondition { fixture.service.terminal.getUrls().none { it.url == ready } }
			keys(fixture, ":inoremap <CR> [ENTER]")
			fixture.instrumentation.runOnMainSync {
				fixture.service.terminal.dispatchCharacter(0, 'i'.code)
			}
			fixture.awaitCondition { fixture.service.terminal.bracketedPasteEnabled }
			val draft = "第一行\n  第二行"
			compose.onNodeWithContentDescription("Editor").performClick()
			compose.onNodeWithText("Local editor").assertIsDisplayed()
			fixture.instrumentation.runOnMainSync { editor().setText(draft) }
			compose.onNodeWithText("Paste").assertIsEnabled().performClick()
			compose.onNodeWithText("Local editor").assertDoesNotExist()
			compose.onNodeWithContentDescription("Keyboard").performClick()
			compose.onNodeWithText("Esc").performClick()
			keys(fixture, ":wq")
			fixture.awaitCondition { fixture.service.terminal.getUrls().any { it.url == ready } }
			command(
				fixture,
				"printf 'https://example.org/file-%s\\n' \"\$(od -An -v -tx1 $file | tr -d ' \\n')\""
			)
			val hex = (draft + "\n").toByteArray().joinToString("") {
				"%02x".format(it.toInt() and 255)
			}
			fixture.awaitCondition {
				fixture.service.terminal.getUrls().any {
					it.url ==
						"https://example.org/file-$hex"
				}
			}
		}
	}
}
