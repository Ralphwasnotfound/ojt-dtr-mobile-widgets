@file:OptIn(kotlin.time.ExperimentalTime::class)

package ph.edu.bsit.tcc.ojtdtr.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SecureAuthenticationTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var alias: String
    private lateinit var storage: EncryptedAuthStorage
    @Before fun prepare() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        root = File(app.noBackupFilesDir, "auth-test-$id").apply { mkdirs() }
        context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = root }
        alias = "dtr.auth.test.$id"
        storage = EncryptedAuthStorage(context, alias)
    }
    @After fun cleanup() {
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    private fun sample() = UserSession(accessToken = "synthetic-access-not-a-real-token", refreshToken = "synthetic-refresh-not-a-real-token", expiresIn = 3600, tokenType = "bearer")
    private fun disk(name: String) = File(root, "native-auth/$name.enc")
    private fun challenge(verifier: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    @Test fun sessionIsEncryptedRestorableAndKeyIsNonExportable() = runBlocking {
        val session = sample()
        SecureSessionManager(storage).saveSession(session)
        val onDisk = disk("session").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains(session.accessToken)); assertFalse(onDisk.contains(session.refreshToken)); assertFalse(onDisk.contains("expires_in"))
        assertEquals(session, SecureSessionManager(EncryptedAuthStorage(context, alias)).loadSession())
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        assertNull(key.encoded)
    }
    @Test fun encryptionUsesFreshNonceAndAuthenticatesFileType() {
        storage.write("session", "synthetic".toByteArray())
        val first = disk("session").readBytes()
        storage.write("session", "synthetic".toByteArray())
        assertFalse(first.contentEquals(disk("session").readBytes()))
        disk("pkce").writeBytes(disk("session").readBytes())
        try { storage.read("pkce"); fail("Swapped ciphertext must fail") } catch (_: SecureStorageFailure) { }
        assertFalse(disk("pkce").exists())
    }
    @Test fun ciphertextTamperingFailsClosedAndDeletesDamagedSession() = runBlocking {
        val manager = SecureSessionManager(storage)
        manager.saveSession(sample())
        val bytes = disk("session").readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        disk("session").writeBytes(bytes)
        try { manager.loadSession(); fail("Tampering must fail") } catch (_: SecureStorageFailure) { }
        assertFalse(disk("session").exists()); assertNull(manager.loadSession())
    }
    @Test fun logoutFenceRejectsLateSessionPersistence() = runBlocking {
        val manager = SecureSessionManager(storage)
        manager.saveSession(sample())
        manager.blockAndClear()
        manager.saveSession(sample()) // A cancelled network operation trying to save after logout.
        assertNull(manager.loadSession()); assertFalse(disk("session").exists())
        manager.allowWrites(); manager.saveSession(sample()); assertNotNull(manager.loadSession())
    }
    @Test fun encryptedPkceSurvivesRestartIsClaimedOnceAndIsConsumed() = runBlocking {
        val verifier = "synthetic-verifier-" + "a".repeat(48)
        val first = SecurePkceCache(storage)
        val id = first.begin(); first.bindChallenge(id, challenge(verifier)); first.saveCodeVerifier(verifier); first.awaitReady(id)
        assertFalse(disk("pkce").readBytes().toString(Charsets.ISO_8859_1).contains(verifier))
        val restarted = SecurePkceCache(EncryptedAuthStorage(context, alias))
        assertTrue(restarted.hasPending()); assertNull(restarted.loadCodeVerifier())
        assertTrue(restarted.claim()); assertFalse(restarted.claim()); assertEquals(verifier, restarted.loadCodeVerifier())
        restarted.deleteCodeVerifier(); assertNull(restarted.loadCodeVerifier()); assertFalse(disk("pkce").exists())
    }
    @Test fun expiredCancelledAndInterruptedTransactionsCannotBeReused() = runBlocking {
        var clock = 1000L
        val verifier = "synthetic-verifier-" + "b".repeat(48)
        val cache = SecurePkceCache(storage) { clock }
        suspend fun prepare() { val id = cache.begin(); cache.bindChallenge(id, challenge(verifier)); cache.saveCodeVerifier(verifier) }
        prepare(); clock += CallbackPolicy.TTL_MILLIS; assertFalse(cache.claim()); assertFalse(disk("pkce").exists())
        prepare(); cache.deleteCodeVerifier(); assertFalse(cache.claim())
        prepare(); assertTrue(cache.claim())
        assertFalse(SecurePkceCache(storage) { clock }.hasPending()); assertFalse(disk("pkce").exists())
    }
    @Test fun asynchronousVerifierStorageFailureDoesNotEscapeSdkCoroutine() = runBlocking {
        val verifier = "synthetic-verifier-" + "e".repeat(48)
        val cache = SecurePkceCache(storage)
        val id = cache.begin(); cache.bindChallenge(id, challenge(verifier))
        val bytes = disk("pkce").readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        disk("pkce").writeBytes(bytes)
        cache.saveCodeVerifier(verifier) // SDK background save must not throw and crash the application.
        try { cache.awaitReady(id); fail("Failed persistence cannot launch a browser") } catch (_: SecureStorageFailure) { }
        assertFalse(cache.claim()); assertFalse(disk("pkce").exists())
    }
    @Test fun lateVerifierCannotPopulateReplacementTransaction() = runBlocking {
        val old = "old-synthetic-verifier-" + "c".repeat(48)
        val replacement = "new-synthetic-verifier-" + "d".repeat(48)
        val cache = SecurePkceCache(storage)
        val first = cache.begin(); cache.bindChallenge(first, challenge(old))
        cache.deleteCodeVerifier()
        val next = cache.begin(); cache.bindChallenge(next, challenge(replacement))
        cache.saveCodeVerifier(old); assertFalse(cache.claim())
        cache.saveCodeVerifier(replacement); assertTrue(cache.claim()); assertEquals(replacement, cache.loadCodeVerifier())
    }
}
