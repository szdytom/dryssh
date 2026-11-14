package dev.dryssh

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TerminalInsetTest {

	@get:Rule val compose = createEmptyComposeRule()

	@Test fun insetSettingSavesAcrossRecreationAndCancelPreservesIt() {
		ActivityScenario.launch(MainActivity::class.java).use { activity ->
			lateinit var repository: AppRepository
			activity.onActivity { repository = (it.application as DrySshApplication).repository }
			val original = JSONObject(repository.config.value)
			try {
				repository.update(
					JSONObject(original.toString()).apply {
						getJSONObject("settings").put("terminal_inset", 4)
					}
				)
				compose.onNodeWithContentDescription("Settings").performClick()
				compose.onNodeWithText("Terminal inset: 4 px").performScrollTo().assertIsDisplayed()
				compose.onNodeWithTag(
					"terminal-inset-slider"
				).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {
					it(12f)
				}
				compose.onNodeWithText("Save settings").performClick()
				compose.waitUntil(5_000) {
					JSONObject(repository.config.value).getJSONObject("settings").getInt("terminal_inset") ==
						12
				}
				val restored = AppRepository(InstrumentationRegistry.getInstrumentation().targetContext)
				assertEquals(
					12,
					JSONObject(restored.config.value).getJSONObject("settings").getInt("terminal_inset")
				)
				activity.recreate()
				compose.onNodeWithContentDescription("Settings").performClick()
				compose.onNodeWithText("Terminal inset: 12 px").performScrollTo().assertIsDisplayed()
				compose.onNodeWithTag(
					"terminal-inset-slider"
				).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {
					it(0f)
				}
				compose.onNodeWithText("Cancel").performClick()
				assertEquals(
					12,
					JSONObject(repository.config.value).getJSONObject("settings").getInt("terminal_inset")
				)
			} finally {
				repository.update(original)
			}
		}
	}

	@Test fun defaultAndLiveInsetsUsePhysicalPixelsForRenderingSizingAndMouseInput() {
		val output = ByteArrayOutputStream()
		TerminalEmulatorFactory.create(Looper.getMainLooper(), onKeyboardInput = {
			synchronized(output) { output.write(it) }
		}).use { terminal ->
			ActivityScenario.launch(MainActivity::class.java).use { activity ->
				val density = mutableStateOf(1f)
				val inset = mutableStateOf<Int?>(null)
				activity.onActivity {
					it.setContent {
						CompositionLocalProvider(LocalDensity provides Density(density.value)) {
							val localDensity = LocalDensity.current
							Box(
								Modifier.requiredSize(
									with(localDensity) {
										240.toDp()
									},
									with(localDensity) { 180.toDp() }
								)
							) {
								val modifier = Modifier.testTag("inset-terminal")
								if (inset.value == null) {
									Terminal(terminal, modifier, initialFontSize = 12.sp)
								} else {
									Terminal(terminal, modifier, initialFontSize = 12.sp, contentInsetPx = inset.value!!)
								}
							}
						}
					}
				}
				for (scale in listOf(1f, 2f, 3f)) {
					for (padding in listOf(null, 0, 16, 64, 4)) {
						compose.runOnIdle {
							density.value = scale
							inset.value = padding
						}
						val px = padding ?: 4
						val paint = Paint().apply {
							typeface = Typeface.MONOSPACE
							textSize = 12f * scale
						}
						val charWidth = paint.measureText("M")
						val charHeight = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
						val cols = ((240 - px * 2) / charWidth).toInt()
						val rows = ((180 - px * 2) / charHeight).toInt()
						compose.waitUntil(5_000) {
							val size = terminal.dimensions
							size.widthPixels == 240 - px * 2 && size.heightPixels == 180 - px * 2 &&
								size.rows == rows && size.columns == cols
						}
						terminal.writeInput(
							"\u001b[?25l\u001b[48;2;255;0;0m\u001b[2J\u001b[?1000h\u001b[?1006h".toByteArray()
						)
						terminal.pasteTextIfBracketed("")
						compose.waitForIdle()
						val node = compose.onNodeWithTag("inset-terminal")
						val image = node.captureToImage()
						assertEquals(240, image.width)
						assertEquals(180, image.height)
						val pixels = image.toPixelMap()
						for (y in 0 until 180) {
							for (x in 0 until 240) {
								if (x < px || y < px || x >= 240 - px || y >= 180 - px) {
									assertEquals(
										"Margin at ($x, $y), inset $px, density $scale",
										Color.Black.toArgb(),
										pixels[x, y].toArgb()
									)
								}
							}
						}
						assertEquals(Color.Red.toArgb(), pixels[px, px].toArgb())
						val row = minOf(2, rows - 1)
						val col = minOf(3, cols - 1)
						node.performTouchInput {
							click(
								Offset(
									px + (col + 0.5f) * charWidth,
									px + (row + 0.5f) * charHeight
								)
							)
						}
						val expected = "\u001b[<0;${col + 1};${row + 1}M\u001b[<0;${col + 1};${row + 1}m"
						compose.waitUntil(5_000) { synchronized(output) { output.toString("UTF-8") == expected } }
						synchronized(output) { output.reset() }
					}
				}
			}
		}
	}
}
