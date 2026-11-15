package dev.dryssh

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCredentialTest {
	@Test fun credentialsRotateDisableAndRollbackWithoutPlaintextConfiguration() {
		val target = InstrumentationRegistry.getInstrumentation().targetContext
		val directory = File(target.cacheDir, "speech-credentials-${System.nanoTime()}").apply {
			mkdirs()
		}
		val context = object : ContextWrapper(target) {
			override fun getNoBackupFilesDir() = directory
		}
		val secrets = mutableMapOf<String, ByteArray>()
		val store = object : CredentialStore {
			override fun put(reference: String, secret: ByteArray) {
				secrets[reference] = secret
			}
			override fun read(reference: String) = secrets.getValue(reference)
			override fun delete(reference: String) {
				secrets.remove(reference)
			}
		}
		try {
			val repository = AppRepository(context, store)
			fun settings() = JSONObject(repository.config.value).getJSONObject("settings")
			repository.saveSettings(settings().put("font_size", 18))
			assertTrue(settings().isNull("speech_api_key_ref"))
			assertTrue(secrets.isEmpty())
			repository.saveSettings(settings(), " first-secret ")
			assertEquals("first-secret", repository.speechApiKey())
			assertEquals(1, secrets.size)
			assertFalse(File(directory, "configuration.json").readText().contains("first-secret"))
			val firstReference = secrets.keys.single()
			repository.saveSettings(settings().put("font_size", 18))
			assertEquals(firstReference, secrets.keys.single())
			assertEquals("first-secret", AppRepository(context, store).speechApiKey())
			assertThrows(IllegalStateException::class.java) {
				repository.saveSettings(settings().put("font_size", 0), "invalid-save-secret")
			}
			assertEquals(firstReference, secrets.keys.single())
			assertEquals("first-secret", repository.speechApiKey())
			repository.saveSettings(settings(), "second-secret")
			assertEquals(1, secrets.size)
			assertFalse(secrets.containsKey(firstReference))
			assertEquals("second-secret", repository.speechApiKey())
			repository.saveSettings(settings(), "")
			assertTrue(secrets.isEmpty())
			assertTrue(settings().isNull("speech_api_key_ref"))
			val failed = AppRepository(
				context,
				object : CredentialStore {
					override fun put(reference: String, secret: ByteArray): Unit =
						throw IOException("store unavailable")
					override fun read(reference: String) = error("No credential saved")
					override fun delete(reference: String) = error("No credential saved")
				}
			)
			assertThrows(IOException::class.java) { failed.saveSettings(settings(), "secret") }
			assertTrue(
				JSONObject(failed.config.value).getJSONObject("settings").isNull("speech_api_key_ref")
			)
		} finally {
			directory.deleteRecursively()
		}
	}
}
