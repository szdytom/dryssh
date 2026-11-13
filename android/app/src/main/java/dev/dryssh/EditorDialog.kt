package dev.dryssh

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun EditorDialog(session: SshService, typeface: Typeface, dismiss: () -> Unit) {
	val draft by session.draft.collectAsState()
	val state by session.state.collectAsState()
	var protectedPaste by remember { mutableStateOf(session.terminal.bracketedPasteEnabled) }
	var refused by remember { mutableStateOf(false) }
	val owner = LocalLifecycleOwner.current
	LaunchedEffect(session, owner) {
		owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
			while (isActive) {
				protectedPaste = session.terminal.bracketedPasteEnabled
				delay(100)
			}
		}
	}
	val multiline = draft.any { it == '\n' || it == '\r' }
	val connected = state.state == "connected"
	val canPaste = connected && draft.isNotEmpty() && (!multiline || protectedPaste)
	val paste: (Boolean) -> Unit = { plain ->
		if (session.pasteDraft(plain)) dismiss() else refused = true
	}
	val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
	ScreenDialog(onDismissRequest = dismiss) {
		Surface(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
				.widthIn(max = 640.dp).fillMaxWidth(),
			shape = MaterialTheme.shapes.extraLarge,
			tonalElevation = 6.dp
		) {
			Column(Modifier.heightIn(max = 440.dp).padding(12.dp).testTag("local-editor")) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text("Local editor", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
					TextButton(onClick = { session.updateDraft("") }) { Text("Clear draft") }
				}
				AndroidView(
					modifier = Modifier.weight(1f).fillMaxWidth().testTag("local-editor-input"),
					factory = { context ->
						object : EditText(context) {
							override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
								super.onWindowFocusChanged(hasWindowFocus)
								if (hasWindowFocus) requestFocus()
							}
						}.apply {
							inputType =
								InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
							imeOptions =
								EditorInfo.IME_FLAG_NO_EXTRACT_UI or
								EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
							gravity = Gravity.TOP or Gravity.START
							setTextColor(textColor)
							setTextSize(16f)
							setText(draft)
							setSelection(text.length)
							doAfterTextChanged { session.updateDraft(it.toString()) }
							requestFocus()
						}
					},
					update = { editor ->
						editor.typeface = typeface
						if (editor.text.toString() != draft) editor.setText(draft)
						editor.setOnKeyListener { _, keyCode, event ->
							when {
								keyCode == KeyEvent.KEYCODE_ESCAPE -> {
									if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) dismiss()
									true
								}
								keyCode == KeyEvent.KEYCODE_ENTER && event.isShiftPressed -> {
									if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && canPaste) {
										paste(false)
									}
									true
								}
								else -> false
							}
						}
					}
				)
				if (multiline && !protectedPaste) {
					Text("Protected paste is off. Draft kept. Plain paste may run multiple lines.")
				}
				if (refused) {
					Text(
						"Paste was not sent. The connection or paste mode changed; draft kept."
					)
				}
				if (!connected) Text("Connect to a server to paste this draft.")
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
					TextButton(onClick = dismiss) { Text("Keep draft") }
					if (multiline && !protectedPaste) {
						TextButton(onClick = { paste(true) }, enabled = connected && draft.isNotEmpty()) {
							Text("Plain paste")
						}
					}
					TextButton(onClick = { paste(false) }, enabled = canPaste) { Text("Paste") }
				}
			}
		}
	}
}
