package dev.dryssh

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ThemeIntegrationTest {
	@get:Rule val compose = createEmptyComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test fun allBundledThemesRoundtripWithoutChangingOtherSettings() {
		val themes = TerminalTheme.load(context)
		assertEquals(180, themes.size)
		assertEquals(themes.size, themes.map { it.id }.toSet().size)
		for (theme in themes) {
			assertEquals(16, theme.ansi.size)
			assertTrue((theme.ansi + theme.foreground + theme.background).all { it ushr 24 == 255L })
			val config = JSONObject(NativeBridge.normalizeConfig("{}"))
			val settings = config.getJSONObject("settings").put("font_size", 23)
			val bindings = settings.getJSONArray("bindings").toString()
			theme.applyTo(settings)
			val restored = JSONObject(
				NativeBridge.normalizeConfig(config.toString())
			).getJSONObject("settings")
			assertTrue(theme.matches(restored))
			assertEquals(theme.id, TerminalTheme.selected(themes, restored).id)
			assertEquals(23.0, restored.getDouble("font_size"), 0.0)
			assertEquals(bindings, restored.getJSONArray("bindings").toString())
		}
		val everforest = themes.first { it.id == "everforest_dark_medium" }
		assertEquals(0xffd3c6aaL, everforest.foreground)
		assertEquals(0xff2d353bL, everforest.background)
		assertEquals(0xffa7c080L, everforest.ansi[2])
		assertEquals(0xfff4bf75L, themes.first { it.id == "citylights" }.ansi[3])
		assertEquals(0xffc55555L, themes.first { it.id == "doom_one" }.ansi[9])
	}

	@Test fun legacyColorsAreKeptUntilThemeIsSavedAndLightThemeSurvivesRecreation() {
		SessionFixture().use { fixture ->
			val repository = fixture.service.repository
			val original = JSONObject(repository.config.value).getJSONObject("settings")
			try {
				fixture.connect()
				val pid = fixture.shellPid("before-theme")
				val custom = JSONObject(original.toString()).put("theme", JSONObject.NULL)
					.put("foreground", 0xff123456L).put("background", 0xff070809L)
				repository.update(JSONObject(repository.config.value).put("settings", custom))
				fun openSettings() {
					compose.onNodeWithContentDescription("Settings").performClick()
				}
				fun search(value: String) {
					val field = compose.onNode(hasText("Search themes") and hasSetTextAction())
					field.performTextClearance()
					field.performTextInput(value)
				}
				openSettings()
				compose.onNodeWithText("Theme: Saved colors").performClick()
				search("nothing matches this theme")
				compose.onNodeWithText("No matching themes").assertIsDisplayed()
				search("EVERFOREST DARK MEDIUM")
				compose.onNodeWithText("Everforest Dark Medium").performClick()
				compose.onNodeWithText("Theme: Everforest Dark Medium").assertIsDisplayed()
				compose.onNodeWithText("Cancel").performClick()
				assertEquals(
					custom.toString(),
					JSONObject(repository.config.value).getJSONObject("settings").toString()
				)
				openSettings()
				compose.onNodeWithText("Theme: Saved colors").performClick()
				compose.onNodeWithText("Back").performClick()
				compose.onNodeWithText("Theme: Saved colors").assertIsDisplayed()
				compose.onNodeWithText("Theme: Saved colors").performClick()
				search("everforest light medium")
				compose.onNodeWithText("Everforest Light Medium").performClick()
				compose.onNodeWithText("Save settings").performClick()
				fixture.awaitCondition {
					JSONObject(repository.config.value).getJSONObject("settings").optString("theme") ==
						"everforest_light_medium"
				}
				val saved = JSONObject(AppRepository(context).config.value).getJSONObject("settings")
				assertEquals(0xfffdf6e3L, saved.getLong("background"))
				fixture.activity.recreate()
				compose.waitForIdle()
				openSettings()
				compose.onNodeWithText("Theme: Everforest Light Medium").assertIsDisplayed()
				compose.onNodeWithText("Foreground").assertDoesNotExist()
				compose.onNodeWithText("ANSI 1").assertDoesNotExist()
				compose.onNodeWithText("Cancel").performClick()
				assertEquals(pid, fixture.shellPid("after-theme"))
			} finally {
				repository.update(JSONObject(repository.config.value).put("settings", original))
			}
		}
	}

	@Test fun themeSearchAndSelectionFitLandscapeAfterDismissingTheIme() {
		SessionFixture().use { fixture ->
			try {
				fixture.activity.onActivity {
					it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
				}
				compose.waitUntil(10_000) {
					compose.onAllNodes(hasTestTag("landscape-controls")).fetchSemanticsNodes().isNotEmpty()
				}
				compose.onNodeWithContentDescription("Settings").performClick()
				compose.onNodeWithText("Theme:", substring = true).performClick()
				val search = compose.onNode(hasText("Search themes") and hasSetTextAction())
				search.performTextInput("everforest dark medium")
				search.performImeAction()
				compose.waitUntil(5_000) {
					compose.onNodeWithText("Everforest Dark Medium").isDisplayed()
				}
				compose.onNodeWithText("Everforest Dark Medium").assertIsDisplayed().performClick()
				compose.onNodeWithText("Theme: Everforest Dark Medium").assertIsDisplayed()
				compose.onNodeWithText("Cancel").performClick()
			} finally {
				fixture.activity.onActivity {
					it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
				}
			}
		}
	}
}
