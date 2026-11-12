package dev.dryssh

import android.content.pm.ActivityInfo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionLifecycleTest {
	@Test(timeout = 90_000)
	fun originalShellSurvivesRecreationRotationAndBackground() {
		SessionFixture().use { fixture ->
			fixture.connect()
			fixture.detachTestBinding()
			val originalPid = fixture.shellPid("before")
			fixture.activity.recreate()
			assertEquals(originalPid, fixture.shellPid("recreated"))
			val dimensions = fixture.service.terminal.dimensions
			fixture.activity.onActivity {
				it.requestedOrientation =
					ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
			}
			fixture.awaitCondition {
				fixture.service.terminal.dimensions.columns !=
					dimensions.columns
			}
			assertEquals(originalPid, fixture.shellPid("rotated"))
			fixture.activity.moveToState(Lifecycle.State.CREATED)
			Thread.sleep(2_000)
			assertEquals("connected", fixture.service.state.value.state)
			fixture.activity.moveToState(Lifecycle.State.RESUMED)
			assertEquals(originalPid, fixture.shellPid("resumed"))
			fixture.instrumentation.runOnMainSync { fixture.service.disconnect() }
			assertEquals("idle", fixture.service.state.value.state)
			fixture.activity.recreate()
			assertEquals("idle", fixture.service.state.value.state)
		}
	}
}
