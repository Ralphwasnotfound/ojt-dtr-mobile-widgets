package ph.edu.bsit.tcc.ojtdtr.recovery

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import android.system.Os
import android.system.OsConstants
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate non-exportable key and no-backup directory; no plaintext/key-loss fallback. */
internal class EncryptedJournalStorage(context: Context, private val alias: String = "dtr.native.recovery.v1",
    directoryName: String = "native-recovery") : JournalStorage {
    private val directory = File(context.noBackupFilesDir, directoryName)
    private val target = AtomicFile(File(directory, "attempt.enc"))
    private val aad = "ph.edu.bsit.tcc.ojtdtr:attendance-recovery:v1".toByteArray()
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
    override fun read(): ByteArray? {
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        try {
            // Read at most the ciphertext bound plus one sentinel, regardless of file length.
            val bytes = target.openRead().use { stream ->
                val buffer = ByteArray(4126)
                var count = 0
                while (count < buffer.size) {
                    val read = stream.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) throw RecoveryStorageFailure()
                    count += read
                }
                buffer.copyOf(count)
            }
            require(bytes.size in 30..4125 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        } catch (_: Exception) { throw RecoveryStorageFailure() }
    }
    override fun replace(bytes: ByteArray) {
        try {
            require(bytes.size in 1..4096)
            check(directory.mkdirs() || directory.isDirectory)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
            val payload = byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
            replaceRecoveryCiphertext(target.baseFile, payload, AndroidJournalFiles)
        } catch (_: Exception) { throw RecoveryStorageFailure() }
    }
    override fun remove() {
        target.delete()
        if (listOf("", ".bak", ".new").any { File(target.baseFile.path + it).exists() }) throw RecoveryStorageFailure()
        try { AndroidJournalFiles.syncDirectory(directory) }
        catch (_: Exception) { throw RecoveryStorageFailure() }
    }
}

/** Checked operations: AtomicFile.finishWrite only logs certain sync/rename failures.
 * This writer retains AtomicFile's .new/read rollback protocol but does not use that void commit API. */
internal interface JournalFileOperations {
    fun syncFile(channel: FileChannel)
    fun rename(source: File, target: File)
    fun syncDirectory(directory: File)
}
private object AndroidJournalFiles : JournalFileOperations {
    override fun syncFile(channel: FileChannel) { channel.force(true) }
    override fun rename(source: File, target: File) { Os.rename(source.path, target.path) }
    override fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
}

/** Called under the shared RecoveryJournal lock. File, close, rename and directory-sync errors
 * all propagate before a caller may dispatch network I/O. A post-rename failure retains new
 * conservative evidence; a pre-rename failure retains the previous complete record. */
internal fun replaceRecoveryCiphertext(target: File, payload: ByteArray, operations: JournalFileOperations) {
    val pending = File(target.path + ".new")
    try {
        val directory = target.parentFile ?: throw RecoveryStorageFailure()
        require(payload.size in 30..4125)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw RecoveryStorageFailure()
        // Also make creation of the no-backup journal directory durable.
        operations.syncDirectory(directory.parentFile ?: throw RecoveryStorageFailure())
        FileChannel.open(pending.toPath(), setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.wrap(payload)
            while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw RecoveryStorageFailure()
            operations.syncFile(channel)
        }
        operations.rename(pending, target)
        operations.syncDirectory(directory)
    } catch (_: Exception) {
        // Only our temporary leaf; never erase the last committed journal or a symlink target.
        try { Files.deleteIfExists(pending.toPath()) } catch (_: Exception) { }
        throw RecoveryStorageFailure()
    }
}
