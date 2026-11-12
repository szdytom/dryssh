package dev.dryssh

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Network
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.compose.ui.graphics.Color
import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulatorFactory
import org.json.JSONArray
import org.json.JSONObject

class SshService : Service() {
	inner class LocalBinder : Binder() {
		val service: SshService get() = this@SshService
	}
	data class HostKey(val algorithm: String, val fingerprint: String)
	data class UiState(
		val state: String = "idle",
		val detail: String = "Disconnected",
		val name: String = "DrySSH",
		val hostKey: HostKey? = null
	)
	private class Attempt(val generation: Long, val network: Network?) {
		var job: Job? = null

		@Volatile var nativeId = 0L
		private var cancelled = false
		private var socket: Socket? = null

		@Synchronized fun own(value: Socket) {
			if (cancelled) {
				value.close()
				throw CancellationException("Connection cancelled")
			}
			socket = value
		}

		@Synchronized fun close() {
			cancelled = true
			if (nativeId != 0L) NativeBridge.close(nativeId)
			socket?.close()
			job?.cancel()
		}
	}
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val mutableState = MutableStateFlow(UiState())
	val state = mutableState.asStateFlow()
	private var policy = ""
	private var policyState = "idle"
	private var request: ConnectionRequest? = null
	private var attempt: Attempt? = null
	private lateinit var network: NetworkAccess
	private lateinit var wakeLock: PowerManager.WakeLock
	private val trusted by lazy { getSharedPreferences("host-keys", MODE_PRIVATE) }
	val terminal by lazy {
		TerminalEmulatorFactory.create(
			defaultForeground = Color(0xFFD4DFE9),
			defaultBackground = Color(0xFF101A25),
			onKeyboardInput = ::send,
			onResize = ::resize,
			onClipboardCopy = {
				getSystemService(
					ClipboardManager::class.java
				).setPrimaryClip(ClipData.newPlainText("Terminal", it))
			}
		).also { it.setDefaultColors(0xFFD4DFE9.toInt(), 0xFF101A25.toInt()) }
	}

	override fun onCreate() {
		super.onCreate()
		wakeLock =
			getSystemService(
				PowerManager::class.java
			).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DrySSH:session").apply {
				setReferenceCounted(false)
			}
		getSystemService(
			NotificationManager::class.java
		).createNotificationChannel(
			NotificationChannel("session", "SSH connection", NotificationManager.IMPORTANCE_LOW)
		)
		val banner = "DrySSH ${NativeBridge.version()}\r\n\r\n" +
			"ASCII / 中文终端\r\nChoose Connect to open a shell.\r\n"
		terminal.writeInput(banner.toByteArray())
		network = NetworkAccess(
			this,
			changed = { online, wlan ->
				transition("network_changed", JSONObject().put("online", online).put("wlan", wlan))
			},
			lost = { lost ->
				attempt?.takeIf {
					it.network == lost
				}?.let { failed(it, "network", "WLAN connection lost") }
			}
		)
	}

	override fun onBind(intent: Intent): IBinder = LocalBinder()

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == "disconnect") disconnect()
		return START_NOT_STICKY
	}

	// The foreground session owns the wake lock until `disconnect`.
	@SuppressLint("WakelockTimeout")
	fun connect(value: ConnectionRequest) {
		terminal.clearScreen()
		request = value
		mutableState.value = UiState(name = value.name, detail = "Connecting")
		startService(Intent(this, SshService::class.java))
		startForeground(1, notification())
		if (!wakeLock.isHeld) wakeLock.acquire()
		try {
			network.retainWlan(value.wlanOnly)
		} catch (error: IOException) {
			disconnect()
			mutableState.value = state.value.copy(detail = error.message ?: "WLAN request failed")
			return
		}
		transition(
			"connect",
			JSONObject().put("auto_reconnect", value.autoReconnect).put("wlan_only", value.wlanOnly)
		)
	}

	fun disconnect() {
		transition("disconnect")
		mutableState.value = mutableState.value.copy(detail = "Disconnected", hostKey = null)
		finishForeground()
	}

	fun trust(accept: Boolean) {
		val key = state.value.hostKey ?: return
		val current = attempt ?: return
		if (accept) trusted.edit().putString(request!!.trustKey, key.fingerprint).apply()
		mutableState.value = state.value.copy(hostKey = null)
		NativeBridge.trust(current.nativeId, accept)
	}

	private fun transition(kind: String, data: Any? = null) {
		val event = JSONObject().put("kind", kind)
		if (data != null) event.put("data", data)
		val result = JSONObject(NativeBridge.transition(policy, event.toString()))
		policy = result.getJSONObject("policy").toString()
		val newState = result.getString("state")
		val previousState = policyState
		policyState = newState
		mutableState.value =
			state.value.copy(
				state = newState,
				hostKey = if (newState ==
					"connecting"
				) {
					state.value.hostKey
				} else {
					null
				}
			)
		val effects = result.getJSONArray("effects")
		for (i in 0 until effects.length()) {
			val effect = effects.getJSONObject(i)
			when (effect.getString("kind")) {
				"close_transport" -> {
					attempt?.close()
					attempt = null
				}
				"connect" -> startAttempt(effect.getLong("generation"))
				"schedule_retry" -> scope.launch {
					delay(2_000)
					transition("retry", effect.getLong("generation"))
				}
			}
		}
		if (newState ==
			"waiting_wlan"
		) {
			mutableState.value = state.value.copy(detail = "Waiting for WLAN")
		}
		if (newState ==
			"waiting_network"
		) {
			mutableState.value = state.value.copy(detail = "Waiting for network")
		}
		if (newState != previousState && newState in
			listOf("authentication_failed", "host_key_failed", "idle")
		) {
			finishForeground()
		}
		updateNotification()
	}

	private fun startAttempt(generation: Long) {
		val target = request!!
		val current = Attempt(generation, if (target.wlanOnly) network.selectedWlan() else null)
		attempt = current
		mutableState.value =
			state.value.copy(detail = "Connecting to ${target.host}:${target.port}", hostKey = null)
		current.job = scope.launch {
			try {
				val socket =
					withContext(Dispatchers.IO) {
						network.open(
							target.host,
							target.port,
							target.wlanOnly,
							current.network,
							current::own
						)
					}
				if (attempt !== current) {
					socket.close()
					return@launch
				}
				val dimensions = terminal.dimensions
				val options = JSONObject()
					.put("username", target.username)
					.put("authentication", target.authentication)
					.put(
						"trusted_fingerprint",
						trusted.getString(target.trustKey, null) ?: JSONObject.NULL
					)
					.put("columns", dimensions.columns).put("rows", dimensions.rows)
				current.nativeId =
					NativeBridge.start(
						ParcelFileDescriptor.fromSocket(socket).detachFd(),
						options.toString()
					)
				withContext(Dispatchers.IO) {
					while (true) {
						val frame = NativeBridge.nextEvent(current.nativeId) ?: break
						if (frame[0] == 0.toByte()) {
							withContext(Dispatchers.Main.immediate) {
								if (attempt ===
									current
								) {
									terminal.writeInput(frame, 1, frame.size - 1)
								}
							}
						} else {
							val control =
								JSONObject(String(frame, 1, frame.size - 1, Charsets.UTF_8))
							withContext(Dispatchers.Main.immediate) {
								if (attempt === current) control(current, control)
							}
							if (control.getString("kind") == "closed") break
						}
					}
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Exception) {
				if (attempt ===
					current
				) {
					failed(
						current,
						"network",
						"Connection failed: ${error.message ?: error.javaClass.simpleName}"
					)
				}
			} finally {
				current.close()
			}
		}
	}

	private fun control(current: Attempt, event: JSONObject) {
		when (event.getString("kind")) {
			"host_key" ->
				mutableState.value =
					state.value.copy(
						detail = "Verify host key",
						hostKey = HostKey(event.getString("algorithm"), event.getString("fingerprint"))
					)
			"connected" -> {
				mutableState.value = state.value.copy(detail = "Connected", hostKey = null)
				transition("connected", current.generation)
				resize(terminal.dimensions)
			}
			"closed" -> failed(
				current,
				when (event.getString("reason")) {
					"authentication" -> "authentication"
					"host_key" -> "host_key"
					else -> "network"
				},
				event.getString("detail")
			)
		}
		updateNotification()
	}

	private fun failed(current: Attempt, reason: String, detail: String) {
		mutableState.value = state.value.copy(detail = detail, hostKey = null)
		transition("failed", JSONArray().put(current.generation).put(reason))
	}

	private fun send(bytes: ByteArray) {
		val current = attempt ?: return
		if (state.value.state != "connected") return
		if (!NativeBridge.sendInput(current.nativeId, bytes)) {
			failed(current, "network", "Input could not be sent; connection closed")
		}
	}

	private fun resize(dimensions: TerminalDimensions) {
		val id = attempt?.nativeId ?: return
		if (id !=
			0L
		) {
			NativeBridge.resize(
				id,
				dimensions.columns,
				dimensions.rows,
				dimensions.widthPixels,
				dimensions.heightPixels
			)
		}
	}

	private fun notification(): Notification {
		val open = PendingIntent.getActivity(
			this,
			0,
			Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE
		)
		val disconnect = PendingIntent.getService(
			this,
			1,
			Intent(this, SshService::class.java).setAction("disconnect"),
			PendingIntent.FLAG_IMMUTABLE
		)
		return Notification.Builder(this, "session").setSmallIcon(dev.dryssh.R.drawable.ic_terminal)
			.setContentTitle(state.value.name).setContentText(state.value.detail)
			.setContentIntent(open).setOngoing(true)
			.addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build()).build()
	}
	private fun updateNotification() {
		if (wakeLock.isHeld) {
			getSystemService(
				NotificationManager::class.java
			).notify(1, notification())
		}
	}
	private fun finishForeground() {
		network.retainWlan(false)
		if (wakeLock.isHeld) wakeLock.release()
		stopForeground(STOP_FOREGROUND_REMOVE)
		stopSelf()
	}
	override fun onDestroy() {
		attempt?.close()
		scope.cancel()
		network.close()
		terminal.close()
		if (wakeLock.isHeld) wakeLock.release()
		super.onDestroy()
	}
}
