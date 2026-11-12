package dev.dryssh

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceNotificationTest {
	@Test(timeout = 45_000)
	fun notificationDisconnectCancelsReconnectIntent() {
		SessionFixture().use { fixture ->
			fixture.connect(autoReconnect = true)
			fixture.shellPid("notification")
			val manager = fixture.service.getSystemService(NotificationManager::class.java)
			val notification = manager.activeNotifications.first { it.id == 1 }.notification
			assertTrue(notification.contentIntent != null)
			notification.actions.single { it.title == "Disconnect" }.actionIntent.send()
			fixture.awaitCondition { fixture.service.state.value.state == "idle" }
			Thread.sleep(2_500)
			assertEquals("idle", fixture.service.state.value.state)
			assertTrue(manager.activeNotifications.none { it.id == 1 })
		}
	}

	@Test(timeout = 45_000)
	fun tappingNotificationReturnsToTheOriginalShell() {
		SessionFixture().use { fixture ->
			fixture.connect()
			val before = fixture.shellPid("return-before")
			val automation = fixture.instrumentation.uiAutomation
			fun shell(command: String) {
				ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
					it.readBytes()
				}
			}
			try {
				fixture.activity.onActivity { it.moveTaskToBack(true) }
				shell("cmd statusbar expand-notifications")
				automation.serviceInfo = automation.serviceInfo.apply {
					flags =
						flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
						AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
				}
				fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
					if (node == null) return null
					if (node.text?.contains("Integration server") == true ||
						node.contentDescription?.contains("Integration server") == true
					) {
						return node
					}
					for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
					return null
				}
				fun notification() = automation.windows.firstNotNullOfOrNull { find(it.root) }
				fixture.awaitCondition { notification() != null }
				var node = notification()!!
				while (!node.isClickable) node = node.parent
				assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
				fixture.awaitCondition { fixture.activity.state == Lifecycle.State.RESUMED }
				assertEquals(before, fixture.shellPid("return-after"))
			} finally {
				shell("cmd statusbar collapse")
			}
		}
	}
}
