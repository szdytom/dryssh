package dev.dryssh

import android.content.pm.ActivityInfo
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ResizeUiTest {
	@get:Rule val compose = createEmptyComposeRule()

	@Test fun rotationAndImeResizeTheExistingRemotePty() {
		SessionFixture().use { fixture ->
			fixture.connect()
			val pid = fixture.shellPid("resize-before")
			fun ime(visible: Boolean) {
				fixture.activity.onActivity { activity ->
					if (visible) {
						activity.window.insetsController!!.show(WindowInsets.Type.ime())
					} else {
						activity.window.insetsController!!.hide(WindowInsets.Type.ime())
					}
				}
				compose.waitUntil(10_000) {
					var showing = false
					fixture.activity.onActivity {
						showing =
							it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
					}
					showing == visible
				}
				compose.waitForIdle()
				fixture.service.terminal.pasteTextIfBracketed("")
			}
			fun verifyPty(stage: String): TerminalDimensions {
				val dimensions = fixture.service.terminal.dimensions
				val url = "https://s/${dimensions.rows}x${dimensions.columns}-$stage"
				val command = "printf '%s%s%s\\n' 'https://s/' \"\$(stty size | tr ' ' 'x')\" '-$stage'"
				fixture.instrumentation.runOnMainSync {
					fixture.service.terminal.pasteText(command)
					fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
				}
				fixture.awaitCondition { fixture.service.terminal.getUrls().any { it.url == url } }
				return dimensions
			}
			try {
				fixture.activity.onActivity {
					it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
				}
				compose.waitForIdle()
				ime(false)
				val portrait = verifyPty("portrait")
				ime(true)
				compose.waitUntil(10_000) { fixture.service.terminal.dimensions.rows < portrait.rows }
				val keyboard = verifyPty("ime")
				assertTrue(keyboard.rows < portrait.rows)
				ime(false)
				fixture.activity.onActivity {
					it.requestedOrientation =
						ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
				}
				compose.waitUntil(10_000) { fixture.service.terminal.dimensions.columns > portrait.columns }
				ime(false)
				val landscape = verifyPty("landscape")
				assertTrue(landscape.columns > portrait.columns)
				assertTrue(landscape.rows < portrait.rows)
				assertEquals(pid, fixture.shellPid("resize-after"))
			} finally {
				fixture.activity.onActivity {
					it.requestedOrientation =
						ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
				}
			}
		}
	}
}
