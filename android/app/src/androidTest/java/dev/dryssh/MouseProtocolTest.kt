package dev.dryssh

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseProtocolTest {
	@Test fun mouseAndAlternateScrollFollowNegotiatedModes() {
		val output = ByteArrayOutputStream()
		TerminalEmulatorFactory.create(onKeyboardInput = {
			synchronized(output) { output.write(it) }
		}).use { terminal ->
			fun mode(sequence: String) {
				terminal.writeInput(sequence.toByteArray())
				terminal.pasteTextIfBracketed("")
			}
			fun emitted(): String {
				InstrumentationRegistry.getInstrumentation().waitForIdleSync()
				return synchronized(output) { output.toString("UTF-8").also { output.reset() } }
			}
			assertFalse(terminal.pointerInput(2, 3))
			assertFalse(terminal.pointerInput(2, 3, 1))
			assertEquals("", emitted())
			mode("\u001b[?1000h\u001b[?1006h")
			assertTrue(terminal.pointerInput(2, 3))
			assertEquals("\u001b[<0;4;3M\u001b[<0;4;3m", emitted())
			assertTrue(terminal.pointerInput(2, 3, 2))
			assertEquals("\u001b[<64;4;3M\u001b[<64;4;3M", emitted())
			assertTrue(terminal.pointerInput(2, 3, -1))
			assertEquals("\u001b[<65;4;3M", emitted())
			mode("\u001b[?1000l\u001b[?1049h")
			assertFalse(terminal.pointerInput(2, 3))
			assertTrue(terminal.pointerInput(2, 3, 2))
			assertEquals("\u001b[A\u001b[A", emitted())
			mode("\u001b[?1h")
			assertTrue(terminal.pointerInput(2, 3, -1))
			assertEquals("\u001bOB", emitted())
			mode("\u001b[?1007l")
			assertFalse(terminal.pointerInput(2, 3, 1))
			assertEquals("", emitted())
			mode("\u001b[?1049l\u001bc")
			assertFalse(terminal.pointerInput(2, 3, 1))
			assertEquals("", emitted())
		}
	}
}
