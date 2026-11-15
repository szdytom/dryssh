package dev.dryssh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

internal data class SpeechResult(val text: String?, val final: Boolean)

internal object DoubaoProtocol {
	fun configuration(): ByteArray = frame(
		0x10,
		0x11,
		JSONObject()
			.put("user", JSONObject().put("uid", "dryssh"))
			.put(
				"audio",
				JSONObject().put("format", "pcm").put("codec", "raw")
					.put("rate", 16000).put("bits", 16).put("channel", 1)
			)
			.put(
				"request",
				JSONObject().put("model_name", "bigmodel")
					.put("result_type", "full").put("enable_nonstream", true)
					.put("enable_itn", true).put("enable_punc", true)
			)
			.toString().toByteArray()
	)

	fun audio(pcm: ByteArray, last: Boolean = false): ByteArray =
		frame(if (last) 0x22 else 0x20, 0x01, pcm)

	private fun frame(type: Int, encoding: Int, bytes: ByteArray): ByteArray {
		val output = ByteArrayOutputStream()
		GZIPOutputStream(output).use { it.write(bytes) }
		val payload = output.toByteArray()
		return ByteBuffer.allocate(8 + payload.size)
			.put(byteArrayOf(0x11, type.toByte(), encoding.toByte(), 0))
			.putInt(payload.size).put(payload).array()
	}

	fun parse(bytes: ByteArray): SpeechResult {
		require(bytes.size >= 8) { "Incomplete speech response" }
		val header = bytes[0].toInt() and 15
		require(bytes[0].toInt() ushr 4 == 1 && header >= 1) { "Invalid speech protocol" }
		val type = (bytes[1].toInt() and 255) ushr 4
		val flags = bytes[1].toInt() and 15
		val encoding = (bytes[2].toInt() and 255) ushr 4
		val compression = bytes[2].toInt() and 15
		val buffer = ByteBuffer.wrap(bytes)
		require(header * 4 <= bytes.size - 4) { "Incomplete speech header" }
		buffer.position(header * 4)
		if (type == 15) {
			throw IOException("Speech service error ${buffer.int}")
		}
		require(type == 9 && encoding == 1 && compression in 0..1) { "Unsupported speech response" }
		if (flags and 1 != 0) {
			require(buffer.remaining() >= 8) { "Incomplete speech sequence" }
			buffer.int
		}
		val length = buffer.int
		require(length >= 0 && length == buffer.remaining()) { "Invalid speech payload size" }
		val payload = ByteArray(length).also { buffer.get(it) }
		val decoded = if (compression == 1) {
			GZIPInputStream(payload.inputStream()).use { it.readBounded(1024 * 1024) }
		} else {
			payload
		}
		val json = JSONObject(decoded.toString(Charsets.UTF_8))
		val code = json.optInt("code", 0)
		if (code != 0 && code != 20000000) throw IOException("Speech service error $code")
		return SpeechResult(
			json.optJSONObject("result")?.let {
				if (it.has("text")) it.getString("text") else null
			},
			flags and 2 != 0
		)
	}
}

internal fun interface SpeechEngine {
	suspend fun recognize(apiKey: String, stop: StateFlow<Boolean>, onText: (String) -> Unit)
}

internal class DoubaoSpeechEngine(
	private val pcm: (StateFlow<Boolean>) -> Flow<ByteArray> = ::microphonePcm,
	private val client: OkHttpClient = sharedClient,
	private val endpoint: String = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
	private val finalTimeout: Long = 15_000,
	private val resource: SpeechResource = SpeechResource.SeedHourly
) : SpeechEngine {
	private fun request(apiKey: String) = Request.Builder().url(endpoint)
		.header("X-Api-Key", apiKey)
		.header("X-Api-Resource-Id", resource.id)
		.header("X-Api-Request-Id", UUID.randomUUID().toString())
		.header("X-Api-Sequence", "-1").build()

	private fun connectionError(response: Response?, apiKey: String): IOException {
		if (response == null) return IOException("Speech connection failed. Check your network.")
		val body = runCatching {
			response.body?.byteStream()?.use { it.readBounded(4096).toString(Charsets.UTF_8) }
		}.getOrNull().orEmpty()
		val detail = response.header("X-Api-Message") ?: runCatching {
			val json = JSONObject(body)
			json.optJSONObject("error")?.optString("message")
				?: json.optString("message", json.optString("msg", json.optString("error", body)))
		}.getOrDefault(body)
		fun redact(value: String) = value.replace(apiKey, "[redacted]").take(512)
		return IOException(
			buildString {
				append("Speech connection failed (HTTP ${response.code}). ${resource.label}.")
				if (response.code == 401 || response.code == 403) {
					append(
						" Check the Speech-console API key and enable the matching model/billing resource in Volcengine; select it in Settings → Voice."
					)
				}
				if (detail.isNotBlank()) append("\n${redact(detail)}")
				response.header("X-Tt-Logid")?.let { append("\nLog ID: ${redact(it)}") }
			}
		)
	}

	suspend fun checkConnection(apiKey: String) {
		val opened = CompletableDeferred<Unit>()
		val socket = client.newWebSocket(
			request(apiKey),
			object : WebSocketListener() {
				override fun onOpen(webSocket: WebSocket, response: Response) {
					opened.complete(Unit)
				}
				override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
					opened.completeExceptionally(connectionError(response, apiKey))
				}
			}
		)
		try {
			withTimeoutOrNull(10_000) { opened.await() }
				?: throw IOException("Timed out connecting to the speech service")
		} finally {
			socket.cancel()
		}
	}

	override suspend fun recognize(
		apiKey: String,
		stop: StateFlow<Boolean>,
		onText: (String) -> Unit
	) = coroutineScope {
		val opened = CompletableDeferred<WebSocket>()
		val responses = Channel<ByteArray>(64)
		val audio = Channel<ByteArray>(50)
		val socket = client.newWebSocket(
			request(apiKey),
			object : WebSocketListener() {
				override fun onOpen(webSocket: WebSocket, response: Response) {
					opened.complete(webSocket)
				}
				override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
					if (!responses.trySend(bytes.toByteArray()).isSuccess) {
						responses.close(IOException("Speech responses arrived too quickly"))
					}
				}
				override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
					webSocket.close(code, null)
					responses.close(IOException("Speech connection closed before the final result"))
				}
				override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
					val error = connectionError(response, apiKey)
					opened.completeExceptionally(error)
					responses.close(error)
				}
			}
		)
		val recording = launch {
			try {
				pcm(stop).collect { audio.send(it) }
			} finally {
				audio.close()
			}
		}
		val sending = launch(Dispatchers.IO) {
			withTimeoutOrNull(10_000) { opened.await() }
				?: throw IOException("Timed out connecting to the speech service")
			fun send(frame: ByteArray) {
				if (!socket.send(frame.toByteString())) throw IOException("Speech connection is unavailable")
			}
			send(DoubaoProtocol.configuration())
			for (chunk in audio) send(DoubaoProtocol.audio(chunk))
			send(DoubaoProtocol.audio(byteArrayOf(), last = true))
		}
		val timeout = launch {
			sending.join()
			delay(finalTimeout)
			responses.close(IOException("Timed out waiting for the final speech result"))
		}
		try {
			for (bytes in responses) {
				val result = DoubaoProtocol.parse(bytes)
				result.text?.let(onText)
				if (result.final) break
			}
		} finally {
			recording.cancel()
			sending.cancel()
			timeout.cancel()
			audio.cancel()
			responses.cancel()
			socket.cancel()
		}
	}

	companion object {
		private val sharedClient = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
	}
}
