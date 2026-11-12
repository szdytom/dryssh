package dev.dryssh

import android.content.pm.ActivityInfo
import android.view.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LandscapeLayoutTest {
	@get:Rule val compose = createEmptyComposeRule()

	@Test fun landscapeUsesOneLeftRailAndPreservesTheWholeTerminalHeight() {
		SessionFixture().use { fixture ->
			fixture.connect()
			val pid = fixture.shellPid("rail-before")
			try {
				fixture.activity.onActivity {
					it.requestedOrientation =
						ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
				}
				compose.waitUntil(10_000) {
					compose.onAllNodes(
						androidx.compose.ui.test.hasTestTag("landscape-controls")
					).fetchSemanticsNodes().isNotEmpty()
				}
				fixture.activity.onActivity { it.window.insetsController!!.hide(WindowInsets.Type.ime()) }
				compose.waitForIdle()
				val rail = compose.onNodeWithTag("landscape-controls").fetchSemanticsNode().boundsInRoot
				val terminal = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
				assertTrue(rail.right <= terminal.left + 1f)
				assertEquals(rail.top, terminal.top, 1f)
				assertEquals(rail.bottom, terminal.bottom, 1f)
				assertTrue(rail.width < terminal.width / 8)
				for (name in listOf(
					"Connect",
					"Editor",
					"Keyboard",
					"Quick actions",
					"Settings",
					"Session options"
				)) {
					compose.onNodeWithContentDescription(name).assertIsDisplayed()
				}
				compose.onNodeWithContentDescription("Editor").performClick()
				compose.onNodeWithText("Local editor").assertIsDisplayed()
				compose.onNodeWithText("Keep draft").performClick()
				compose.onNodeWithContentDescription("Session options").performClick()
				compose.onNodeWithText("Connected").assertIsDisplayed()
				compose.onNodeWithText("Allow sleep").performClick()
				compose.onNodeWithContentDescription("Quick actions").performClick()
				compose.onNodeWithText("Ctrl-C").performClick()
				Thread.sleep(200)
				assertEquals(pid, fixture.shellPid("rail-after"))
			} finally {
				fixture.activity.onActivity {
					it.requestedOrientation =
						ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
				}
			}
		}
	}
}
