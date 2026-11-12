package dev.dryssh

import org.json.JSONObject

class ConnectionRequest(
	val name: String,
	val host: String,
	val port: Int,
	val username: String,
	val authentication: JSONObject,
	val autoReconnect: Boolean = true,
	val wlanOnly: Boolean = false
) {
	val trustKey: String get() = "${host.lowercase()}:$port"
}
