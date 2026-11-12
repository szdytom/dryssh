package dev.dryssh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.connectbot.terminal.VTermKey
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull

class SessionFixture : AutoCloseable {
	val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val host = InstrumentationRegistry.getArguments().getString("sshHost").also {
		assumeNotNull(it)
	}!!
	private val port = InstrumentationRegistry.getArguments().getString("sshPort", "22222").toInt()
	private val context = instrumentation.targetContext
	val activity = ActivityScenario.launch(MainActivity::class.java)
	lateinit var service: SshService
		private set
	private var bound = true
	private val latch = CountDownLatch(1)
	private val binding = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName, binder: IBinder) {
			service = (binder as SshService.LocalBinder).service
			latch.countDown()
		}
		override fun onServiceDisconnected(name: ComponentName) { }
	}

	init {
		context.bindService(
			Intent(context, SshService::class.java),
			binding,
			Context.BIND_AUTO_CREATE
		)
		assertTrue(latch.await(5, TimeUnit.SECONDS))
	}

	fun awaitCondition(condition: () -> Boolean) {
		val deadline = System.nanoTime() + 20_000_000_000
		while (System.nanoTime() < deadline) {
			instrumentation.waitForIdleSync()
			if (condition()) return
			Thread.sleep(20)
		}
		assertTrue("Condition did not become true: ${service.state.value}", condition())
	}

	fun connect(autoReconnect: Boolean = false, wlanOnly: Boolean = false) {
		instrumentation.runOnMainSync {
			service.connect(
				ConnectionRequest(
					"Integration server",
					host,
					port,
					"dryssh",
					JSONObject().put("kind", "password").put("password", "dryssh-test"),
					autoReconnect = autoReconnect,
					wlanOnly = wlanOnly
				)
			)
		}
		awaitCondition {
			service.state.value.hostKey != null ||
				service.state.value.state == "connected"
		}
		if (service.state.value.hostKey !=
			null
		) {
			instrumentation.runOnMainSync { service.trust(true) }
		}
		awaitCondition { service.state.value.state == "connected" }
	}

	fun detachTestBinding() {
		context.unbindService(binding)
		bound = false
	}

	fun shellPid(stage: String): String {
		val command = "printf '%s%s%s\\n' 'https://example.' 'org/$stage-' \"\$\$\""
		instrumentation.runOnMainSync {
			service.terminal.pasteText(command)
			service.terminal.dispatchKey(0, VTermKey.ENTER)
		}
		val pattern = Regex("https://example.org/$stage-[0-9]+")
		awaitCondition { service.terminal.getUrls().any { pattern.matches(it.url) } }
		return service.terminal.getUrls().first {
			pattern.matches(it.url)
		}.url.substringAfterLast('-')
	}

	override fun close() {
		instrumentation.runOnMainSync { service.disconnect() }
		if (bound) context.unbindService(binding)
		activity.close()
	}
}
