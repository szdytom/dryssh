package dev.dryssh

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneTest {
	@Test fun microphoneProducesPcmAndCanBeReopenedAfterCancellation() = runBlocking {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		instrumentation.uiAutomation.grantRuntimePermission(
			instrumentation.targetContext.packageName,
			Manifest.permission.RECORD_AUDIO
		)
		repeat(2) {
			val frames = withTimeout(5000) { microphonePcm(MutableStateFlow(false)).take(2).toList() }
			assertEquals(2, frames.size)
			assertTrue(frames.all { it.size == 6400 })
		}
	}
}
