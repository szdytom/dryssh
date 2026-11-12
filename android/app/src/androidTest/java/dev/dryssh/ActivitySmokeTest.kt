package dev.dryssh

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivitySmokeTest {
	@Test
	fun launchesAndRecreatesWithNativeTerminal() {
		ActivityScenario.launch(MainActivity::class.java).use { scenario ->
			scenario.onActivity { assertEquals("DrySSH", it.title.toString()) }
			InstrumentationRegistry.getInstrumentation().waitForIdleSync()
			scenario.recreate()
			scenario.onActivity { assertEquals("DrySSH", it.title.toString()) }
		}
	}
}
