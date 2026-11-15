package dev.dryssh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoSpeechTest {
	private fun response(text: String?, flags: Int = 1, compressed: Boolean = true): ByteArray {
		val json = JSONObject().put(
			"result",
			JSONObject().apply {
				text?.let { put("text", it) }
			}
		).toString().toByteArray()
		val payload = if (compressed) {
			val output = ByteArrayOutputStream()
			GZIPOutputStream(output).use { it.write(json) }
			output.toByteArray()
		} else {
			json
		}
		val hasSequence = flags and 1 != 0
		return ByteBuffer.allocate((if (hasSequence) 12 else 8) + payload.size)
			.put(byteArrayOf(0x11, (0x90 or flags).toByte(), (if (compressed) 0x11 else 0x10).toByte(), 0))
			.apply { if (hasSequence) putInt(if (flags and 2 != 0) -1 else 1) }
			.putInt(payload.size).put(payload).array()
	}

	private fun payload(frame: ByteArray) =
		GZIPInputStream(frame.copyOfRange(8, frame.size).inputStream()).use {
			it.readBytes()
		}

	@Test fun configurationEnablesTwoPassAndFullTranscriptWithPcm16k() {
		val frame = DoubaoProtocol.configuration()
		assertEquals(0x10, frame[1].toInt())
		val json = JSONObject(payload(frame).toString(Charsets.UTF_8))
		assertTrue(json.getJSONObject("request").getBoolean("enable_nonstream"))
		assertEquals("full", json.getJSONObject("request").getString("result_type"))
		assertEquals(16000, json.getJSONObject("audio").getInt("rate"))
		assertEquals(16, json.getJSONObject("audio").getInt("bits"))
		assertEquals(1, json.getJSONObject("audio").getInt("channel"))
	}

	@Test fun arbitraryAudioFramesRoundtripAndMarkLastPacket() {
		val random = Random(42)
		repeat(100) {
			val pcm = ByteArray(random.nextInt(6401)).also(random::nextBytes)
			for (last in listOf(false, true)) {
				val frame = DoubaoProtocol.audio(pcm, last)
				assertEquals(if (last) 0x22 else 0x20, frame[1].toInt())
				assertEquals(frame.size - 8, ByteBuffer.wrap(frame, 4, 4).int)
				assertArrayEquals(pcm, payload(frame))
			}
		}
	}

	@Test fun acceptsPartialFinalAndUnsequencedResponsesWithOrWithoutGzip() {
		for (compressed in listOf(false, true)) {
			for (flags in 0..3) {
				val result = DoubaoProtocol.parse(response("你好，world。", flags, compressed))
				assertEquals("你好，world。", result.text)
				assertEquals(flags and 2 != 0, result.final)
			}
		}
		assertEquals("", DoubaoProtocol.parse(response("", 3)).text)
		assertEquals(null, DoubaoProtocol.parse(response(null, 3)).text)
	}

	@Test fun rejectsTruncatedInvalidAndServiceErrorPackets() {
		val valid = response("test")
		for (length in 0 until valid.size) {
			assertThrows(Exception::class.java) { DoubaoProtocol.parse(valid.copyOf(length)) }
		}
		assertThrows(IllegalArgumentException::class.java) {
			DoubaoProtocol.parse(valid.clone().apply { this[0] = 0x10 })
		}
		val error = ByteBuffer.allocate(
			12
		).put(byteArrayOf(0x11, 0xf0.toByte(), 0, 0)).putInt(45000001).putInt(0).array()
		assertEquals(
			"Speech service error 45000001",
			assertThrows(IOException::class.java) {
				DoubaoProtocol.parse(error)
			}.message
		)
	}

	@Test fun websocketStreamsAudioAndWaitsForSecondPassAfterStop() = runBlocking {
		MockWebServer().use { server ->
			val last = CompletableDeferred<WebSocket>()
			val audioReceived = CompletableDeferred<Unit>()
			val frames = mutableListOf<ByteArray>()
			server.enqueue(
				MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
					override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
						val frame = bytes.toByteArray()
						frames.add(frame)
						when (frame[1].toInt()) {
							0x20 -> {
								webSocket.send(response("测试中").toByteString())
								audioReceived.complete(Unit)
							}
							0x22 -> last.complete(webSocket)
						}
					}
				})
			)
			val stop = MutableStateFlow(false)
			val partial = CompletableDeferred<Unit>()
			val results = mutableListOf<String>()
			val client = OkHttpClient()
			try {
				val engine = DoubaoSpeechEngine({ stopping ->
					flow {
						emit(byteArrayOf(1, 2, 3, 4))
						stopping.first { it }
						emit(byteArrayOf(5, 6))
					}
				}, client, server.url("/speech").toString())
				val task = async {
					engine.recognize("test-secret", stop) {
						results.add(it)
						partial.complete(Unit)
					}
				}
				withTimeout(5000) {
					audioReceived.await()
					partial.await()
				}
				stop.value = true
				val socket = withTimeout(5000) { last.await() }
				assertFalse(task.isCompleted)
				socket.send(response("测试完成。", 3).toByteString())
				withTimeout(5000) { task.await() }
				assertEquals("测试完成。", results.last())
				assertEquals(listOf(0x10, 0x20, 0x20, 0x22), frames.map { it[1].toInt() })
				assertArrayEquals(byteArrayOf(5, 6), payload(frames[2]))
				assertArrayEquals(byteArrayOf(), payload(frames.last()))
				val request = server.takeRequest(1, TimeUnit.SECONDS)!!
				assertEquals("test-secret", request.getHeader("X-Api-Key"))
				assertEquals("volc.seedasr.sauc.duration", request.getHeader("X-Api-Resource-Id"))
				assertTrue(request.getHeader("X-Api-Request-Id")!!.isNotBlank())
			} finally {
				client.dispatcher.executorService.shutdown()
				client.connectionPool.evictAll()
			}
		}
	}

	@Test fun disconnectAndFinalTimeoutFailAndReleaseAudio() = runBlocking {
		for (disconnect in listOf(false, true)) {
			MockWebServer().use { server ->
				val released = CompletableDeferred<Unit>()
				server.enqueue(
					MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
						override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
							if (disconnect) webSocket.close(1000, "gone")
						}
					})
				)
				val client = OkHttpClient()
				try {
					val engine = DoubaoSpeechEngine({
						flow {
							try {
								emit(byteArrayOf(0, 0))
							} finally {
								released.complete(Unit)
							}
						}
					}, client, server.url("/").toString(), finalTimeout = 100)
					var failed = false
					try {
						withTimeout(5000) { engine.recognize("test", MutableStateFlow(false)) {} }
					} catch (_: IOException) {
						failed = true
					}
					assertTrue(failed)
					assertTrue(released.isCompleted)
				} finally {
					client.dispatcher.executorService.shutdown()
					client.connectionPool.evictAll()
				}
			}
		}
	}

	@Test fun cancellationReleasesAudioWhileConnecting() = runBlocking {
		MockWebServer().use { server ->
			server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
			val started = CompletableDeferred<Unit>()
			val released = CompletableDeferred<Unit>()
			val client = OkHttpClient()
			try {
				val engine = DoubaoSpeechEngine({
					flow {
						try {
							started.complete(Unit)
							kotlinx.coroutines.awaitCancellation()
						} finally {
							released.complete(Unit)
						}
					}
				}, client, server.url("/").toString())
				val task = async(Dispatchers.Default) { engine.recognize("test", MutableStateFlow(false)) {} }
				withTimeout(5000) { started.await() }
				task.cancelAndJoin()
				assertTrue(released.isCompleted)
			} finally {
				client.dispatcher.executorService.shutdown()
				client.connectionPool.evictAll()
			}
		}
	}

	@Test fun connectionCheckUsesTheSelectedResourceWithoutRecordingAudio() = runBlocking {
		MockWebServer().use { server ->
			val client = OkHttpClient()
			try {
				for (resource in SpeechResource.entries) {
					server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
					val engine = DoubaoSpeechEngine(
						{ error("Connection checks must not record audio") },
						client,
						server.url("/").toString(),
						resource = resource
					)
					withTimeout(5000) { engine.checkConnection("saved-key") }
					val request = server.takeRequest(1, TimeUnit.SECONDS)!!
					assertEquals(resource.id, request.getHeader("X-Api-Resource-Id"))
					assertEquals("saved-key", request.getHeader("X-Api-Key"))
				}
			} finally {
				client.dispatcher.executorService.shutdown()
				client.connectionPool.evictAll()
			}
		}
	}

	@Test fun forbiddenResponseIncludesResourceAndServiceReasonButRedactsTheKey() = runBlocking {
		MockWebServer().use { server ->
			val client = OkHttpClient()
			try {
				for (resource in SpeechResource.entries) {
					val reason = "resource permission denied for secret-key"
					val body = when (resource) {
						SpeechResource.SeedHourly -> JSONObject().put(
							"error",
							JSONObject().put("message", reason)
						).toString()
						SpeechResource.SeedConcurrent -> JSONObject().put("message", reason).toString()
						SpeechResource.BigHourly -> JSONObject().put("error", reason).toString()
						SpeechResource.BigConcurrent -> reason
					}
					server.enqueue(
						MockResponse().setResponseCode(403).setHeader("X-Tt-Logid", "service-log-id")
							.setBody(body)
					)
					val engine =
						DoubaoSpeechEngine(
							client = client,
							endpoint = server.url("/").toString(),
							resource = resource
						)
					var failure: IOException? = null
					try {
						withTimeout(5000) { engine.checkConnection("secret-key") }
					} catch (
						error: IOException
					) {
						failure = error
					}
					val message = failure!!.message!!
					assertTrue(message.contains("HTTP 403"))
					assertTrue(message.contains(resource.label))
					assertTrue(message.contains("resource permission denied"))
					assertTrue(message.contains("service-log-id"))
					assertFalse(message.contains("secret-key"))
				}
			} finally {
				client.dispatcher.executorService.shutdown()
				client.connectionPool.evictAll()
			}
		}
	}

	@Test fun recordingReportsHandshakeErrorsAndReleasesTheMicrophone() = runBlocking {
		MockWebServer().use { server ->
			server.enqueue(
				MockResponse().setResponseCode(
					403
				).setHeader("X-Api-Message", "selected resource not activated")
			)
			val client = OkHttpClient()
			val started = CompletableDeferred<Unit>()
			val released = CompletableDeferred<Unit>()
			try {
				val engine = DoubaoSpeechEngine({
					flow {
						try {
							started.complete(Unit)
							kotlinx.coroutines.awaitCancellation()
						} finally {
							released.complete(Unit)
						}
					}
				}, client, server.url("/").toString())
				var failure: IOException? = null
				try {
					withTimeout(5000) { engine.recognize("secret", MutableStateFlow(false)) {} }
				} catch (
					error: IOException
				) {
					failure = error
				}
				assertTrue(failure!!.message!!.contains("selected resource not activated"))
				assertTrue(started.isCompleted)
				assertTrue(released.isCompleted)
			} finally {
				client.dispatcher.executorService.shutdown()
				client.connectionPool.evictAll()
			}
		}
	}
}
