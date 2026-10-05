package ph.edu.bsit.tcc.ojtdtr.auth

import io.github.jan.supabase.auth.CodeVerifierCache
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private class OAuthTransaction(val id: String, val createdAt: Long, val challenge: String? = null,
    val verifier: String? = null, val claimed: Boolean = false)

/** One short-lived, encrypted, single-consumer transaction; no callback can start a transaction. */
class SecurePkceCache(private val storage: EncryptedAuthStorage, private val now: () -> Long = System::currentTimeMillis) : CodeVerifierCache {
    private val lock = Any()
    private val json = Json
    @Volatile private var verifierSaveFailed = false
    private fun read(): OAuthTransaction? {
        val bytes = storage.read("pkce") ?: return null
        val transaction = try { json.decodeFromString<OAuthTransaction>(bytes.toString(Charsets.UTF_8)) }
        catch (_: Exception) { storage.delete("pkce"); throw SecureStorageFailure() }
        finally { bytes.fill(0) }
        if (!CallbackPolicy.fresh(transaction.createdAt, now())) { storage.delete("pkce"); return null }
        return transaction
    }
    private fun write(transaction: OAuthTransaction) = storage.write("pkce", json.encodeToString(transaction).toByteArray(Charsets.UTF_8))
    suspend fun begin(): String = withContext(Dispatchers.IO) { synchronized(lock) {
        verifierSaveFailed = false
        OAuthTransaction(UUID.randomUUID().toString(), now()).also(::write).id
    } }
    suspend fun bindChallenge(id: String, challenge: String) = withContext(Dispatchers.IO) { synchronized(lock) {
        val tx = read() ?: throw SecureStorageFailure()
        check(tx.id == id && !tx.claimed && tx.challenge == null)
        write(OAuthTransaction(tx.id, tx.createdAt, challenge))
    } }
    // SDK 3.2.6 saves the verifier asynchronously. Match its S256 challenge before launching a browser.
    // A delayed save from a cancelled transaction can never populate a later transaction.
    override suspend fun saveCodeVerifier(codeVerifier: String) = withContext(Dispatchers.IO) {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII)))
        try { withTimeout(5_000) {
            while (true) {
                val finished = synchronized(lock) {
                    val tx = read()
                    when {
                        tx == null || tx.claimed -> true
                        tx.challenge == null -> false
                        tx.challenge != challenge -> true
                        else -> { write(OAuthTransaction(tx.id, tx.createdAt, tx.challenge, codeVerifier)); true }
                    }
                }
                if (finished) break
                delay(10)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // The SDK invokes this from its own launch. Never let disk/key failure crash that coroutine.
            verifierSaveFailed = true
        }
    }
    suspend fun awaitReady(id: String) = withTimeout(5_000) {
        while (true) {
            if (verifierSaveFailed) throw SecureStorageFailure()
            if (withContext(Dispatchers.IO) { synchronized(lock) { read()?.let { it.id == id && it.verifier != null && !it.claimed } == true } }) break
            delay(10)
        }
    }
    suspend fun hasPending(): Boolean = withContext(Dispatchers.IO) { synchronized(lock) {
        val tx = read() ?: return@synchronized false
        // A process interrupted during exchange cannot safely repeat it.
        if (tx.claimed || tx.verifier == null) { storage.delete("pkce"); false } else true
    } }
    suspend fun remainingMillis(): Long = withContext(Dispatchers.IO) { synchronized(lock) {
        read()?.let { (it.createdAt + CallbackPolicy.TTL_MILLIS - now()).coerceAtLeast(1L) } ?: 1L
    } }
    suspend fun claim(): Boolean = withContext(Dispatchers.IO) { synchronized(lock) {
        val tx = read() ?: return@synchronized false
        if (tx.claimed || tx.verifier == null) return@synchronized false
        write(OAuthTransaction(tx.id, tx.createdAt, tx.challenge, tx.verifier, claimed = true)); true
    } }
    override suspend fun loadCodeVerifier(): String? = withContext(Dispatchers.IO) { synchronized(lock) {
        read()?.takeIf { it.claimed }?.verifier
    } }
    override suspend fun deleteCodeVerifier() = withContext(Dispatchers.IO) { synchronized(lock) { storage.delete("pkce") } }
}
