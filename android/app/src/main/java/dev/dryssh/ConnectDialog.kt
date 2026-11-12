package dev.dryssh

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun ConnectDialog(session: SshService, dismiss: () -> Unit, connect: (ConnectionRequest) -> Unit) {
	val repository = session.repository
	val config by repository.config.collectAsState()
	val profiles = JSONObject(config).getJSONArray("profiles")
	val scope = rememberCoroutineScope()
	val context = LocalContext.current
	var selected by remember { mutableStateOf<JSONObject?>(null) }
	var name by remember { mutableStateOf("") }
	var host by remember { mutableStateOf("") }
	var port by remember { mutableStateOf("22") }
	var user by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	var privateKey by remember { mutableStateOf<String?>(null) }
	var passphrase by remember { mutableStateOf("") }
	var keyAuth by remember { mutableStateOf(false) }
	var autoReconnect by remember { mutableStateOf(true) }
	var wlanOnly by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf(repository.loadError) }
	var busy by remember { mutableStateOf(false) }
	fun select(profile: JSONObject?) {
		selected = profile
		name = profile?.getString("name") ?: ""
		host = profile?.getString("host") ?: ""
		port = (profile?.getInt("port") ?: 22).toString()
		user = profile?.getString("username") ?: ""
		keyAuth = profile?.getString("authentication") == "private_key"
		autoReconnect = profile?.getBoolean("auto_reconnect") ?: true
		wlanOnly = profile?.getBoolean("wlan_only") ?: false
		password = ""
		privateKey = null
		passphrase = ""
		error = null
	}
	fun runOperation(operation: suspend () -> Unit) {
		busy = true
		scope.launch {
			try {
				operation()
			} catch (
				failure: Exception
			) {
				error = failure.message ?: "Operation failed"
			} finally {
				busy = false
			}
		}
	}
	val importKey = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		if (uri != null) {
			runOperation {
				privateKey = withContext(Dispatchers.IO) {
					val bytes = context.contentResolver.openInputStream(uri)!!.use {
						it.readNBytes(1024 * 1024 + 1)
					}
					require(bytes.size <= 1024 * 1024) { "Key file is too large" }
					bytes.toString(Charsets.UTF_8).also {
						require(it.contains("PRIVATE KEY")) { "Select a private key file" }
					}
				}
			}
		}
	}
	fun secret(): JSONObject? = when {
		keyAuth && privateKey != null -> JSONObject().put("kind", "private_key").put("key", privateKey)
			.put("passphrase", passphrase.ifEmpty { null } ?: JSONObject.NULL)
		!keyAuth && password.isNotEmpty() -> JSONObject().put(
			"kind",
			"password"
		).put("password", password)
		keyAuth && passphrase.isNotEmpty() -> throw IllegalArgumentException(
			"Reimport the private key to change its passphrase"
		)
		selected?.getString("authentication") == (if (keyAuth) "private_key" else "password") -> null
		!keyAuth -> JSONObject().put("kind", "password").put("password", "")
		else -> throw IllegalArgumentException("Import a private key first")
	}
	fun profile() = JSONObject().put("id", selected?.getString("id") ?: UUID.randomUUID().toString())
		.put(
			"name",
			name.trim().ifEmpty {
				"$user@$host"
			}
		).put("host", host.trim()).put("port", port.toInt())
		.put("username", user.trim()).put("authentication", if (keyAuth) "private_key" else "password")
		.put("auto_reconnect", autoReconnect).put("wlan_only", wlanOnly)
	val valid =
		!busy && host.isNotBlank() && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535
	AlertDialog(onDismissRequest = dismiss, title = { Text("Connect to server") }, text = {
		Column(
			Modifier.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			for (i in 0 until profiles.length()) {
				val item = profiles.getJSONObject(i)
				Row(Modifier.fillMaxWidth()) {
					TextButton(
						onClick = {
							runOperation { connect(withContext(Dispatchers.IO) { repository.connection(item) }) }
						},
						enabled = !busy,
						modifier = Modifier.weight(1f)
					) { Text(item.getString("name")) }
					TextButton(onClick = { select(item) }) { Text("Edit") }
				}
			}
			if (profiles.length() > 0) HorizontalDivider()
			if (selected != null) TextButton(onClick = { select(null) }) { Text("New connection") }
			OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
			OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true)
			OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true)
			OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				Text("Private key")
				Switch(keyAuth, { keyAuth = it })
			}
			if (keyAuth) {
				TextButton(onClick = { importKey.launch("*/*") }) {
					Text(
						if (privateKey ==
							null
						) {
							"Import private key"
						} else {
							"Private key imported"
						}
					)
				}
				OutlinedTextField(
					passphrase,
					{
						passphrase = it
					},
					label = { Text("Key passphrase") },
					singleLine = true,
					visualTransformation = PasswordVisualTransformation()
				)
			} else {
				OutlinedTextField(
					password,
					{ password = it },
					label = { Text("Password") },
					singleLine = true,
					visualTransformation = PasswordVisualTransformation()
				)
			}
			if (selected !=
				null
			) {
				Text(
					"Leave credentials unchanged to keep the saved credential. Reimport the key to change its passphrase.",
					style = MaterialTheme.typography.bodySmall
				)
			}
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				Text("Auto reconnect")
				Switch(autoReconnect, {
					autoReconnect =
						it
				})
			}
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				Text("WLAN only")
				Switch(wlanOnly, {
					wlanOnly =
						it
				})
			}
			error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
			TextButton(enabled = valid, onClick = {
				runOperation {
					val item = profile()
					val credential = secret()
					withContext(Dispatchers.IO) { repository.saveProfile(item, credential) }
					select(item)
				}
			}) { Text("Save profile") }
			selected?.let { item ->
				TextButton(enabled = !busy, onClick = {
					runOperation {
						if (session.currentProfileId == item.getString("id")) session.disconnect()
						withContext(Dispatchers.IO) { repository.deleteProfile(item.getString("id")) }
						select(null)
					}
				}) {
					Text(
						if (session.currentProfileId ==
							item.getString("id")
						) {
							"Disconnect and delete"
						} else {
							"Delete profile"
						}
					)
				}
				TextButton(onClick = {
					session.forgetHostKey(item.getString("host"), item.getInt("port"))
					error =
						"Host trust removed. Verify the fingerprint at the next connection."
				}) { Text("Forget host key") }
			}
		}
	}, confirmButton = {
		Button(enabled = valid, onClick = {
			runOperation {
				val item = profile()
				val auth =
					secret() ?: withContext(Dispatchers.IO) { repository.connection(selected!!).authentication }
				connect(
					ConnectionRequest(
						item.getString("name"),
						item.getString("host"),
						item.getInt("port"),
						item.getString("username"),
						auth,
						autoReconnect,
						wlanOnly,
						selected?.getString("id")
					)
				)
			}
		}) { Text("Connect") }
	}, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
