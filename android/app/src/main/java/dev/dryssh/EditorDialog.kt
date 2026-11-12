package dev.dryssh

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun EditorDialog(session: SshService, dismiss: () -> Unit) {
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
	val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
	AlertDialog(
		onDismissRequest = dismiss,
		title = { Text("Local editor") },
		text = {
			Column {
				AndroidView(
					modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
					factory = { context ->
						EditText(context).apply {
							inputType =
								InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
							imeOptions =
								EditorInfo.IME_FLAG_NO_EXTRACT_UI or
								EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
							gravity = Gravity.TOP or Gravity.START
							typeface = Typeface.MONOSPACE
							setTextColor(textColor)
							setTextSize(16f)
							setText(draft)
							setSelection(text.length)
							doAfterTextChanged { session.updateDraft(it.toString()) }
							requestFocus()
						}
					},
					update = { if (it.text.toString() != draft) it.setText(draft) }
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
				TextButton(onClick = { session.updateDraft("") }) { Text("Clear draft") }
			}
		},
		confirmButton = {
			Column {
				TextButton(onClick = {
					if (session.pasteDraft()) dismiss() else refused = true
				}, enabled = connected && draft.isNotEmpty() && (!multiline || protectedPaste)) {
					Text("Paste")
				}
				if (multiline && !protectedPaste) {
					TextButton(onClick = {
						if (session.pasteDraft(plain = true)) dismiss() else refused = true
					}, enabled = connected && draft.isNotEmpty()) { Text("Plain paste") }
				}
			}
		},
		dismissButton = { TextButton(onClick = dismiss) { Text("Keep draft") } }
	)
}
