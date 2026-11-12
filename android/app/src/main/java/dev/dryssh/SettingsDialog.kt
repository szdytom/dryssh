package dev.dryssh

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SettingsDialog(repository: AppRepository, dismiss: () -> Unit) {
	val context = LocalContext.current
	val focus = LocalFocusManager.current
	val keyboard = LocalSoftwareKeyboardController.current
	val scope = rememberCoroutineScope()
	var settings by remember {
		mutableStateOf(JSONObject(repository.config.value).getJSONObject("settings").toString())
	}
	val current = JSONObject(settings)
	var page by remember { mutableStateOf("Appearance") }
	var error by remember { mutableStateOf<String?>(null) }
	var busy by remember { mutableStateOf(false) }
	var edit by remember { mutableStateOf<Pair<String, Int>?>(null) }
	val imported = remember { mutableStateListOf<File>() }
	val colorValidity = remember { mutableStateMapOf<String, Boolean>() }
	fun change(update: (JSONObject) -> Unit) {
		settings = JSONObject(settings).also(update).toString()
	}
	DisposableEffect(Unit) {
		onDispose {
			val kept = JSONObject(repository.config.value).getJSONObject("settings").optString("font_ref")
			imported.filter { it.name != kept }.forEach { it.delete() }
		}
	}
	val importFont = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		if (uri != null) {
			busy = true
			scope.launch {
				try {
					val file = File(repository.fonts, UUID.randomUUID().toString())
					imported.add(file)
					withContext(Dispatchers.IO) {
						context.contentResolver.openInputStream(uri)!!.use { input ->
							file.writeBytes(input.readBounded(32 * 1024 * 1024))
						}
						Typeface.createFromFile(file)
					}
					change { it.put("font_ref", file.name) }
					error = null
				} catch (
					failure: Exception
				) {
					error = "Cannot import font: ${failure.message}"
				} finally {
					busy = false
				}
			}
		}
	}
	SettingsPanel(
		"Settings",
		dismiss,
		"Save settings",
		!busy && colorValidity.values.all {
			it
		},
		save = {
			busy = true
			scope.launch {
				try {
					withContext(Dispatchers.IO) {
						repository.update(JSONObject(repository.config.value).put("settings", JSONObject(settings)))
					}
					dismiss()
				} catch (failure: Exception) {
					error = failure.message
				} finally {
					busy = false
				}
			}
		}
	) {
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row {
				listOf("Appearance", "Keys", "Actions").forEach { title ->
					TextButton(onClick = {
						focus.clearFocus()
						keyboard?.hide()
						colorValidity.clear()
						page = title
					}) { Text(title) }
				}
			}
			Column(
				Modifier.weight(1f).verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				when (page) {
					"Appearance" -> {
						Text("Font size: ${current.getDouble("font_size").toInt()} sp")
						Slider(
							current.getDouble("font_size").toFloat(),
							{ size ->
								change { it.put("font_size", size.toInt()) }
							},
							valueRange =
							6f..40f,
							steps = 33
						)
						TextButton(onClick = { importFont.launch("*/*") }, enabled = !busy) { Text("Import font") }
						Text(if (current.isNull("font_ref")) "System monospace" else "Imported font")
						TextButton(onClick = {
							change { it.put("font_ref", JSONObject.NULL) }
						}) { Text("Use system font") }
						ColorField("Foreground", current.getLong("foreground"), {
							colorValidity["foreground"] = it
						}) { color ->
							change { it.put("foreground", color) }
						}
						ColorField("Background", current.getLong("background"), {
							colorValidity["background"] = it
						}) { color ->
							change { it.put("background", color) }
						}
						Text("ANSI palette · #RRGGBB")
						for (i in 0..15) {
							ColorField("ANSI $i", current.getJSONArray("ansi").getLong(i), {
								colorValidity["ansi$i"] = it
							}) { color ->
								change { it.getJSONArray("ansi").put(i, color) }
							}
						}
					}
					else -> {
						val kind = if (page == "Keys") "bindings" else "quick_actions"
						val items = current.getJSONArray(kind)
						Text(
							if (page ==
								"Keys"
							) {
								"Mappings replace terminal keys. Use lowercase letters, digits, f1–f12, escape, tab, enter, arrows, home, end, insert, delete, backspace, page_up, page_down or space."
							} else {
								"Actions appear on the auxiliary bar. Text actions use the negotiated paste mode."
							}
						)
						for (i in 0 until items.length()) {
							val item = items.getJSONObject(i)
							Row(Modifier.fillMaxWidth()) {
								TextButton(modifier = Modifier.weight(1f), onClick = { edit = kind to i }) {
									Text(
										if (kind ==
											"bindings"
										) {
											shortcutLabel(item)
										} else {
											item.getString("name")
										}
									)
								}
								TextButton(onClick = { change { it.getJSONArray(kind).remove(i) } }) { Text("Remove") }
							}
						}
						TextButton(onClick = { edit = kind to items.length() }) {
							Text(
								if (page ==
									"Keys"
								) {
									"Add mapping"
								} else {
									"Add action"
								}
							)
						}
					}
				}
				error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
			}
		}
	}

	edit?.let { (kind, index) ->
		ActionDialog(kind, current.getJSONArray(kind).optJSONObject(index), { edit = null }) { item ->
			val candidate = JSONObject(settings).also { it.getJSONArray(kind).put(index, item) }
			NativeBridge.normalizeConfig(
				JSONObject(repository.config.value).put("settings", candidate).toString()
			)
			settings = candidate.toString()
			edit = null
		}
	}
}

fun shortcutLabel(binding: JSONObject): String {
	val modifiers = binding.getJSONObject("modifiers")
	return listOf("ctrl", "alt", "shift").filter {
		modifiers.getBoolean(it)
	}.joinToString(
		"+",
		postfix = if (listOf("ctrl", "alt", "shift").any {
				modifiers.getBoolean(it)
			}
		) {
			"+"
		} else {
			""
		}
	) +
		binding.getString("key")
}

@Composable
private fun ColorField(
	label: String,
	color: Long,
	validity: (Boolean) -> Unit,
	changed: (Long) -> Unit
) {
	var value by remember(color) { mutableStateOf("%06X".format(color and 0xffffff)) }
	OutlinedTextField(value, {
		value = it.removePrefix("#")
		validity(value.matches(Regex("[0-9a-fA-F]{6}")))
		if (value.matches(Regex("[0-9a-fA-F]{6}"))) changed(0xff000000L or value.toLong(16))
	}, label = { Text(label) }, singleLine = true, isError = !value.matches(Regex("[0-9a-fA-F]{6}")))
}

@Composable
private fun ActionDialog(
	kind: String,
	original: JSONObject?,
	dismiss: () -> Unit,
	save: (JSONObject) -> Unit
) {
	var name by remember { mutableStateOf(original?.optString("name") ?: "") }
	var key by remember { mutableStateOf(original?.optString("key") ?: "") }
	var ctrl by remember {
		mutableStateOf(original?.optJSONObject("modifiers")?.getBoolean("ctrl") ?: false)
	}
	var alt by remember {
		mutableStateOf(original?.optJSONObject("modifiers")?.getBoolean("alt") ?: false)
	}
	var shift by remember {
		mutableStateOf(original?.optJSONObject("modifiers")?.getBoolean("shift") ?: false)
	}
	val action = original?.getJSONObject("action")
	var type by remember { mutableStateOf(action?.getString("kind") ?: "editor") }
	var value by remember {
		mutableStateOf(
			action?.optString(
				when (type) {
					"control" -> "character"
					"key" -> "key"
					else -> "text"
				}
			) ?: ""
		)
	}
	var error by remember { mutableStateOf<String?>(null) }
	SettingsPanel(
		if (kind ==
			"bindings"
		) {
			"Key mapping"
		} else {
			"Quick action"
		},
		dismiss,
		"Apply action",
		true,
		save = {
			try {
				val result = JSONObject()
				if (kind ==
					"bindings"
				) {
					result.put(
						"key",
						key
					).put("modifiers", JSONObject().put("ctrl", ctrl).put("alt", alt).put("shift", shift))
				} else {
					result.put("name", name)
				}
				val output = JSONObject().put("kind", type)
				if (type !=
					"editor"
				) {
					output.put(
						when (type) {
							"control" -> "character"
							"key" -> "key"
							else -> "text"
						},
						value
					)
				}
				save(result.put("action", output))
			} catch (failure: Exception) {
				error = failure.message
			}
		}
	) {
		Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
			if (kind == "bindings") {
				OutlinedTextField(key, {
					key = it.lowercase()
				}, label = { Text("Input key") }, singleLine = true)
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text("Ctrl")
					Switch(ctrl, {
						ctrl =
							it
					})
				}
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text("Alt")
					Switch(alt, {
						alt =
							it
					})
				}
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text("Shift")
					Switch(shift, {
						shift =
							it
					})
				}
			} else {
				OutlinedTextField(name, { name = it }, label = { Text("Action name") }, singleLine = true)
			}
			for (choice in listOf("editor", "control", "key", "text")) {
				TextButton(onClick = {
					type = choice
					value =
						""
				}) { Text(if (type == choice) "✓ $choice" else choice) }
			}
			if (type !=
				"editor"
			) {
				OutlinedTextField(value, { value = it }, label = {
					Text(
						when (type) {
							"control" -> "Control character"
							"key" -> "Output key"
							else -> "Paste text"
						}
					)
				})
			}
			error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
		}
	}
}

@Composable
private fun SettingsPanel(
	title: String,
	dismiss: () -> Unit,
	saveLabel: String,
	enabled: Boolean,
	save: () -> Unit,
	content: @Composable ColumnScope.() -> Unit
) {
	Dialog(
		onDismissRequest = dismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
	) {
		Surface(Modifier.fillMaxSize()) {
			Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp)) {
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
					TextButton(onClick = dismiss) { Text("Cancel") }
					TextButton(enabled = enabled, onClick = save) { Text(saveLabel) }
				}
				content()
			}
		}
	}
}
