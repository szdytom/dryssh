package dev.dryssh

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class TerminalTheme(
	val id: String,
	val name: String,
	val foreground: Long,
	val background: Long,
	val ansi: List<Long>
) {
	fun matches(settings: JSONObject): Boolean =
		foreground == settings.getLong("foreground") && background == settings.getLong("background") &&
			ansi == (0..15).map { settings.getJSONArray("ansi").getLong(it) }

	fun applyTo(settings: JSONObject) {
		settings.put("theme", id).put("foreground", foreground).put("background", background)
			.put("ansi", JSONArray(ansi))
	}

	companion object {
		fun fromSettings(id: String, name: String, settings: JSONObject) = TerminalTheme(
			id,
			name,
			settings.getLong("foreground"),
			settings.getLong("background"),
			(0..15).map { settings.getJSONArray("ansi").getLong(it) }
		)

		fun load(context: Context): List<TerminalTheme> {
			val defaults = JSONObject(NativeBridge.normalizeConfig("{}")).getJSONObject("settings")
			val themes = JSONArray(context.assets.open("themes.json").bufferedReader().use { it.readText() })
			return listOf(fromSettings("dryssh", "DrySSH", defaults)) + (0 until themes.length()).map {
				val item = themes.getJSONObject(it)
				fromSettings(item.getString("id"), item.getString("name"), item)
			}
		}

		fun selected(themes: List<TerminalTheme>, settings: JSONObject): TerminalTheme =
			themes.firstOrNull { it.id == settings.optString("theme") && it.matches(settings) }
				?: themes.firstOrNull { it.matches(settings) }
				?: fromSettings("", "Saved colors", settings)
	}
}

@Composable
fun ThemePreview(theme: TerminalTheme) {
	Column(Modifier.fillMaxWidth().background(Color(theme.background.toInt())).padding(12.dp)) {
		Text(
			"user@host ~ $ ls",
			color = Color(theme.foreground.toInt()),
			fontFamily = FontFamily.Monospace
		)
		for (colors in theme.ansi.chunked(8)) {
			Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
				colors.forEach { color ->
					Box(Modifier.weight(1f).height(8.dp).background(Color(color.toInt())))
				}
			}
		}
	}
}

@Composable
fun ThemePicker(
	themes: List<TerminalTheme>,
	selected: TerminalTheme,
	dismiss: () -> Unit,
	choose: (TerminalTheme) -> Unit
) {
	var query by remember { mutableStateOf("") }
	val filtered = themes.filter { it.name.contains(query.trim(), ignoreCase = true) }
	SettingsPanel("Terminal theme", dismiss, dismissLabel = "Back") {
		val focus = LocalFocusManager.current
		val keyboard = LocalSoftwareKeyboardController.current
		OutlinedTextField(
			query,
			{
				query = it
			},
			label = { Text("Search themes") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
			keyboardActions = KeyboardActions(onSearch = {
				focus.clearFocus()
				keyboard?.hide()
			})
		)
		LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			if (filtered.isEmpty()) item { Text("No matching themes", Modifier.padding(vertical = 16.dp)) }
			items(filtered, key = { it.id }) { theme ->
				Column(
					Modifier.fillMaxWidth().selectable(
						theme.id == selected.id,
						role = Role.RadioButton,
						onClick = { choose(theme) }
					).padding(vertical = 4.dp)
				) {
					Row {
						RadioButton(theme.id == selected.id, onClick = null)
						Text(theme.name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
					}
					ThemePreview(theme)
				}
			}
		}
	}
}
