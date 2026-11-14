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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
		window.useImmersiveScreen()
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
	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) window.hideSystemBars()
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
	val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
	var showActions by remember { mutableStateOf(false) }
	var showOptions by remember { mutableStateOf(false) }
	val snackbar = remember { SnackbarHostState() }
	LaunchedEffect(state.state, state.detail, landscape) {
		if (landscape &&
			(
				state.state == "idle" && state.detail !in listOf("Disconnected", "Connecting") || state.state in
					listOf(
						"authentication_failed",
						"host_key_failed",
						"waiting_wlan",
						"waiting_network",
						"retry_pending"
					)
				)
		) {
			snackbar.showSnackbar(state.detail)
		}
	}
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
	val viewport: @Composable (Modifier) -> Unit = { viewportModifier ->
		Terminal(
			terminalEmulator = session.terminal,
			modifier = viewportModifier.testTag("terminal"),
			onTerminalLongPress = { showMenu = true },
			localHistory = localHistory,
			onSelectionControllerAvailable = { selection = it },
			onScrollControllerAvailable = { scroll = it },
			onPasteRequest = ::pasteClipboard,
			keyboardEnabled = true,
			showSoftKeyboard = keyboard,
			initialFontSize = settings.getDouble("font_size").toFloat().sp,
			contentInsetPx = settings.getInt("terminal_inset"),
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
	}
	fun returnToLive() {
		localHistory = false
		scroll?.scrollToBottom()
		selection?.clearSelection()
	}
	val auxiliary: @Composable (Boolean) -> Unit = { menu ->
		val sendKey: (Int) -> Unit = {
			session.terminal.dispatchKey(0, it)
			showActions = false
		}
		if (menu) {
			DropdownMenuItem(text = { Text("Esc") }, onClick = { sendKey(VTermKey.ESCAPE) })
			DropdownMenuItem(text = { Text("Tab") }, onClick = { sendKey(VTermKey.TAB) })
			DropdownMenuItem(text = { Text("Ctrl-C") }, onClick = {
				session.terminal.dispatchCharacter(4, 'c'.code)
				showActions =
					false
			})
		} else {
			TextButton(onClick = { sendKey(VTermKey.ESCAPE) }) { Text("Esc") }
			TextButton(onClick = { sendKey(VTermKey.TAB) }) { Text("Tab") }
			TextButton(onClick = { session.terminal.dispatchCharacter(4, 'c'.code) }) { Text("Ctrl-C") }
		}
		val actions = settings.getJSONArray("quick_actions")
		for (i in 0 until actions.length()) {
			val action = actions.getJSONObject(i)
			if (action.getString("name") == "Editor" &&
				action.getJSONObject("action").getString("kind") == "editor"
			) {
				continue
			}
			val perform = {
				TerminalActions.perform(action.getJSONObject("action"), session.terminal) {
					showEditor =
						true
				}
				showActions = false
			}
			if (menu) {
				DropdownMenuItem(text = { Text(action.getString("name")) }, onClick = perform)
			} else {
				TextButton(onClick = perform) { Text(action.getString("name")) }
			}
		}
	}
	Surface(Modifier.fillMaxSize()) {
		Box(Modifier.fillMaxSize()) {
			SessionLayout(
				landscape = landscape,
				controls = { bounds, geometry ->
					SafeRail(bounds, geometry) {
						ToolIcon(
							TerminalIcons.Connect,
							"Connect",
							state.state == "connected",
							Modifier.semantics {
								stateDescription =
									"${state.name}: ${state.detail}"
							}
						) { showConnect = true }
						ToolIcon(TerminalIcons.Editor, "Editor") { showEditor = true }
						ToolIcon(TerminalIcons.Keyboard, "Keyboard", keyboard) { keyboard = !keyboard }
						Box {
							ToolIcon(TerminalIcons.Actions, "Quick actions") { showActions = true }
							DropdownMenu(expanded = showActions, onDismissRequest = {
								showActions = false
							}) { auxiliary(true) }
						}
						ToolIcon(TerminalIcons.Settings, "Settings") { showSettings = true }
						Box {
							ToolIcon(TerminalIcons.More, "Session options") { showOptions = true }
							DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
								Text(
									state.name,
									Modifier.padding(horizontal = 16.dp),
									style = MaterialTheme.typography.titleSmall
								)
								Text(state.detail, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
								if (fontResult.isFailure) {
									Text(
										"Cannot load imported font. Select another font in Settings.",
										Modifier.padding(16.dp),
										color = MaterialTheme.colorScheme.error
									)
								}
								DropdownMenuItem(text = {
									Text(if (stayAwake) "Allow sleep" else "Stay awake")
								}, leadingIcon = { Icon(TerminalIcons.Awake, null) }, onClick = {
									stayAwake =
										!stayAwake
									showOptions = false
								})
								DropdownMenuItem(text = { Text("Terminal menu") }, onClick = {
									showMenu = true
									showOptions =
										false
								})
								if (state.state !=
									"idle"
								) {
									DropdownMenuItem(text = {
										Text("Disconnect")
									}, leadingIcon = { Icon(TerminalIcons.Disconnect, null) }, onClick = {
										session.disconnect()
										showOptions =
											false
									})
								}
							}
						}
						if (localHistory) ToolIcon(TerminalIcons.Live, "Return to live terminal") { returnToLive() }
					}
				},
				header = { bounds, geometry ->
					SafeHeader(
						bounds,
						geometry,
						buildList {
							add(
								EdgeAction(TerminalIcons.Connect, "Connect", state.state == "connected") {
									showConnect =
										true
								}
							)
							add(EdgeAction(TerminalIcons.Editor, "Editor") { showEditor = true })
							if (state.state !=
								"idle"
							) {
								add(EdgeAction(TerminalIcons.Disconnect, "Disconnect") { session.disconnect() })
							}
						}
					) {
						Column(Modifier.padding(horizontal = 12.dp)) {
							Text(
								state.name,
								style = MaterialTheme.typography.titleSmall,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
							Text(
								state.detail,
								style = MaterialTheme.typography.labelSmall,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
					}
				},
				footer = { bounds, geometry ->
					SafeFooter(
						bounds,
						geometry,
						buildList {
							if (localHistory) {
								add {
									TextButton(onClick = ::returnToLive) { Text("Return to live terminal") }
								}
							}
							if (fontResult.isFailure) {
								add {
									Text(
										"Cannot load imported font. Select another font in Settings.",
										color = MaterialTheme.colorScheme.error,
										maxLines = 2,
										overflow = TextOverflow.Ellipsis
									)
								}
							}
							if (!hardwareKeyboard || keyboard) {
								add {
									Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { auxiliary(false) }
								}
							}
							add {
								Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
									ToolIcon(TerminalIcons.Settings, "Settings") { showSettings = true }
									ToolIcon(TerminalIcons.Keyboard, "Keyboard", keyboard) { keyboard = !keyboard }
									ToolIcon(TerminalIcons.Awake, if (stayAwake) "Allow sleep" else "Stay awake", stayAwake) {
										stayAwake =
											!stayAwake
									}
								}
							}
						}
					)
				},
				terminal = viewport
			)
			SafeWindowRegion {
				Box(Modifier.fillMaxSize()) { SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) }
			}
		}
	}

	if (showMenu) {
		ScreenAlertDialog(onDismissRequest = {
			showMenu = false
		}, title = { Text("Terminal menu") }, text = {
			Column(Modifier.verticalScroll(rememberScrollState())) {
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
	if (showEditor) {
		EditorDialog(session, fontResult.getOrDefault(Typeface.MONOSPACE)) {
			showEditor =
				false
		}
	}
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
		ScreenAlertDialog(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolIcon(
	icon: ImageVector,
	label: String,
	active: Boolean = false,
	modifier: Modifier = Modifier,
	click: () -> Unit
) {
	TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = {
		PlainTooltip { Text(label) }
	}, state = rememberTooltipState()) {
		IconButton(onClick = click, modifier = modifier.size(48.dp)) {
			Icon(
				icon,
				contentDescription = label,
				modifier = Modifier.size(22.dp),
				tint = if (active) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				}
			)
		}
	}
}
