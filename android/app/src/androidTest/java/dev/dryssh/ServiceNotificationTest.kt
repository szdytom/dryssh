package dev.dryssh

import android.app.NotificationManager
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
}
