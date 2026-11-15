package dev.dryssh

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

@SuppressLint("MissingPermission")
internal fun microphonePcm(stop: StateFlow<Boolean>) = flow {
	val minimum = AudioRecord.getMinBufferSize(
		16000,
		AudioFormat.CHANNEL_IN_MONO,
		AudioFormat.ENCODING_PCM_16BIT
	)
	if (minimum <= 0) throw IOException("16 kHz microphone recording is unavailable")
	val recorder = AudioRecord.Builder()
		.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
		.setAudioFormat(
			AudioFormat.Builder().setSampleRate(16000)
				.setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
		)
		.setBufferSizeInBytes(maxOf(minimum, 12800)).build()
	try {
		if (recorder.state !=
			AudioRecord.STATE_INITIALIZED
		) {
			throw IOException("Cannot initialize microphone")
		}
		recorder.startRecording()
		if (recorder.recordingState !=
			AudioRecord.RECORDSTATE_RECORDING
		) {
			throw IOException("Cannot start microphone")
		}
		val chunk = ByteArray(6400)
		var filled = 0
		while (currentCoroutineContext().isActive && !stop.value) {
			val count = recorder.read(chunk, filled, chunk.size - filled, AudioRecord.READ_NON_BLOCKING)
			if (count < 0) throw IOException("Microphone recording failed")
			filled += count
			if (filled == chunk.size) {
				emit(chunk.copyOf())
				filled = 0
			} else {
				delay(10)
			}
		}
		if (filled > 0) emit(chunk.copyOf(filled))
	} finally {
		recorder.release()
	}
}.flowOn(Dispatchers.IO)
