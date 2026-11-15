package dev.dryssh

import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class SpeechInsertion(start: Int, end: Int) {
	val start = minOf(start, end)
	var end = maxOf(start, end)
		private set
	fun update(transcript: String, replace: (Int, Int, String) -> Unit) {
		replace(start, end, transcript)
		end = start + transcript.length
	}
}

internal enum class SpeechPhase { Idle, Recording, Finishing }

internal class EditorSpeechInput(
	private val scope: CoroutineScope,
	private val engine: SpeechEngine,
	private val apiKey: suspend () -> String,
	private val applyText: (String) -> Unit
) {
	var phase by mutableStateOf(SpeechPhase.Idle)
		private set
	var error by mutableStateOf<String?>(null)
		private set
	val busy get() = phase != SpeechPhase.Idle
	var actionPending by mutableStateOf(false)
		private set
	private var afterFinal: (() -> Unit)? = null
	private var stop = MutableStateFlow(false)
	private var job: Job? = null
	private var generation = 0

	fun start() {
		if (busy) return
		error = null
		stop = MutableStateFlow(false)
		phase = SpeechPhase.Recording
		val current = ++generation
		job = scope.launch {
			var completed = false
			try {
				engine.recognize(apiKey(), stop) { if (it.isNotEmpty()) applyText(it) }
				completed = true
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: Exception) {
				error = when (failure) {
					is java.io.IOException -> failure.message
					else -> "Voice input failed. Check microphone permission, network and API key."
				}
			} finally {
				if (current == generation) {
					phase = SpeechPhase.Idle
					val action = if (completed) afterFinal else null
					afterFinal = null
					actionPending = false
					action?.invoke()
				}
			}
		}
	}

	fun finish() {
		if (phase != SpeechPhase.Recording) return
		phase = SpeechPhase.Finishing
		stop.value = true
	}

	fun finishThen(action: () -> Unit) {
		if (!busy) {
			action()
		} else if (!actionPending) {
			afterFinal = action
			actionPending = true
			finish()
		}
	}

	fun cancel() {
		generation++
		afterFinal = null
		actionPending = false
		job?.cancel()
		job = null
		phase = SpeechPhase.Idle
	}

	fun permissionDenied() {
		error = "Microphone permission is required for voice input."
	}
}

internal class AltSpeechKey(
	private val scope: CoroutineScope,
	private val start: () -> Unit,
	private val finish: () -> Unit,
	private val holdMillis: Long = ViewConfiguration.getLongPressTimeout().toLong()
) {
	private var key: Int? = null
	private var pending: Job? = null
	private var started = false

	fun handle(event: KeyEvent): Boolean {
		val alt =
			event.keyCode == KeyEvent.KEYCODE_ALT_LEFT || event.keyCode == KeyEvent.KEYCODE_ALT_RIGHT
		if (!alt) {
			if (event.action == KeyEvent.ACTION_DOWN) reset()
			return false
		}
		if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && key == null &&
			!event.isCtrlPressed && !event.isShiftPressed && !event.isMetaPressed
		) {
			key = event.keyCode
			pending = scope.launch {
				delay(holdMillis)
				started = true
				start()
			}
		} else if (event.action == KeyEvent.ACTION_UP && key == event.keyCode) {
			reset()
		}
		return true
	}

	fun reset() {
		pending?.cancel()
		pending = null
		key = null
		if (started) finish()
		started = false
	}
}
