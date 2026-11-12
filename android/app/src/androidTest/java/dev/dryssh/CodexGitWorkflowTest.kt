package dev.dryssh

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.security.MessageDigest
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CodexGitWorkflowTest {
	@get:Rule val compose = createEmptyComposeRule()

	@Test fun multilineEditorReachesRealCodexInTmuxAndGitStagesAnInteractiveHunk() {
		SessionFixture().use { fixture ->
			fixture.connect()
			fun command(text: String) {
				fixture.instrumentation.runOnMainSync {
					fixture.service.terminal.pasteText(text)
					fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
				}
			}
			fun hasUrl(url: String) = fixture.service.terminal.getUrls().any { it.url == url }
			val token = System.nanoTime().toString()
			val ready = "https://s/codex-ready-$token"
			command("printf '%s%s\\n' 'https://s/' 'codex-ready-$token'")
			fixture.awaitCondition { hasUrl(ready) }
			command(
				"tmux -L codex-$token -f /home/dryssh/tmux-acceptance.conf new-session -s acceptance 'cd /home/dryssh; codex-fixture --no-daemon --no-alt-screen'"
			)
			fixture.awaitCondition { !hasUrl(ready) && fixture.service.terminal.bracketedPasteEnabled }
			Thread.sleep(1_500)
			compose.onNodeWithContentDescription("Editor").performClick()
			compose.onNodeWithText("Local editor").assertIsDisplayed()
			val draft = "中文编辑与语音\nsecond line $token"
			fixture.instrumentation.runOnMainSync {
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
				WindowInspector.getGlobalWindowViews().firstNotNullOf { find(it) }.setText(draft)
			}
			compose.onNodeWithText("Paste").performClick()
			val digest = MessageDigest.getInstance(
				"SHA-256"
			).digest(draft.toByteArray()).take(6).joinToString("") {
				"%02x".format(it.toInt() and 255)
			}
			val response = "https://s/codex-$digest"
			Thread.sleep(500)
			assertFalse(hasUrl(response))
			fixture.instrumentation.runOnMainSync { fixture.service.terminal.dispatchKey(0, VTermKey.ENTER) }
			fixture.awaitCondition { hasUrl(response) }
			repeat(2) {
				fixture.instrumentation.runOnMainSync {
					fixture.service.terminal.dispatchCharacter(4, 'c'.code)
				}
				Thread.sleep(100)
			}
			fixture.awaitCondition { hasUrl(ready) }
			command(
				"mkdir /tmp/dryssh-git-$token; cd /tmp/dryssh-git-$token; git init -q; printf 'before\\n' > note; git add note; git -c user.name=DrySSH -c user.email=test@example.org commit -qm initial; printf 'after\\n' > note; git add -p; git diff --cached --quiet || printf '%s%s\\n' 'https://s/' 'git-staged-$token'"
			)
			Thread.sleep(500)
			fixture.instrumentation.runOnMainSync {
				fixture.service.terminal.dispatchCharacter(0, 'y'.code)
				fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
			}
			fixture.awaitCondition { hasUrl("https://s/git-staged-$token") }
		}
	}
}
