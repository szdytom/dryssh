package dev.dryssh

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class DrySshApplication : Application() {
	val repository by lazy { AppRepository(this) }
}

class AppRepository(
	context: Context,
	private val credentials: CredentialStore = KeystoreCredentialStore(context)
) {
	private val file = AtomicFile(File(context.noBackupFilesDir, "configuration.json"))
	var loadError: String? = null
		private set
	private val mutableConfig = MutableStateFlow(
		runCatching {
			if (file.baseFile.exists()) {
				NativeBridge.normalizeConfig(file.readFully().toString(Charsets.UTF_8))
			} else {
				NativeBridge.normalizeConfig("{}")
			}
		}.getOrElse {
			loadError = "Cannot load saved configuration: ${it.message}"
			NativeBridge.normalizeConfig("{}")
		}
	)
	val config = mutableConfig.asStateFlow()
	val fonts = File(context.noBackupFilesDir, "fonts").apply { mkdirs() }

	@Synchronized fun update(config: JSONObject) {
		check(loadError == null) { loadError!! }
		val normalized = NativeBridge.normalizeConfig(config.toString())
		val stream = file.startWrite()
		try {
			stream.write(normalized.toByteArray(Charsets.UTF_8))
			file.finishWrite(stream)
		} catch (error: Exception) {
			file.failWrite(stream)
			throw error
		}
		mutableConfig.value = normalized
	}

	@Synchronized fun saveProfile(profile: JSONObject, secret: JSONObject?) {
		val config = JSONObject(this.config.value)
		val profiles = config.getJSONArray("profiles")
		val old = (0 until profiles.length()).map { profiles.getJSONObject(it) }
			.firstOrNull { it.getString("id") == profile.getString("id") }
		val reference = if (secret != null) {
			UUID.randomUUID().toString()
		} else {
			old?.getString("credential_ref") ?: throw IOException("A credential is required")
		}
		profile.put("credential_ref", reference)
		if (secret != null) credentials.put(reference, secret.toString().toByteArray(Charsets.UTF_8))
		try {
			val updated = JSONArray()
			for (i in 0 until profiles.length()) {
				val item = profiles.getJSONObject(i)
				if (item.getString("id") != profile.getString("id")) updated.put(item)
			}
			updated.put(profile)
			update(config.put("profiles", updated))
		} catch (error: Exception) {
			if (secret != null) credentials.delete(reference)
			throw error
		}
		if (secret != null && old != null) credentials.delete(old.getString("credential_ref"))
	}

	@Synchronized fun deleteProfile(id: String) {
		val config = JSONObject(this.config.value)
		val profiles = config.getJSONArray("profiles")
		val updated = JSONArray()
		var reference: String? = null
		for (i in 0 until profiles.length()) {
			val item = profiles.getJSONObject(i)
			if (item.getString("id") ==
				id
			) {
				reference = item.getString("credential_ref")
			} else {
				updated.put(item)
			}
		}
		update(config.put("profiles", updated))
		reference?.let(credentials::delete)
	}
	fun connection(profile: JSONObject): ConnectionRequest {
		val secret =
			JSONObject(credentials.read(profile.getString("credential_ref")).toString(Charsets.UTF_8))
		check(secret.getString("kind") == profile.getString("authentication")) {
			"Credential type does not match the profile"
		}
		return ConnectionRequest(
			profile.getString("name"),
			profile.getString("host"),
			profile.getInt("port"),
			profile.getString("username"),
			secret,
			profile.getBoolean("auto_reconnect"),
			profile.getBoolean("wlan_only"),
			profile.getString("id")
		)
	}
}
