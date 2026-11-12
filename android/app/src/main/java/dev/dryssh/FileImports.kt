package dev.dryssh

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readBounded(maximum: Int): ByteArray {
	val output = ByteArrayOutputStream()
	val buffer = ByteArray(8192)
	while (true) {
		val count = read(buffer)
		if (count < 0) return output.toByteArray()
		require(output.size() + count <= maximum) { "Imported file is too large" }
		output.write(buffer, 0, count)
	}
}
