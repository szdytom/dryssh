package dev.dryssh

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private fun reference() = "test-${UUID.randomUUID()}"
	private fun profile(id: String) = JSONObject().put("id", id).put("name", "Saved server")
		.put("host", InstrumentationRegistry.getArguments().getString("sshHost", "localhost"))
		.put("port", InstrumentationRegistry.getArguments().getString("sshPort", "22222").toInt())
		.put(
			"username",
			"dryssh"
		).put("authentication", "password").put("auto_reconnect", true).put("wlan_only", false)

	@Test fun encryptedCredentialsSurviveReloadAndRejectTamperingAndSwaps() {
		val store = KeystoreCredentialStore(context)
		val a = reference()
		val b = reference()
		val secret = "password / 私钥口令 / ${UUID.randomUUID()}".toByteArray()
		try {
			store.put(a, secret)
			store.put(b, "another secret".toByteArray())
			assertArrayEquals(secret, KeystoreCredentialStore(context).read(a))
			val fileA = File(context.noBackupFilesDir, "credentials/$a")
			val encrypted = fileA.readBytes()
			assertFalse(encrypted.toString(Charsets.UTF_8).contains(secret.toString(Charsets.UTF_8)))
			File(context.noBackupFilesDir, "credentials/$b").writeBytes(encrypted)
			assertThrows(Exception::class.java) { store.read(b) }
			encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
			fileA.writeBytes(encrypted)
			assertThrows(Exception::class.java) { store.read(a) }
		} finally {
			store.delete(a)
			store.delete(b)
		}
	}

	@Test fun profileEditsPersistWithoutPlaintextAndFailedUpdatesKeepOldCredential() {
		val repository = AppRepository(context)
		val item = profile(reference())
		val password = "private-${UUID.randomUUID()}"
		try {
			repository.saveProfile(item, JSONObject().put("kind", "password").put("password", password))
			val oldReference = item.getString("credential_ref")
			assertEquals(
				password,
				AppRepository(context).connection(item).authentication.getString("password")
			)
			assertFalse(File(context.noBackupFilesDir, "configuration.json").readText().contains(password))
			item.put("name", "Renamed").put("wlan_only", true)
			repository.saveProfile(item, null)
			val persisted = JSONObject(AppRepository(context).config.value).getJSONArray("profiles")
			val saved = (0 until persisted.length()).map { persisted.getJSONObject(it) }.first {
				it.getString("id") ==
					item.getString("id")
			}
			assertEquals("Renamed", saved.getString("name"))
			assertTrue(saved.getBoolean("wlan_only"))
			val broken = JSONObject(item.toString()).put("port", 0)
			assertThrows(Exception::class.java) {
				repository.saveProfile(
					broken,
					JSONObject().put("kind", "password").put("password", "replacement")
				)
			}
			assertEquals(
				password,
				AppRepository(context).connection(item).authentication.getString("password")
			)
			assertEquals(oldReference, item.getString("credential_ref"))
		} finally {
			repository.deleteProfile(item.getString("id"))
		}
		assertThrows(Exception::class.java) {
			KeystoreCredentialStore(context).read(item.getString("credential_ref"))
		}
	}

	@Test fun encryptedPrivateKeyIsSavedReloadedAndAuthenticatesToOpenSsh() {
		SessionFixture().use { fixture ->
			val repository = AppRepository(context)
			val item = profile(reference()).put("authentication", "private_key").put("auto_reconnect", false)
			val key = InstrumentationRegistry.getInstrumentation().context.assets.open(
				"encrypted-fixture-key"
			).bufferedReader().use {
				it.readText()
			}
			try {
				repository.saveProfile(
					item,
					JSONObject().put("kind", "private_key").put("key", key).put("passphrase", "dryssh-key-test")
				)
				val connection = AppRepository(context).connection(item)
				fixture.instrumentation.runOnMainSync { fixture.service.connect(connection) }
				fixture.awaitCondition {
					fixture.service.state.value.hostKey != null ||
						fixture.service.state.value.state == "connected"
				}
				if (fixture.service.state.value.hostKey !=
					null
				) {
					fixture.instrumentation.runOnMainSync { fixture.service.trust(true) }
				}
				fixture.awaitCondition { fixture.service.state.value.state == "connected" }
				assertTrue(fixture.shellPid("saved-key").toInt() > 0)
			} finally {
				repository.deleteProfile(item.getString("id"))
			}
		}
	}
}
