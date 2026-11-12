package dev.dryssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileUiTest {
	@get:Rule val compose = createAndroidComposeRule<MainActivity>()

	@Test fun createsEditsReloadsAndDeletesSavedProfileThroughUi() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val repository = (context.applicationContext as DrySshApplication).repository
		val before = JSONObject(repository.config.value).getJSONArray("profiles").length()
		compose.onNodeWithContentDescription("Connect").performClick()
		fun fill(label: String, text: String) {
			val field = compose.onNode(hasText(label) and hasSetTextAction())
			field.performScrollTo().performTextClearance()
			field.performTextInput(text)
		}
		fill("Name (optional)", "Profile UI acceptance")
		fill("Host", "localhost")
		fill("Username", "dryssh")
		fill("Password", "ui-secret")
		compose.onNodeWithText("Save profile").performScrollTo().performClick()
		compose.waitUntil {
			JSONObject(repository.config.value).getJSONArray("profiles").length() ==
				before + 1
		}
		try {
			fill("Name (optional)", "Edited UI acceptance")
			compose.onNodeWithText("Save profile").performScrollTo().performClick()
			compose.waitUntil { repository.config.value.contains("Edited UI acceptance") }
			compose.onNodeWithText("Cancel").performClick()
			compose.activityRule.scenario.recreate()
			compose.onNodeWithContentDescription("Connect").performClick()
			compose.onNodeWithText("Edited UI acceptance").assertIsDisplayed()
			compose.onNodeWithText("Edit").performClick()
			compose.onNodeWithText("Delete profile").performScrollTo().performClick()
			compose.waitUntil {
				JSONObject(repository.config.value).getJSONArray("profiles").length() ==
					before
			}
			assertEquals(
				before,
				JSONObject(AppRepository(context).config.value).getJSONArray("profiles").length()
			)
		} finally {
			val items = JSONObject(repository.config.value).getJSONArray("profiles")
			for (i in 0 until items.length()) {
				val item = items.getJSONObject(i)
				if (item.getString(
						"name"
					).endsWith("UI acceptance")
				) {
					repository.deleteProfile(item.getString("id"))
				}
			}
		}
	}
}
