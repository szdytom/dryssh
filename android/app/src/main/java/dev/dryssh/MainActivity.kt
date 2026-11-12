package dev.dryssh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey
import org.json.JSONObject

class MainActivity : ComponentActivity() {
	private var service by mutableStateOf<SshService?>(null)
	private val notificationPermission =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
	private val binding = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName, binder: IBinder) {
			service = (binder as SshService.LocalBinder).service
		}
		override fun onServiceDisconnected(name: ComponentName) {
			service = null
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			MaterialTheme(
				colorScheme = darkColorScheme(
					background = Color(0xFF101A25),
					surface = Color(0xFF182433),
					primary = Color(0xFF8AC6DB)
				)
			) {
				service?.let { session ->
					SessionScreen(session, background = { moveTaskToBack(true) }, connect = {
						if (Build.VERSION.SDK_INT >=
							33
						) {
							notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
						}
						session.connect(it)
					})
				}
			}
		}
	}
	override fun onStart() {
		super.onStart()
		bindService(Intent(this, SshService::class.java), binding, Context.BIND_AUTO_CREATE)
	}
	override fun onStop() {
		unbindService(binding)
		service = null
		super.onStop()
	}
}

@Composable
private fun SessionScreen(
	session: SshService,
	background: () -> Unit,
	connect: (ConnectionRequest) -> Unit
) {
	val state by session.state.collectAsState()
	val configuration = LocalConfiguration.current
	val hardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
		configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
	var keyboard by remember(hardwareKeyboard) { mutableStateOf(!hardwareKeyboard) }
	var showConnect by remember { mutableStateOf(false) }
	var stayAwake by remember { mutableStateOf(true) }
	val view = LocalView.current
	DisposableEffect(state.state, stayAwake) {
		view.keepScreenOn = state.state == "connected" && stayAwake
		onDispose { view.keepScreenOn = false }
	}
	BackHandler { background() }
	Surface(Modifier.fillMaxSize()) {
		Column(Modifier.safeDrawingPadding()) {
			Row(
				Modifier.fillMaxWidth().padding(horizontal = 12.dp),
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
					Text(state.name, style = MaterialTheme.typography.titleMedium)
					Text(state.detail, style = MaterialTheme.typography.labelSmall, maxLines = 2)
				}
				TextButton(onClick = { showConnect = true }) { Text("Connect") }
				if (state.state !=
					"idle"
				) {
					TextButton(onClick = session::disconnect) { Text("Disconnect") }
				}
			}
			Terminal(
				terminalEmulator = session.terminal,
				modifier = Modifier.weight(1f).fillMaxWidth(),
				keyboardEnabled = true,
				showSoftKeyboard = keyboard,
				initialFontSize = 14.sp,
				backgroundColor = Color(0xFF101A25),
				foregroundColor = Color(0xFFD4DFE9)
			)
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
				if (!hardwareKeyboard || keyboard) {
					TextButton(onClick = {
						session.terminal.dispatchKey(0, VTermKey.ESCAPE)
					}) { Text("Esc") }
					TextButton(onClick = {
						session.terminal.dispatchKey(0, VTermKey.TAB)
					}) { Text("Tab") }
					TextButton(onClick = {
						session.terminal.dispatchCharacter(4, 'c'.code)
					}) { Text("Ctrl-C") }
				}
				TextButton(onClick = { keyboard = !keyboard }) { Text("Keyboard") }
				TextButton(onClick = {
					stayAwake = !stayAwake
				}) { Text(if (stayAwake) "Stay awake" else "Allow sleep") }
			}
		}
	}
	if (showConnect) {
		ConnectDialog(
			dismiss = { showConnect = false },
			connect = {
				showConnect = false
				connect(it)
			}
		)
	}
	state.hostKey?.let { key ->
		AlertDialog(
			onDismissRequest = { session.trust(false) },
			title = { Text("Trust this host?") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(
						"Verify this fingerprint with your server before connecting. It will be saved for future connections."
					)
					SelectionContainer {
						Text("${key.algorithm}\n${key.fingerprint}", fontFamily = FontFamily.Monospace)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { session.trust(true) }) { Text("Trust and connect") }
			},
			dismissButton = { TextButton(onClick = { session.trust(false) }) { Text("Cancel") } }
		)
	}
}

@Composable
private fun ConnectDialog(dismiss: () -> Unit, connect: (ConnectionRequest) -> Unit) {
	var host by remember { mutableStateOf("") }
	var port by remember { mutableStateOf("22") }
	var user by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	var autoReconnect by remember { mutableStateOf(true) }
	var wlanOnly by remember { mutableStateOf(false) }
	AlertDialog(
		onDismissRequest = dismiss,
		title = { Text("Connect to server") },
		text = {
			Column(
				Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true)
				OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true)
				OutlinedTextField(user, {
					user = it
				}, label = { Text("Username") }, singleLine = true)
				OutlinedTextField(password, {
					password = it
				}, label = {
					Text("Password")
				}, singleLine = true, visualTransformation = PasswordVisualTransformation())
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text("Auto reconnect")
					Switch(autoReconnect, { autoReconnect = it })
				}
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text("WLAN only")
					Switch(wlanOnly, { wlanOnly = it })
				}
			}
		},
		confirmButton = {
			Button(
				onClick = {
					connect(
						ConnectionRequest(
							"$user@$host",
							host.trim(),
							port.toInt(),
							user.trim(),
							JSONObject().put("kind", "password").put("password", password),
							autoReconnect,
							wlanOnly
						)
					)
				},
				enabled =
				host.isNotBlank() && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535
			) {
				Text("Connect")
			}
		},
		dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
	)
}
