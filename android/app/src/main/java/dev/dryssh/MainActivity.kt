package dev.dryssh

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.connectbot.terminal.ScrollController
import org.connectbot.terminal.SelectionController
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
		bindService(Intent(this, SshService::class.java), binding, Context.BIND_AUTO_CREATE)
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
	override fun onDestroy() {
		unbindService(binding)
		service = null
		super.onDestroy()
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
	var showMenu by remember { mutableStateOf(false) }
	var localHistory by remember { mutableStateOf(false) }
	var selection by remember { mutableStateOf<SelectionController?>(null) }
	var scroll by remember { mutableStateOf<ScrollController?>(null) }
	val context = LocalContext.current
	var showConnect by remember { mutableStateOf(false) }
	var showSettings by remember { mutableStateOf(false) }
	val config by session.repository.config.collectAsState()
	val settings = remember(config) { JSONObject(config).getJSONObject("settings") }
	val fontResult = remember(settings.optString("font_ref")) {
		runCatching {
			if (settings.isNull(
					"font_ref"
				)
			) {
				Typeface.MONOSPACE
			} else {
				Typeface.createFromFile(
					File(session.repository.fonts, settings.getString("font_ref"))
				)
			}
		}
	}
	var showEditor by rememberSaveable { mutableStateOf(false) }
	var stayAwake by remember { mutableStateOf(true) }
	val view = LocalView.current
	DisposableEffect(state.state, stayAwake) {
		view.keepScreenOn = state.state == "connected" && stayAwake
		onDispose { view.keepScreenOn = false }
	}
	fun pasteClipboard() {
		val text =
			context.getSystemService(ClipboardManager::class.java).primaryClip?.let {
				if (it.itemCount >
					0
				) {
					it.getItemAt(0).coerceToText(context).toString()
				} else {
					""
				}
			}
				?: return
		if (text.any { it == '\n' || it == '\r' } && !session.terminal.bracketedPasteEnabled) {
			session.updateDraft(text)
			showEditor = true
		} else {
			session.terminal.pasteText(text)
		}
	}
	BackHandler {
		when {
			localHistory -> {
				localHistory = false
				scroll?.scrollToBottom()
				selection?.clearSelection()
			}
			selection?.isSelectionActive == true -> selection?.clearSelection()
			else -> background()
		}
	}
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
				TextButton(onClick = { showEditor = true }) { Text("Editor") }
				if (state.state !=
					"idle"
				) {
					TextButton(onClick = session::disconnect) { Text("Disconnect") }
				}
			}
			Terminal(
				terminalEmulator = session.terminal,
				modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal"),
				onTerminalLongPress = { showMenu = true },
				localHistory = localHistory,
				onSelectionControllerAvailable = { selection = it },
				onScrollControllerAvailable = { scroll = it },
				onPasteRequest = ::pasteClipboard,
				keyboardEnabled = true,
				showSoftKeyboard = keyboard,
				initialFontSize = settings.getDouble("font_size").toFloat().sp,
				typeface = fontResult.getOrDefault(Typeface.MONOSPACE),
				maxFontSize = 40.sp,
				backgroundColor = Color(settings.getLong("background").toInt()),
				foregroundColor = Color(settings.getLong("foreground").toInt()),
				onInterceptKey = { event ->
					val action = TerminalActions.matches(event.nativeKeyEvent, settings.getJSONArray("bindings"))
					if (action != null && !showEditor && !showConnect && !showSettings && !showMenu) {
						if (event.nativeKeyEvent.action ==
							KeyEvent.ACTION_DOWN
						) {
							TerminalActions.perform(action, session.terminal) { showEditor = true }
						}
						true
					} else {
						false
					}
				}
			)
			if (localHistory) {
				TextButton(onClick = {
					localHistory = false
					scroll?.scrollToBottom()
					selection?.clearSelection()
				}) { Text("Return to live terminal") }
			}
			if (fontResult.isFailure) {
				Text(
					"Cannot load imported font. Select another font in Settings.",
					color = MaterialTheme.colorScheme.error
				)
			}
			if (!hardwareKeyboard || keyboard) {
				Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
					TextButton(onClick = {
						session.terminal.dispatchKey(0, VTermKey.ESCAPE)
					}) { Text("Esc") }
					TextButton(onClick = {
						session.terminal.dispatchKey(0, VTermKey.TAB)
					}) { Text("Tab") }
					TextButton(onClick = {
						session.terminal.dispatchCharacter(4, 'c'.code)
					}) { Text("Ctrl-C") }
					val actions = settings.getJSONArray("quick_actions")
					for (i in 0 until actions.length()) {
						val action = actions.getJSONObject(i)
						if (action.getString("name") == "Editor" &&
							action.getJSONObject("action").getString("kind") == "editor"
						) {
							continue
						}
						TextButton(onClick = {
							TerminalActions.perform(action.getJSONObject("action"), session.terminal) {
								showEditor =
									true
							}
						}) { Text(action.getString("name")) }
					}
				}
			}
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
				TextButton(onClick = { showSettings = true }) { Text("Settings") }
				TextButton(onClick = { keyboard = !keyboard }) { Text("Keyboard") }
				TextButton(onClick = {
					stayAwake = !stayAwake
				}) { Text(if (stayAwake) "Stay awake" else "Allow sleep") }
			}
		}
	}
	if (showMenu) {
		AlertDialog(onDismissRequest = { showMenu = false }, title = { Text("Terminal menu") }, text = {
			Column {
				TextButton(
					onClick = {
						selection?.copySelection()
						showMenu = false
					},
					enabled =
					selection?.isSelectionActive == true
				) { Text("Copy selection") }
				TextButton(onClick = {
					pasteClipboard()
					showMenu = false
				}, enabled = state.state == "connected") { Text("Paste clipboard") }
				TextButton(onClick = {
					selection?.startSelection()
					showMenu = false
				}) { Text("Select text") }
				TextButton(onClick = {
					selection?.selectAll()
					showMenu = false
				}) { Text("Select all") }
				TextButton(onClick = {
					localHistory = true
					scroll?.scrollToTop()
					showMenu = false
				}) { Text("Local history") }
				TextButton(onClick = {
					showEditor = true
					showMenu = false
				}) { Text("Open local editor") }
			}
		}, confirmButton = { TextButton(onClick = { showMenu = false }) { Text("Close") } })
	}
	if (showSettings) SettingsDialog(session.repository) { showSettings = false }
	if (showEditor) EditorDialog(session) { showEditor = false }
	if (showConnect) {
		ConnectDialog(
			session = session,
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
