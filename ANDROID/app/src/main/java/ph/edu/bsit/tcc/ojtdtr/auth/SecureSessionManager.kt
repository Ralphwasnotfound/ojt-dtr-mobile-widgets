package ph.edu.bsit.tcc.ojtdtr.auth

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SecureSessionManager(private val storage: EncryptedAuthStorage) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var writesAllowed = true
    fun allowWrites() = synchronized(lock) { writesAllowed = true }
    // The fence and delete share the save lock: an in-flight write cannot outlive this operation.
    suspend fun blockAndClear() = withContext(Dispatchers.IO) {
        synchronized(lock) { writesAllowed = false; storage.delete("session") }
    }
    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (writesAllowed) storage.write("session", json.encodeToString(session).toByteArray(Charsets.UTF_8))
        }
    }
    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            storage.read("session")?.let { bytes ->
                try { json.decodeFromString<UserSession>(bytes.toString(Charsets.UTF_8)) }
                catch (_: Exception) { storage.delete("session"); throw SecureStorageFailure() }
                finally { bytes.fill(0) }
            }
        }
    }
    override suspend fun deleteSession() = withContext(Dispatchers.IO) {
        synchronized(lock) { storage.delete("session") }
    }
}
