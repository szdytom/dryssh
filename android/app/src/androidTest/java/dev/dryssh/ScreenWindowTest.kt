package dev.dryssh

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenWindowTest {
	@get:Rule val compose = createEmptyComposeRule()

	@Test(timeout = 60_000)
	fun mainWindowAndDialogsRemainImmersiveAndAvoidTheKeyboard() {
		ActivityScenario.launch(MainActivity::class.java).use { activity ->
			compose.waitUntil(5_000) {
				compose.onAllNodes(
					androidx.compose.ui.test.hasTestTag("terminal")
				).fetchSemanticsNodes().isNotEmpty()
			}
			fun checkMain() {
				activity.onActivity { it.window.insetsController!!.hide(WindowInsets.Type.ime()) }
				compose.waitUntil(5_000) {
					var hidden = false
					activity.onActivity {
						val insets = it.window.decorView.rootWindowInsets
						hidden =
							!insets.isVisible(WindowInsets.Type.ime()) &&
							!insets.isVisible(WindowInsets.Type.statusBars()) &&
							!insets.isVisible(WindowInsets.Type.navigationBars())
					}
					hidden
				}
				val bounds = compose.onNodeWithTag("session-layout").fetchSemanticsNode().boundsInRoot
				activity.onActivity {
					assertEquals(0f, bounds.left, 1f)
					assertEquals(0f, bounds.top, 1f)
					assertEquals(it.window.decorView.width.toFloat(), bounds.right, 1f)
					assertEquals(it.window.decorView.height.toFloat(), bounds.bottom, 1f)
				}
				val terminal = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
				activity.onActivity { assertSafeTerminal(terminal, it.window.decorView.rootWindowInsets) }
			}
			checkMain()
			val beforeBars = compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot
			var screenSize = 0 to 0
			activity.onActivity { screenSize = it.window.decorView.width to it.window.decorView.height }
			val (width, height) = screenSize
			val command = "input swipe ${width / 2} ${height - 1} ${width / 2} ${height - 160} 300"
			val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
				command
			)
			ParcelFileDescriptor.AutoCloseInputStream(output).use { stream -> stream.readBytes() }
			compose.waitForIdle()
			assertEquals(beforeBars, compose.onNodeWithTag("terminal").fetchSemanticsNode().boundsInRoot)
			for ((orientation, rotation) in listOf(
				ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to 0,
				ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to 1,
				ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to 3
			)) {
				activity.onActivity { it.requestedOrientation = orientation }
				compose.waitUntil(5_000) {
					var current = -1
					activity.onActivity { current = it.display.rotation }
					current == rotation
				}
				checkMain()
				val prefix = InstrumentationRegistry.getArguments().getString("edgeScreenshotPrefix")
				if (prefix != null) {
					val instrumentation = InstrumentationRegistry.getInstrumentation()
					val screenshot = instrumentation.uiAutomation.takeScreenshot()
					File(instrumentation.targetContext.cacheDir, "$prefix-$rotation.png").outputStream().use {
						screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
					}
					screenshot.recycle()
				}
			}
			activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
			compose.waitUntil(5_000) {
				var portrait = false
				activity.onActivity { portrait = it.display.rotation == 0 }
				portrait
			}
			checkMain()
			for ((open, close) in listOf(
				"Settings" to "Cancel",
				"Connect" to "Cancel",
				"Editor" to "Keep draft"
			)) {
				compose.onNodeWithContentDescription(open).performClick()
				compose.onNodeWithTag("dialog-content").assertIsDisplayed()
				compose.waitForIdle()
				val content = compose.onNodeWithTag("dialog-content").fetchSemanticsNode().boundsInRoot
				activity.onActivity {
					val view = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
					val insets = view.rootWindowInsets
					assertFalse(insets.isVisible(WindowInsets.Type.statusBars()))
					val bottom = insets.getInsets(
						WindowInsets.Type.ime() or WindowInsets.Type.navigationBars()
					).bottom
					assertTrue(content.bottom <= view.height - bottom + 1)
				}
				compose.onNodeWithText(close).performClick()
				checkMain()
			}
			activity.recreate()
			checkMain()
		}
	}
}

private fun assertSafeTerminal(rect: Rect, insets: WindowInsets) {
	for (hole in insets.displayCutout?.boundingRects.orEmpty()) {
		assertFalse(
			rect.left < hole.right && rect.right > hole.left &&
				rect.top < hole.bottom && rect.bottom > hole.top
		)
	}
	if (Build.VERSION.SDK_INT >= 31) {
		for (position in 0..3) {
			val corner = insets.getRoundedCorner(position) ?: continue
			val left = position == RoundedCorner.POSITION_TOP_LEFT ||
				position == RoundedCorner.POSITION_BOTTOM_LEFT
			val top = position == RoundedCorner.POSITION_TOP_LEFT ||
				position == RoundedCorner.POSITION_TOP_RIGHT
			val dx = if (left) corner.center.x - rect.left else rect.right - corner.center.x
			val dy = if (top) corner.center.y - rect.top else rect.bottom - corner.center.y
			assertTrue(
				dx <= 0 || dy <= 0 ||
					dx * dx + dy * dy <= corner.radius.toFloat() * corner.radius + 1
			)
		}
	}
}
