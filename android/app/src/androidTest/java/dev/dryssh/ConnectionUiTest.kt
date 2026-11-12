package dev.dryssh

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test

class ConnectionUiTest {
	@get:Rule val compose = createAndroidComposeRule<MainActivity>()

	@Test
	fun connectsThroughFormAndTrustDialog() {
		val host = InstrumentationRegistry.getArguments().getString("sshHost")
		assumeNotNull(host)
		val port = InstrumentationRegistry.getArguments().getString("sshPort", "22222")
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		context.getSharedPreferences("host-keys", 0).edit().remove("$host:$port").commit()
		try {
			compose.onNodeWithContentDescription("Connect").performClick()
			compose.onNode(hasText("Host") and hasSetTextAction()).performTextInput(host!!)
			compose.onNode(hasText("Port") and hasSetTextAction()).performTextClearance()
			compose.onNode(hasText("Port") and hasSetTextAction()).performTextInput(port)
			compose.onNode(hasText("Username") and hasSetTextAction()).performTextInput("dryssh")
			compose.onNode(
				hasText("Password") and hasSetTextAction()
			).performTextInput("dryssh-test")
			compose.onAllNodesWithText("Connect").onLast().performClick()
			compose.waitUntil(20_000) {
				compose.onAllNodesWithText("Trust this host?").fetchSemanticsNodes().isNotEmpty()
			}
			compose.onNodeWithText("Trust and connect").performClick()
			compose.waitUntil(20_000) {
				compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty()
			}
			compose.onNodeWithText("dryssh@$host").assertIsDisplayed()
			compose.onNodeWithContentDescription("Disconnect").performClick()
			compose.onNodeWithText("Disconnected").assertIsDisplayed()
		} finally {
			context.startService(Intent(context, SshService::class.java).setAction("disconnect"))
		}
	}
}
