package dev.dryssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
	fun put(reference: String, secret: ByteArray)
	fun read(reference: String): ByteArray
	fun delete(reference: String)
}

class KeystoreCredentialStore(context: Context) : CredentialStore {
	private val directory = File(context.noBackupFilesDir, "credentials").apply { mkdirs() }
	private fun file(reference: String): AtomicFile {
		require(reference.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid credential reference" }
		return AtomicFile(File(directory, reference))
	}
	private fun key(): SecretKey = synchronized(KeystoreCredentialStore::class.java) {
		val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(store.getKey(ALIAS, null) as SecretKey?) ?: KeyGenerator.getInstance(
			KeyProperties.KEY_ALGORITHM_AES,
			"AndroidKeyStore"
		).apply {
			init(
				KeyGenParameterSpec.Builder(
					ALIAS,
					KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
				)
					.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.setKeySize(256).build()
			)
		}.generateKey()
	}
	override fun put(reference: String, secret: ByteArray) {
		val target = file(reference)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, key())
		cipher.updateAAD(reference.toByteArray(Charsets.UTF_8))
		val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(secret)
		val stream = target.startWrite()
		try {
			stream.write(bytes)
			target.finishWrite(stream)
		} catch (error: Exception) {
			target.failWrite(stream)
			throw error
		}
	}
	override fun read(reference: String): ByteArray {
		val bytes = file(reference).readFully()
		require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Invalid encrypted credential" }
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
		cipher.updateAAD(reference.toByteArray(Charsets.UTF_8))
		return cipher.doFinal(bytes, 13, bytes.size - 13)
	}
	override fun delete(reference: String) {
		file(reference).delete()
	}
	companion object {
		private const val ALIAS = "dryssh.credentials.v1"
	}
}
