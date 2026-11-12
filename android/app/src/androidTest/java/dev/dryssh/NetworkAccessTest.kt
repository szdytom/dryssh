package dev.dryssh

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkAccessTest {
	@Test(timeout = 60_000)
	fun bindsWlanSocketAndDnsWhenProcessDefaultIsCellular() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val host = InstrumentationRegistry.getArguments().getString("sshHost")
		assumeNotNull(host)
		val dnsHost = InstrumentationRegistry.getArguments().getString("sshDnsHost", host)
		val port = InstrumentationRegistry.getArguments().getString("sshPort", "22222").toInt()
		val context = instrumentation.targetContext
		val manager = context.getSystemService(ConnectivityManager::class.java)
		lateinit var access: NetworkAccess
		instrumentation.runOnMainSync { access = NetworkAccess(context, { _, _ -> }, { }) }
		val isolateDns = InstrumentationRegistry.getArguments().getString("isolateDns") == "true"
		val lookupHost = if (isolateDns) {
			require(dnsHost!!.endsWith(".nip.io")) {
				"DNS isolation requires a wildcard nip.io fixture name"
			}
			"probe${System.nanoTime()}.$dnsHost"
		} else {
			dnsHost!!
		}
		val cleanupRules = mutableListOf<String>()
		fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
			instrumentation.uiAutomation.executeShellCommand(command)
		).bufferedReader().use {
			it.readText().trim()
		}
		fun root(command: String) {
			assertEquals("", shell("su 0 $command"))
		}

		val privateDns = if (isolateDns) shell("settings get global private_dns_mode") else ""
		val previous = manager.boundNetworkForProcess
		val cellReady = CountDownLatch(1)
		val cellNetwork = AtomicReference<Network>()
		val cellCallback = object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) {
				cellNetwork.set(network)
				cellReady.countDown()
			}
		}
		manager.requestNetwork(
			NetworkRequest.Builder().addTransportType(
				NetworkCapabilities.TRANSPORT_CELLULAR
			).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
			cellCallback
		)
		instrumentation.runOnMainSync { access.retainWlan(true) }
		try {
			var wlan: Network? = null
			val deadline = System.nanoTime() + 20_000_000_000
			while (wlan == null && System.nanoTime() < deadline) {
				instrumentation.runOnMainSync { wlan = access.selectedWlan() }
				Thread.sleep(20)
			}
			assertTrue("Connect the emulator to AndroidWifi before this test", wlan != null)
			assertTrue("Cellular network unavailable", cellReady.await(10, TimeUnit.SECONDS))
			val cellular = cellNetwork.get()
			assertTrue(manager.bindProcessToNetwork(cellular))
			val cellAddresses = manager.getLinkProperties(cellular)!!.linkAddresses.map {
				it.address
			}
			val wlanAddresses = manager.getLinkProperties(wlan!!)!!.linkAddresses.map { it.address }
			if (isolateDns) {
				assertEquals("eth0", manager.getLinkProperties(cellular)!!.interfaceName)
				shell("settings put global private_dns_mode off")
				for (tool in listOf("iptables", "ip6tables")) {
					for (protocol in listOf("udp", "tcp")) {
						val rule = "OUTPUT -o eth0 -p $protocol --dport 53 -j REJECT"
						root("$tool -I $rule")
						cleanupRules.add("$tool -D $rule")
					}
				}
				assertThrows(UnknownHostException::class.java) { cellular.getAllByName(lookupHost) }
				Log.i("DrySSH", "DNS isolation: cellular resolver refused the fresh fixture hostname")
			}
			Socket(host, port).use { socket ->
				assertTrue(cellAddresses.contains(socket.localAddress))
				socket.soTimeout = 5_000
				assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("SSH-"))
				access.open(lookupHost, port, true, wlan) { }.use { bound ->
					assertTrue(wlanAddresses.contains(bound.localAddress))
					assertNotEquals(socket.localAddress, bound.localAddress)
					assertEquals(socket.inetAddress, bound.inetAddress)
					bound.soTimeout = 5_000
					assertTrue(
						bound.getInputStream().bufferedReader().readLine().startsWith("SSH-")
					)
				}
			}
		} finally {
			cleanupRules.asReversed().forEach(::root)
			if (isolateDns) {
				shell(
					if (privateDns ==
						"null"
					) {
						"settings delete global private_dns_mode"
					} else {
						"settings put global private_dns_mode $privateDns"
					}
				)
			}
			manager.bindProcessToNetwork(previous)
			manager.unregisterNetworkCallback(cellCallback)
			instrumentation.runOnMainSync { access.close() }
		}
	}
}
