package dev.dryssh

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WlanLifecycleTest {
	@Test(timeout = 90_000)
	fun wlanLossWaitsWithoutCellularFallbackAndReconnectsToNewShell() {
		SessionFixture().use { fixture ->
			fun shell(command: String) {
				ParcelFileDescriptor.AutoCloseInputStream(
					fixture.instrumentation.uiAutomation.executeShellCommand(command)
				).use { it.readBytes() }
			}
			try {
				fixture.connect(autoReconnect = true, wlanOnly = true)
				val before = fixture.shellPid("wlan-before")
				shell("svc wifi disable")
				fixture.awaitCondition { fixture.service.state.value.state == "waiting_wlan" }
				val manager = fixture.service.getSystemService(ConnectivityManager::class.java)
				fixture.awaitCondition {
					manager.getNetworkCapabilities(manager.activeNetwork)
						?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
				}
				fixture.instrumentation.runOnMainSync {
					fixture.service.terminal.pasteText(
						"printf '%s%s\\n' 'https://example.' 'org/should-not-execute'"
					)
					fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
				}
				fixture.instrumentation.waitForIdleSync()
				Thread.sleep(2_500)
				assertEquals("waiting_wlan", fixture.service.state.value.state)
				shell("svc wifi enable")
				shell("cmd wifi connect-network AndroidWifi open")
				fixture.awaitCondition { fixture.service.state.value.state == "connected" }
				assertNotEquals(before, fixture.shellPid("wlan-after"))
				assertTrue(
					fixture.service.terminal.getUrls().none {
						it.url == "https://example.org/should-not-execute"
					}
				)
				fixture.instrumentation.runOnMainSync { fixture.service.disconnect() }
				shell("svc wifi disable")
				shell("svc wifi enable")
				shell("cmd wifi connect-network AndroidWifi open")
				Thread.sleep(2_500)
				assertEquals("idle", fixture.service.state.value.state)
			} finally {
				shell("svc wifi enable")
				shell("cmd wifi connect-network AndroidWifi open")
			}
		}
	}
}
