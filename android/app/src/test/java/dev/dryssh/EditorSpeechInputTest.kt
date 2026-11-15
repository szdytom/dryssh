package dev.dryssh

import java.io.IOException
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSpeechInputTest {
	@Test fun replacementPreservesSurroundingTextAcrossRepeatedCorrections() {
		val random = Random(27)
		val symbols = listOf("中", "文", "A", "\n", " ", "🎙️")
		repeat(200) {
			fun text() = (0..random.nextInt(30)).joinToString("") { symbols[random.nextInt(symbols.size)] }
			val prefix = text()
			val replaced = text()
			val suffix = text()
			val insertion = SpeechInsertion(prefix.length + replaced.length, prefix.length)
			var draft = prefix + replaced + suffix
			repeat(5) {
				val transcript = text()
				insertion.update(transcript) { start, end, text ->
					draft = draft.replaceRange(start, end, text)
				}
				assertEquals(prefix + transcript + suffix, draft)
				assertEquals(prefix.length + transcript.length, insertion.end)
			}
		}
	}

	@Test fun stopWaitsForFinalCorrectionAndRepeatedStartDoesNotCreateAnotherSession() = runTest {
		var starts = 0
		val texts = mutableListOf<String>()
		val speech = EditorSpeechInput(
			this,
			SpeechEngine { _, stop, onText ->
				starts++
				onText("临时")
				stop.first { it }
				onText("最终。")
			},
			{ "key" },
			texts::add
		)
		speech.start()
		speech.start()
		runCurrent()
		assertEquals(SpeechPhase.Recording, speech.phase)
		speech.finish()
		speech.start()
		assertEquals(SpeechPhase.Finishing, speech.phase)
		runCurrent()
		assertEquals(listOf("临时", "最终。"), texts)
		assertEquals(1, starts)
		assertFalse(speech.busy)
	}

	@Test fun cancellingAndImmediatelyRestartingCannotBeResetByOldSession() = runTest {
		var releases = 0
		val speech = EditorSpeechInput(
			this,
			SpeechEngine { _, _, _ ->
				try {
					awaitCancellation()
				} finally {
					releases++
				}
			},
			{ "key" },
			{}
		)
		speech.start()
		runCurrent()
		speech.cancel()
		speech.start()
		runCurrent()
		assertEquals(1, releases)
		assertEquals(SpeechPhase.Recording, speech.phase)
		speech.cancel()
		runCurrent()
		assertEquals(2, releases)
		assertEquals(SpeechPhase.Idle, speech.phase)
	}

	@Test fun errorsReturnToIdleAndPermissionDenialDoesNotStartRecording() = runTest {
		var starts = 0
		val speech = EditorSpeechInput(
			this,
			SpeechEngine { _, _, _ ->
				starts++
				throw IOException("Speech service error 45000001")
			},
			{ "key" },
			{}
		)
		speech.permissionDenied()
		assertEquals(0, starts)
		assertTrue(speech.error!!.contains("permission"))
		speech.start()
		runCurrent()
		assertFalse(speech.busy)
		assertEquals("Speech service error 45000001", speech.error)
	}

	@Test fun silenceLeavesTheSelectedDraftTextIntact() = runTest {
		var draft = "selected text"
		val speech =
			EditorSpeechInput(this, SpeechEngine { _, _, onText -> onText("") }, { "key" }, {
				draft =
					it
			})
		speech.start()
		runCurrent()
		assertEquals("selected text", draft)
		assertFalse(speech.busy)
	}

	@Test fun pendingActionRunsOnceAfterFinalTextIncludingWhenAlreadyFinishing() = runTest {
		for (alreadyFinishing in listOf(false, true)) {
			val final = CompletableDeferred<Unit>()
			val events = mutableListOf<String>()
			val speech = EditorSpeechInput(
				this,
				SpeechEngine { _, stop, onText ->
					onText("partial")
					stop.first { it }
					final.await()
					onText("corrected")
				},
				{ "key" },
				events::add
			)
			speech.start()
			runCurrent()
			if (alreadyFinishing) speech.finish()
			speech.finishThen { events.add("paste: ${events.last()}") }
			speech.finishThen { events.add("duplicate") }
			runCurrent()
			assertTrue(speech.actionPending)
			assertEquals(SpeechPhase.Finishing, speech.phase)
			assertEquals(listOf("partial"), events)
			final.complete(Unit)
			runCurrent()
			assertEquals(listOf("partial", "corrected", "paste: corrected"), events)
			assertFalse(speech.busy)
			assertFalse(speech.actionPending)
		}
	}

	@Test fun failureOrCancellationDropsPendingActionAndAllowsTheNextSession() = runTest {
		for (cancel in listOf(false, true)) {
			var actions = 0
			val speech = EditorSpeechInput(
				this,
				SpeechEngine { _, stop, _ ->
					stop.first { it }
					throw IOException("final result unavailable")
				},
				{ "key" },
				{}
			)
			speech.start()
			runCurrent()
			speech.finishThen { actions++ }
			if (cancel) speech.cancel()
			runCurrent()
			assertEquals(0, actions)
			assertFalse(speech.busy)
			assertFalse(speech.actionPending)
			speech.start()
			runCurrent()
			speech.finish()
			runCurrent()
			assertEquals(0, actions)
		}
	}

	@Test fun idleActionsRunImmediately() = runTest {
		var actions = 0
		val speech =
			EditorSpeechInput(
				this,
				SpeechEngine { _, _, _ ->
					error("No recognition requested")
				},
				{ "key" },
				{}
			)
		speech.finishThen { actions++ }
		assertEquals(1, actions)
		assertFalse(speech.actionPending)
	}
}
