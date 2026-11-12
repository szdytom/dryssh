package dev.dryssh

import android.content.ClipboardManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TouchClipboardTest {
	@get:Rule val compose = createEmptyComposeRule()

	@Test fun touchMouseScrollAndNvimOsc52PassThroughTmux() {
		SessionFixture().use { fixture ->
			fixture.connect()
			fun send(text: String) {
				fixture.instrumentation.runOnMainSync {
					fixture.service.terminal.pasteText(text)
					fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
				}
			}
			fun keys(text: String) {
				fixture.instrumentation.runOnMainSync {
					text.forEach { fixture.service.terminal.dispatchCharacter(0, it.code) }
				}
			}
			val token = System.nanoTime().toString()
			val file = "/tmp/dryssh-mouse-$token"
			val ready = "https://example.org/mouse-ready-$token"
			send(
				"printf 'row-%02d-abcdefghijklmnop\\n' \$(seq 0 99) > $file; printf '%s%s\\n' 'https://example.' 'org/mouse-ready-$token'"
			)
			fixture.awaitCondition { fixture.service.terminal.getUrls().any { it.url == ready } }
			send(
				"tmux -L mouse-$token -f /home/dryssh/tmux-acceptance.conf new-session -s acceptance 'nvim -u /home/dryssh/acceptance.lua -i NONE --noplugin $file'"
			)
			fixture.awaitCondition { fixture.service.terminal.getUrls().none { it.url == ready } }
			Thread.sleep(400)
			compose.waitForIdle()
			fixture.service.terminal.pasteTextIfBracketed("")
			val dimensions = fixture.service.terminal.dimensions
			compose.onNodeWithTag("terminal").performTouchInput {
				click(Offset(width * 7.5f / dimensions.columns, height * 2.5f / dimensions.rows))
			}
			fixture.instrumentation.runOnMainSync {
				fixture.service.terminal.dispatchCharacter(0, 'x'.code)
				"\"+yy".forEach { fixture.service.terminal.dispatchCharacter(0, it.code) }
			}
			val clipboard = fixture.instrumentation.targetContext.getSystemService(
				ClipboardManager::class.java
			)
			var copied = ""
			fixture.awaitCondition {
				fixture.instrumentation.runOnMainSync {
					copied =
						clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
				}
				copied == "row-02-bcdefghijklmnop\n"
			}
			assertEquals("row-02-bcdefghijklmnop\n", copied)
			compose.onNodeWithTag("terminal").performTouchInput { swipeUp() }
			Thread.sleep(200)
			fixture.instrumentation.runOnMainSync {
				fixture.service.terminal.dispatchKey(0, VTermKey.ESCAPE)
			}
			keys(":call writefile([string(line('w0'))], '$file.top')")
			fixture.instrumentation.runOnMainSync { fixture.service.terminal.dispatchKey(0, VTermKey.ENTER) }
			keys(":wq")
			fixture.instrumentation.runOnMainSync { fixture.service.terminal.dispatchKey(0, VTermKey.ENTER) }
			fixture.awaitCondition { fixture.service.terminal.getUrls().any { it.url == ready } }
			send(
				"test \$(cat $file.top) -gt 1 && test \"\$(sed -n '3p' $file)\" = 'row-02-bcdefghijklmnop' && printf '%s%s\\n' 'https://example.' 'org/mouse-file-$token'"
			)
			fixture.awaitCondition {
				fixture.service.terminal.getUrls().any {
					it.url ==
						"https://example.org/mouse-file-$token"
				}
			}
			compose.onNodeWithTag("terminal").performTouchInput { longClick(center) }
			compose.onNodeWithText("Local history").performClick()
			compose.onNodeWithText("Return to live terminal").performClick()
			compose.onNodeWithTag("terminal").performTouchInput { longClick(center) }
			compose.onNodeWithText("Select all").performClick()
			compose.onNodeWithTag("terminal").performTouchInput { longClick(center) }
			compose.onNodeWithText("Copy selection").performClick()
			fixture.awaitCondition {
				fixture.instrumentation.runOnMainSync {
					copied =
						clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
				}
				copied.contains("mouse-file-$token")
			}
		}
	}
}
