package dev.dryssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class NetworkAccess(
	context: Context,
	private val changed: (Boolean, Boolean) -> Unit,
	private val lost: (Network) -> Unit
) : AutoCloseable {
	private val manager = context.getSystemService(ConnectivityManager::class.java)
	private val wlan = linkedSetOf<Network>()
	private val retainedWlan = linkedSetOf<Network>()
	private var retention: WifiCallback? = null
	private val handler = Handler(Looper.getMainLooper())
	private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
		override fun onAvailable(network: Network) = report()
		override fun onLost(network: Network) = report()
		override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = report()
	}
	private inner class WifiCallback(private val retained: Boolean = false) :
		ConnectivityManager.NetworkCallback() {
		private fun update(network: Network, capabilities: NetworkCapabilities?) {
			if (retained && retention !== this) return
			val networks = if (retained) retainedWlan else wlan
			if (isWlan(capabilities)) {
				networks.add(network)
			} else if (networks.remove(network)) {
				lost(network)
			}
			report()
		}
		override fun onAvailable(network: Network) =
			update(network, manager.getNetworkCapabilities(network))
		override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
			update(network, capabilities)
		override fun onLost(network: Network) {
			if (retained && retention !== this) return
			(if (retained) retainedWlan else wlan).remove(network)
			lost(network)
			report()
		}
	}
	private val wlanCallback = WifiCallback()

	fun retainWlan(enabled: Boolean) {
		if (enabled && retention == null) {
			val callback = WifiCallback(retained = true)
			retention = callback
			try {
				manager.requestNetwork(
					NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
					callback,
					handler
				)
			} catch (error: SecurityException) {
				retention = null
				throw IOException("Cannot reserve WLAN: ${error.message}", error)
			}
		} else if (!enabled && retention != null) {
			manager.unregisterNetworkCallback(retention!!)
			retention = null
			retainedWlan.clear()
		}
		report()
	}

	init {
		manager.registerDefaultNetworkCallback(defaultCallback, handler)
		manager.registerNetworkCallback(
			NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
			wlanCallback,
			handler
		)
	}

	private fun isWlan(capabilities: NetworkCapabilities?): Boolean =
		capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
			capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

	private fun report() = changed(manager.activeNetwork != null, selectedWlan() != null)

	fun selectedWlan(): Network? = (
		if (retention !=
			null
		) {
			retainedWlan
		} else {
			wlan
		}
		).firstOrNull { isWlan(manager.getNetworkCapabilities(it)) }

	fun open(
		host: String,
		port: Int,
		strict: Boolean,
		network: Network?,
		own: (Socket) -> Unit
	): Socket {
		if (strict && (network == null || !isWlan(manager.getNetworkCapabilities(network)))) {
			throw IOException("WLAN binding is unavailable")
		}
		val addresses = if (strict) network!!.getAllByName(host) else InetAddress.getAllByName(host)
		var lastError: IOException? = null
		for (address in addresses) {
			val socket = Socket()
			try {
				own(socket)
				if (strict) {
					if (!isWlan(
							manager.getNetworkCapabilities(network)
						)
					) {
						throw IOException("WLAN was lost")
					}
					network!!.bindSocket(socket)
				}
				socket.tcpNoDelay = true
				socket.keepAlive = true
				socket.connect(InetSocketAddress(address, port), 15_000)
				return socket
			} catch (error: IOException) {
				socket.close()
				lastError = error
			}
		}
		throw lastError ?: IOException("Host has no usable address")
	}

	override fun close() {
		retention?.let { manager.unregisterNetworkCallback(it) }
		retention = null
		manager.unregisterNetworkCallback(defaultCallback)
		manager.unregisterNetworkCallback(wlanCallback)
	}
}
