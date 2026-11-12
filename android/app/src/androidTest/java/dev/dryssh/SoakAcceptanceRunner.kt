package dev.dryssh

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Explicit runner: long acceptance stays outside the ordinary regression suite. */
class SoakAcceptanceRunner : AndroidJUnitRunner() {
	private lateinit var arguments: Bundle
	override fun onCreate(arguments: Bundle) {
		this.arguments = arguments
		super.onCreate(arguments)
	}
	private fun shell(command: String): String = uiAutomation.executeShellCommand(command).use {
		android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
	}
	override fun onStart() {
		val result = Bundle()
		val mode = arguments.getString("mode", "background")
		val minutes = arguments.getString("minutes", "30").toInt()
		var success = false
		val oldTimeout = shell("settings get system screen_off_timeout").trim()
		try {
			require(mode == "background" || mode == "lock")
			require(minutes >= 30) { "Acceptance requires at least 30 minutes" }
			shell("dumpsys battery unplug")
			shell("settings put system screen_off_timeout 2147483647")
			SessionFixture().use { fixture ->
				fixture.connect(autoReconnect = false)
				val before = fixture.shellPid("soak-before")
				val record = "/tmp/dryssh-soak-$mode-${System.currentTimeMillis()}"
				fun record(stage: String) {
					val command = "printf '%s %s %s %s\\n' '$stage' \"\$\$\" \"\$PPID\" " +
						"\"\$(awk '/PPid:/ {print \$2}' /proc/\$PPID/status)\" >> $record"
					runOnMainSync {
						fixture.service.terminal.pasteText(command)
						fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
					}
					fixture.shellPid("record-$stage")
				}
				record("before")
				fixture.detachTestBinding()
				if (mode == "lock") {
					shell("locksettings set-disabled false")
					shell("input keyevent 223")
					fixture.awaitCondition {
						targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked
					}
					assertFalse(targetContext.getSystemService(PowerManager::class.java).isInteractive)
					assertTrue(targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
				} else {
					runOnMainSync {
						targetContext.startActivity(
							Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						)
					}
				}
				val start = SystemClock.elapsedRealtime()
				val duration = minutes * 60_000L
				var reported = -1L
				while (SystemClock.elapsedRealtime() - start < duration) {
					assertEquals("connected", fixture.service.state.value.state)
					val elapsed = (SystemClock.elapsedRealtime() - start) / 60_000
					if (elapsed != reported) {
						reported = elapsed
						sendStatus(
							1,
							Bundle().apply {
								putString("stream", "$mode: $elapsed/$minutes minutes, original shell $before\n")
							}
						)
					}
					Thread.sleep(1_000)
				}
				if (mode == "lock") {
					assertFalse(targetContext.getSystemService(PowerManager::class.java).isInteractive)
					shell("input keyevent 224")
					shell("wm dismiss-keyguard")
				}
				shell("am start -f 0x00020000 -n dev.dryssh/.MainActivity")
				val after = fixture.shellPid("soak-after")
				assertEquals(before, after)
				record("after")
				result.putString("server_record", record)
				result.putString(
					"stream",
					"PASS: $mode, ${SystemClock.elapsedRealtime() - start} ms, original shell $before\nServer record: $record\n"
				)
			}
			success = true
		} catch (failure: Throwable) {
			result.putString("stream", failure.stackTraceToString())
		} finally {
			shell("settings put system screen_off_timeout $oldTimeout")
			shell("dumpsys battery reset")
		}
		finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
	}
}
