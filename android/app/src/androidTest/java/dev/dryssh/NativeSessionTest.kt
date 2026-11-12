package dev.dryssh

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSessionTest {
	@Test
	fun connectionPolicyCrossesJniWithoutAndroidTypes() {
		var policy = ""
		fun apply(kind: String, data: Any? = null): JSONObject {
			val event = JSONObject().put("kind", kind)
			if (data != null) event.put("data", data)
			val result = JSONObject(NativeBridge.transition(policy, event.toString()))
			policy = result.getJSONObject("policy").toString()
			return result
		}
		apply("network_changed", JSONObject().put("online", true).put("wlan", false))
		val waiting =
			apply("connect", JSONObject().put("auto_reconnect", true).put("wlan_only", true))
		assertEquals("waiting_wlan", waiting.getString("state"))
		val connecting =
			apply("network_changed", JSONObject().put("online", true).put("wlan", true))
		assertEquals("connecting", connecting.getString("state"))
		assertTrue(connecting.getJSONArray("effects").getJSONObject(0).getBoolean("wlan_only"))
		val old = connecting.getLong("generation")
		apply("disconnect")
		assertEquals(
			"idle",
			apply("failed", JSONArray().put(old).put("network")).getString("state")
		)
	}

	@Test(timeout = 30_000)
	fun platformSocketAuthenticatesAndExchangesPtyData() {
		val host = InstrumentationRegistry.getArguments().getString("sshHost")
		assumeNotNull(host)
		val port = InstrumentationRegistry.getArguments().getString("sshPort", "22222").toInt()
		Socket(host, port).use { socket ->
			val options = JSONObject().put("username", "dryssh")
				.put(
					"authentication",
					JSONObject().put("kind", "password").put("password", "dryssh-test")
				)
				.put("trusted_fingerprint", JSONObject.NULL).put("columns", 80).put("rows", 24)
			val id = NativeBridge.start(
				ParcelFileDescriptor.fromSocket(socket).detachFd(),
				options.toString()
			)
			try {
				assertFalse(NativeBridge.sendInput(id, "must not run".toByteArray()))
				while (true) {
					val frame = NativeBridge.nextEvent(id)!!
					if (frame[0] != 1.toByte()) continue
					val control = JSONObject(String(frame, 1, frame.size - 1, Charsets.UTF_8))
					when (control.getString("kind")) {
						"host_key" -> {
							assertTrue(control.getString("fingerprint").startsWith("SHA256:"))
							NativeBridge.trust(id, true)
						}
						"connected" -> break
						"closed" -> throw AssertionError(control.toString())
					}
				}
				assertTrue(NativeBridge.resize(id, 97, 31, 0, 0))
				val command = "printf '%s%s\\n' 'DRYSSH_' 'NATIVE_OK'; stty size\r"
				assertTrue(NativeBridge.sendInput(id, command.toByteArray()))
				val output = StringBuilder()
				while (!output.contains("DRYSSH_NATIVE_OK") || !output.contains("31 97")) {
					val frame = NativeBridge.nextEvent(id)!!
					if (frame[0] ==
						0.toByte()
					) {
						output.append(String(frame, 1, frame.size - 1, Charsets.UTF_8))
					} else {
						val control = JSONObject(String(frame, 1, frame.size - 1, Charsets.UTF_8))
						assertFalse(control.toString(), control.getString("kind") == "closed")
					}
				}
			} finally {
				NativeBridge.close(id)
			}
			assertFalse(NativeBridge.sendInput(id, "offline".toByteArray()))
		}
	}
}
