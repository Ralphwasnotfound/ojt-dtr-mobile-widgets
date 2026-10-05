package ph.edu.bsit.tcc.ojtdtr.auth

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

/** Versioned AES-256-GCM using Android platform primitives and a non-exportable Keystore key. */
class EncryptedAuthStorage(context: Context, private val alias: String = "dtr.native.auth.v1") {
    private val directory = File(context.noBackupFilesDir, "native-auth").apply { mkdirs() }
    private val lock = Any()
    private fun file(name: String): AtomicFile {
        require(name in setOf("session", "pkce"))
        return AtomicFile(File(directory, "$name.enc"))
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun write(name: String, plaintext: ByteArray) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("ph.edu.bsit.tcc.ojtdtr:$name:v1".toByteArray(Charsets.UTF_8))
        val payload = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
        val target = file(name)
        val stream = target.startWrite()
        try { stream.write(payload); target.finishWrite(stream) }
        catch (failure: Exception) { target.failWrite(stream); throw failure }
    }
    fun read(name: String): ByteArray? = synchronized(lock) {
        val target = file(name)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return@synchronized null
        try {
            val bytes = target.openRead().use { it.readBytes() }
            require(bytes.size in 30..1_048_576 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD("ph.edu.bsit.tcc.ojtdtr:$name:v1".toByteArray(Charsets.UTF_8))
            cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        } catch (failure: Exception) {
            target.delete() // Tampering/key loss never falls back to plaintext or authorizes a user.
            throw SecureStorageFailure()
        }
    }
    fun delete(name: String) = synchronized(lock) {
        val target = file(name)
        target.delete()
        if (listOf(target.baseFile, File(target.baseFile.path + ".bak"), File(target.baseFile.path + ".new")).any { it.exists() })
            throw SecureStorageFailure()
    }
}
class SecureStorageFailure : Exception("Native secure storage unavailable")
