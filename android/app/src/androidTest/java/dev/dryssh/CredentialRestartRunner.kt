package dev.dryssh

import android.app.Activity
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** Explicit persistence acceptance across process death and an emulator reboot. */
class CredentialRestartRunner : AndroidJUnitRunner() {
	private lateinit var arguments: Bundle
	override fun onCreate(arguments: Bundle) {
		this.arguments = arguments
		super.onCreate(arguments)
	}
	override fun onStart() {
		val result = Bundle()
		var success = false
		try {
			val repository = (targetContext.applicationContext as DrySshApplication).repository
			val stage = arguments.getString("stage")!!
			val kinds = listOf("password", "private_key")
			when (stage) {
				"seed" -> for (kind in kinds) {
					val profile = JSONObject().put(
						"id",
						"acceptance-restart-$kind"
					).put("name", "Restart acceptance $kind")
						.put(
							"host",
							arguments.getString("sshHost")
						).put("port", arguments.getString("sshPort", "22222").toInt())
						.put(
							"username",
							"dryssh"
						).put("authentication", kind).put("auto_reconnect", false).put("wlan_only", false)
					val credential = JSONObject().put("kind", kind)
					if (kind == "password") {
						credential.put("password", "dryssh-test")
					} else {
						credential
							.put(
								"key",
								context.assets.open("encrypted-fixture-key").bufferedReader().use {
									it.readText()
								}
							).put("passphrase", "dryssh-key-test")
					}
					repository.saveProfile(profile, credential)
				}
				"verify" -> SessionFixture().use { fixture ->
					for (kind in kinds) {
						val profiles = JSONObject(repository.config.value).getJSONArray("profiles")
						val profile = (0 until profiles.length()).map { profiles.getJSONObject(it) }.single {
							it.getString("id") ==
								"acceptance-restart-$kind"
						}
						val request = repository.connection(profile)
						assertEquals(kind, request.authentication.getString("kind"))
						runOnMainSync { fixture.service.connect(request) }
						fixture.awaitCondition {
							fixture.service.state.value.hostKey != null ||
								fixture.service.state.value.state == "connected"
						}
						if (fixture.service.state.value.hostKey != null) runOnMainSync { fixture.service.trust(true) }
						fixture.awaitCondition { fixture.service.state.value.state == "connected" }
						fixture.shellPid("restart-$kind")
					}
				}
				"cleanup" -> kinds.forEach { repository.deleteProfile("acceptance-restart-$it") }
				else -> error("Unknown persistence stage")
			}
			result.putString("stream", "PASS: $stage\n")
			success = true
		} catch (failure: Throwable) {
			result.putString("stream", failure.stackTraceToString())
		}
		finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
	}
}
