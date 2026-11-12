package dev.dryssh

object NativeBridge {
	init {
		System.loadLibrary("dryssh")
	}

	external fun version(): String

	external fun start(fd: Int, options: String): Long
	external fun nextEvent(id: Long): ByteArray?
	external fun sendInput(id: Long, bytes: ByteArray): Boolean
	external fun resize(id: Long, columns: Int, rows: Int, width: Int, height: Int): Boolean
	external fun trust(id: Long, accepted: Boolean)
	external fun close(id: Long)
	external fun transition(policy: String, event: String): String
}
