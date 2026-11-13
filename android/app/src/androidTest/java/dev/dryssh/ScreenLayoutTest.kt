package dev.dryssh

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenLayoutTest {
	@get:Rule val compose = createEmptyComposeRule()

	@Test fun landscapeMovesRailToTheRightCutoutAndDoesNotPadTheTerminalTwice() {
		val hole = ScreenRect(820, 400, 1000, 600)
		val geometry = ScreenGeometry(listOf(ScreenCutout(hole, ScreenEdge.RIGHT)))
		withScreen(1000, 1200) {
			SessionLayout(
				true,
				header = { _, _ -> Box {} },
				footer = { _, _ -> Box {} },
				controls = { bounds, screen ->
					SafeRail(bounds, screen) { repeat(6) { ToolIcon(TerminalIcons.Connect, "Action $it") {} } }
				},
				terminal = { Box(it.testTag("viewport")) },
				geometry = geometry,
				imeBottom = 0
			)
		}.use {
			compose.waitForIdle()
			val viewport = bounds("viewport")
			val rail = bounds("landscape-controls")
			assertEquals(0f, viewport.left, 1f)
			assertEquals(820f, viewport.width, 1f)
			assertEquals(1200f, viewport.height, 1f)
			assertEquals(viewport.right, rail.left, 1f)
			for (index in 0 until 6) {
				val node = compose.onNodeWithContentDescription("Action $index").assertIsDisplayed()
				val button = node.fetchSemanticsNode().boundsInRoot
				assertTrue(button.bottom <= hole.top || button.top >= hole.bottom)
			}
		}
	}

	@Test fun portraitUsesBothSidesOfTheNotchWithoutReservingAnExtraTopStrip() {
		val geometry = ScreenGeometry(listOf(ScreenCutout(ScreenRect(440, 0, 560, 90), ScreenEdge.TOP)))
		var buttonSize = 0f
		withScreen(1000, 1500) {
			buttonSize = with(LocalDensity.current) { 48.dp.toPx() }
			SessionLayout(
				false,
				header = { bounds, screen ->
					SafeHeader(
						bounds,
						screen,
						listOf(
							EdgeAction(TerminalIcons.Connect, "Connect") {},
							EdgeAction(TerminalIcons.Editor, "Editor") {}
						)
					) { Text("Connection status", Modifier.testTag("status")) }
				},
				footer = { _, _ -> Box {} },
				controls = { _, _ -> Box {} },
				terminal = { Box(it.testTag("viewport")) },
				geometry = geometry,
				imeBottom = 0
			)
		}.use {
			compose.waitForIdle()
			assertTrue(bounds("status").right <= 440f)
			assertTrue(
				compose.onNodeWithContentDescription("Connect").fetchSemanticsNode().boundsInRoot.left >= 560f
			)
			assertEquals(buttonSize, bounds("viewport").top, 1f)
			assertEquals(1000f, bounds("viewport").width, 1f)
		}
	}

	@Test fun narrowToolbarKeepsOverflowActionsReachable() {
		val clicked = mutableIntStateOf(0)
		withScreen(300, 500) {
			SafeHeader(
				ScreenRect(0, 0, 300, 500),
				ScreenGeometry(),
				listOf(
					EdgeAction(TerminalIcons.Connect, "Connect") {},
					EdgeAction(TerminalIcons.Editor, "Editor") {},
					EdgeAction(TerminalIcons.Disconnect, "Disconnect") { clicked.intValue++ }
				)
			) { Text("Status") }
		}.use {
			compose.onNodeWithContentDescription("More actions").performClick()
			compose.onNodeWithText("Disconnect").performClick()
			assertEquals(1, clicked.intValue)
		}
	}

	@Test fun railScrollsThroughSafeSlotsInsteadOfMovingButtonsUnderTheCutout() {
		val geometry = ScreenGeometry(listOf(ScreenCutout(ScreenRect(0, 180, 180, 330), ScreenEdge.LEFT)))
		withScreen(180, 600) {
			SafeRail(ScreenRect(0, 0, 180, 600), geometry) {
				repeat(10) { index -> ToolIcon(TerminalIcons.Connect, "Action $index") {} }
			}
		}.use {
			compose.onNodeWithTag("landscape-controls").performTouchInput { swipeUp() }
			compose.onNodeWithContentDescription("Action 0").assertIsNotDisplayed()
			compose.onNodeWithTag("landscape-controls").performSemanticsAction(SemanticsActions.ScrollBy) {
				it(0f, 5000f)
			}
			compose.onNodeWithContentDescription("Action 9").assertIsDisplayed()
			for (index in 7..9) {
				val button = compose.onNodeWithContentDescription(
					"Action $index"
				).fetchSemanticsNode().boundsInRoot
				assertTrue(button.bottom <= 180 || button.top >= 330)
			}
		}
	}

	@Test fun keyboardAndNavigationReserveOnlyTheLargerInsetInTheActualLayout() {
		withScreen(1000, 1500) {
			SessionLayout(
				true,
				header = { _, _ -> Box {} },
				footer = { _, _ -> Box {} },
				controls = { _, _ -> Box(Modifier.fillMaxSize()) },
				terminal = { Box(it.testTag("viewport")) },
				geometry = ScreenGeometry(edges = ScreenEdges(bottom = 80)),
				imeBottom = 500
			)
		}.use {
			assertEquals(1000f, bounds("viewport").bottom, 1f)
		}
	}

	private fun bounds(tag: String): Rect =
		compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

	private fun withScreen(
		width: Int,
		height: Int,
		content: @Composable () -> Unit
	): ActivityScenario<MainActivity> =
		ActivityScenario.launch(MainActivity::class.java).also { activity ->
			activity.onActivity {
				it.setContent {
					MaterialTheme {
						val density = LocalDensity.current
						Box(Modifier.fillMaxSize()) {
							Box(
								Modifier.requiredSize(
									with(density) {
										width.toDp()
									},
									with(density) { height.toDp() }
								)
							) { content() }
						}
					}
				}
			}
		}
}
