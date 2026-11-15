package dev.dryssh

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun EditorDialog(
	session: SshService,
	typeface: Typeface,
	engine: SpeechEngine? = null,
	dismiss: () -> Unit
) {
	val draft by session.draft.collectAsState()
	val state by session.state.collectAsState()
	val config by session.repository.config.collectAsState()
	val voiceEnabled = !JSONObject(config).getJSONObject("settings").isNull("speech_api_key_ref")
	val resourceId = JSONObject(config).getJSONObject("settings").getString("speech_resource_id")
	val configuredEngine = remember(resourceId, engine) {
		engine
			?: DoubaoSpeechEngine(resource = SpeechResource.fromId(resourceId))
	}
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var input by remember { mutableStateOf<EditText?>(null) }
	var insertion by remember { mutableStateOf<SpeechInsertion?>(null) }
	var applyingSpeech by remember { mutableStateOf(false) }
	var preserveSelection by remember { mutableStateOf(false) }
	val speech = remember(session, configuredEngine) {
		EditorSpeechInput(scope, configuredEngine, {
			withContext(Dispatchers.IO) { session.repository.speechApiKey() }
		}) { text ->
			input?.let { editor ->
				insertion?.let { target ->
					applyingSpeech = true
					target.update(text) { start, end, transcript -> editor.text.replace(start, end, transcript) }
					if (!preserveSelection) editor.setSelection(target.end)
					applyingSpeech = false
				}
			}
		}
	}
	fun beginSpeech() {
		if (!speech.busy) {
			input?.let { editor ->
				insertion = SpeechInsertion(editor.selectionStart, editor.selectionEnd)
				preserveSelection = false
				speech.start()
			}
		}
	}
	var permissionPending by remember { mutableStateOf(false) }
	val permission =
		rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			if (permissionPending) {
				permissionPending = false
				if (granted) beginSpeech() else speech.permissionDenied()
			}
		}
	fun requestSpeech() {
		if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
			PackageManager.PERMISSION_GRANTED
		) {
			beginSpeech()
		} else if (!permissionPending) {
			permissionPending = true
			permission.launch(Manifest.permission.RECORD_AUDIO)
		}
	}
	val alt = remember(speech) {
		AltSpeechKey(scope, { if (!speech.busy) requestSpeech() }, {
			permissionPending = false
			speech.finish()
		})
	}
	var protectedPaste by remember { mutableStateOf(session.terminal.bracketedPasteEnabled) }
	var refused by remember { mutableStateOf(false) }
	val owner = LocalLifecycleOwner.current
	DisposableEffect(owner, speech) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_STOP) {
				permissionPending = false
				alt.reset()
				speech.cancel()
			}
		}
		owner.lifecycle.addObserver(observer)
		onDispose {
			permissionPending = false
			alt.reset()
			speech.cancel()
			owner.lifecycle.removeObserver(observer)
		}
	}
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
	val canPaste = !speech.actionPending && connected && (draft.isNotEmpty() || speech.busy) &&
		(!multiline || protectedPaste)
	val keep: () -> Unit = { speech.finishThen(dismiss) }
	val paste: (Boolean) -> Unit = { plain ->
		speech.finishThen {
			if (session.pasteDraft(plain)) dismiss() else refused = true
		}
	}
	val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
	ScreenDialog(onDismissRequest = keep) {
		Surface(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
				.widthIn(max = 640.dp).fillMaxWidth(),
			shape = MaterialTheme.shapes.extraLarge,
			tonalElevation = 6.dp
		) {
			Column(Modifier.heightIn(max = 440.dp).padding(12.dp).testTag("local-editor")) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text("Local editor", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
					if (voiceEnabled) {
						IconButton(
							onClick = { if (speech.busy) speech.finish() else requestSpeech() },
							enabled = speech.phase != SpeechPhase.Finishing && !permissionPending
						) {
							Icon(
								TerminalIcons.Microphone,
								if (speech.busy) "Stop voice input" else "Start voice input",
								tint = if (speech.busy) {
									MaterialTheme.colorScheme.error
								} else {
									MaterialTheme.colorScheme.onSurface
								}
							)
						}
					}
					TextButton(
						onClick = { speech.finishThen { session.updateDraft("") } },
						enabled = !speech.actionPending
					) { Text("Clear draft") }
				}
				AndroidView(
					modifier = Modifier.weight(1f).fillMaxWidth().testTag("local-editor-input"),
					factory = { context ->
						object : EditText(context) {
							override fun onTouchEvent(event: MotionEvent): Boolean {
								if (event.actionMasked == MotionEvent.ACTION_DOWN && speech.busy) {
									preserveSelection = true
									speech.finish()
								}
								return super.onTouchEvent(event)
							}
							override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
								super.onWindowFocusChanged(hasWindowFocus)
								if (hasWindowFocus) {
									requestFocus()
								} else {
									alt.reset()
								}
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
							input = this
							doAfterTextChanged {
								if (!applyingSpeech) speech.cancel()
								session.updateDraft(it.toString())
							}
							requestFocus()
						}
					},
					update = { editor ->
						editor.typeface = typeface
						if (editor.text.toString() != draft) editor.setText(draft)
						editor.setOnKeyListener { _, keyCode, event ->
							when {
								voiceEnabled && alt.handle(event) -> true
								keyCode == KeyEvent.KEYCODE_ESCAPE -> {
									if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) keep()
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
				when (speech.phase) {
					SpeechPhase.Recording -> Text("Listening… Click the microphone to stop, or release Alt.")
					SpeechPhase.Finishing -> Text("Finishing recognition…")
					SpeechPhase.Idle -> speech.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
				}
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
					TextButton(onClick = keep, enabled = !speech.actionPending) { Text("Keep draft") }
					if (multiline && !protectedPaste) {
						TextButton(
							onClick = { paste(true) },
							enabled =
							!speech.actionPending && connected && (draft.isNotEmpty() || speech.busy)
						) {
							Text("Plain paste")
						}
					}
					TextButton(onClick = { paste(false) }, enabled = canPaste) { Text("Paste") }
				}
			}
		}
	}
}
