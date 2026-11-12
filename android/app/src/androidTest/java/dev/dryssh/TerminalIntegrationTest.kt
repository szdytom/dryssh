package dev.dryssh

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalIntegrationTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val output = ByteArrayOutputStream()
	private val copied = mutableListOf<String>()
	private val terminal: TerminalEmulator = TerminalEmulatorFactory.create(
		looper = Looper.getMainLooper(),
		onKeyboardInput = { synchronized(output) { output.write(it) } },
		onClipboardCopy = { copied.add(it) }
	)

	@After
	fun close() {
		terminal.close()
	}

	private fun awaitOutput(expected: String) {
		val deadline = System.nanoTime() + 5_000_000_000
		while (System.nanoTime() < deadline) {
			instrumentation.waitForIdleSync()
			if (synchronized(output) { output.toString("UTF-8") } == expected) return
			Thread.sleep(10)
		}
		assertEquals(expected, synchronized(output) { output.toString("UTF-8") })
	}

	@Test
	fun bridgeLoadsOnDevice() {
		assertEquals("0.1.0", NativeBridge.version())
	}

	@Test
	fun pasteHonorsNegotiatedModeAndNeverAddsReturn() {
		terminal.writeInput("\u001b[?2004h".toByteArray())
		terminal.pasteText("中文\nsecond line")
		awaitOutput("\u001b[200~中文\nsecond line\u001b[201~")
		synchronized(output) { output.reset() }
		terminal.writeInput("\u001b[?2004l".toByteArray())
		terminal.pasteText("中文")
		awaitOutput("中文")
	}

	@Test
	fun controlAndAltCharactersUseTerminalEncoder() {
		terminal.dispatchCharacter(4, 'x'.code)
		terminal.dispatchCharacter(2, 'a'.code)
		awaitOutput("\u0018\u001ba")
	}

	@Test
	fun osc52DecodesUtf8AndResizeReachesNativeCore() {
		terminal.writeInput("\u001b]52;c;5Lit5paH\u0007".toByteArray())
		val deadline = System.nanoTime() + 5_000_000_000
		while (copied.isEmpty() && System.nanoTime() < deadline) {
			instrumentation.waitForIdleSync()
			Thread.sleep(10)
		}
		assertEquals(listOf("中文"), copied)
		terminal.resize(31, 97)
		assertEquals(31, terminal.dimensions.rows)
		assertEquals(97, terminal.dimensions.columns)
		terminal.writeInput("https://example.org/中文".toByteArray())
		val urlDeadline = System.nanoTime() + 5_000_000_000
		while (terminal.getUrls().isEmpty() && System.nanoTime() < urlDeadline) {
			instrumentation.waitForIdleSync()
			Thread.sleep(10)
		}
		assertTrue(terminal.getUrls().any { it.url.startsWith("https://example.org/") })
	}
}
