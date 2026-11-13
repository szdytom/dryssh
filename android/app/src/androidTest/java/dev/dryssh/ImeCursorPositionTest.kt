package dev.dryssh

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil
import kotlin.random.Random
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImeCursorPositionTest {

	@get:Rule val compose = createEmptyComposeRule()
	private fun findIme(view: View): View? {
		if (view.javaClass.simpleName == "ImeInputView") return view
		if (view is ViewGroup) {
			for (index in 0 until view.childCount) {
				findIme(view.getChildAt(index))?.let { return it }
			}
		}
		return null
	}

	@Test fun anchorTracksTerminalCursorCompositionAndLayoutInsteadOfHiddenEditor() {
		val terminal = TerminalEmulatorFactory.create(Looper.getMainLooper(), onKeyboardInput = {})
		try {
			ActivityScenario.launch(MainActivity::class.java).use { activity ->
				val padding = mutableStateOf(24)
				val fontSize = mutableStateOf(14)
				val scale = mutableStateOf(1f)
				activity.onActivity {
					it.setContent {
						Box(
							Modifier.fillMaxSize().padding(padding.value.dp).graphicsLayer {
								scaleX = scale.value
								scaleY = scale.value
								transformOrigin = TransformOrigin(0f, 0f)
							}
						) {
							Terminal(
								terminal,
								initialFontSize = fontSize.value.sp,
								keyboardEnabled = true,
								showSoftKeyboard = false
							)
						}
					}
				}
				compose.waitForIdle()
				val updates = CopyOnWriteArrayList<CursorAnchorInfo>()
				lateinit var connection: InputConnection
				lateinit var editor: View
				activity.onActivity {
					editor = findIme(it.window.decorView)!!
					// Capture the exact `InputMethodManager` payload at the platform boundary.
					editor.javaClass.getDeclaredField("onUpdateCursorAnchorInfo").apply {
						isAccessible = true
						set(editor, { _: View, info: CursorAnchorInfo ->
							updates.add(info)
							Unit
						})
					}
					connection = editor.onCreateInputConnection(EditorInfo())!!
					assertTrue(
						connection.requestCursorUpdates(
							InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
						)
					)
				}
				assertTrue(updates.isNotEmpty())
				fun verify(row: Int, col: Int) {
					compose.waitForIdle()
					val info = updates.last()
					val paint = Paint().apply {
						typeface = Typeface.MONOSPACE
						textSize =
							TypedValue.applyDimension(
								TypedValue.COMPLEX_UNIT_SP,
								fontSize.value.toFloat(),
								editor.resources.displayMetrics
							)
					}
					val height = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
					assertEquals(col * paint.measureText("M"), info.insertionMarkerHorizontal, 0.1f)
					assertEquals(row * height, info.insertionMarkerTop, 0.1f)
					assertEquals(
						row * height + ceil(-paint.fontMetrics.ascent),
						info.insertionMarkerBaseline,
						0.1f
					)
					assertEquals((row + 1) * height, info.insertionMarkerBottom, 0.1f)
					val location = IntArray(2)
					activity.onActivity { editor.getLocationOnScreen(location) }
					val origin = floatArrayOf(0f, 0f)
					info.matrix.mapPoints(origin)
					assertEquals(location[0].toFloat(), origin[0], 1f)
					assertEquals(location[1].toFloat(), origin[1], 1f)
					val unit = floatArrayOf(1f, 1f)
					info.matrix.mapPoints(unit)
					assertEquals(scale.value, unit[0] - origin[0], 0.01f)
					assertEquals(scale.value, unit[1] - origin[1], 0.01f)
					assertTrue(info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION != 0)
				}
				verify(0, 0)
				val random = Random(2026)
				val positions = List(16) { random.nextInt(1, 10) to random.nextInt(1, 20) } + (1 to 3)
				for ((row, col) in positions) {
					val before = updates.size
					terminal.writeInput("\u001b[${row + 1};${col + 1}H".toByteArray())
					compose.waitUntil(5_000) { updates.size > before }
					verify(row, col)
				}
				activity.onActivity { connection.setComposingText("zhongwen", 1) }
				assertEquals("zhongwen", updates.last().composingText.toString())
				assertEquals(8, updates.last().selectionStart)
				verify(1, 3)
				val oldOrigin = floatArrayOf(0f, 0f).also { updates.last().matrix.mapPoints(it) }
				compose.runOnIdle {
					padding.value = 52
					fontSize.value = 20
					scale.value = 1.25f
				}
				compose.waitForIdle()
				verify(1, 3)
				val newOrigin = floatArrayOf(0f, 0f).also { updates.last().matrix.mapPoints(it) }
				assertTrue(newOrigin[0] > oldOrigin[0])
				assertTrue(newOrigin[1] > oldOrigin[1])
				activity.onActivity {
					assertFalse(connection.requestCursorUpdates(1 shl 20))
					assertTrue(connection.requestCursorUpdates(0))
				}
				val count = updates.size
				terminal.writeInput("\u001b[4;9H".toByteArray())
				compose.waitForIdle()
				assertEquals(count, updates.size)
				activity.onActivity {
					assertTrue(
						connection.requestCursorUpdates(
							InputConnection.CURSOR_UPDATE_IMMEDIATE,
							InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER
						)
					)
				}
				assertEquals(count + 1, updates.size)
				verify(3, 8)
			}
		} finally {
			terminal.close()
		}
	}
}
