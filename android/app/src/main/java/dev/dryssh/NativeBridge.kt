package dev.dryssh

object NativeBridge {
	init {
		System.loadLibrary("dryssh")
	}

	external fun version(): String
}
