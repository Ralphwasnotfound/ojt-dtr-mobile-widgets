package ph.edu.bsit.tcc.ojtdtr.auth

import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** SDK background jobs have no coordinator epoch. Each client gets a revocable storage lease. */
internal class SdkStorageFence {
    private val mutex = Mutex()
    private var current: Any? = null

    suspend fun replace(): Any = mutex.withLock { Any().also { current = it } }
    suspend fun retire() = mutex.withLock { current = null }

    fun sessions(owner: Any, delegate: SessionManager, beforeDelete: suspend () -> Unit): SessionManager =
        object : SessionManager {
            override suspend fun saveSession(session: UserSession) = mutex.withLock {
                if (current === owner) delegate.saveSession(session)
            }
            override suspend fun loadSession(): UserSession? = mutex.withLock {
                if (current === owner) delegate.loadSession() else null
            }
            override suspend fun deleteSession() {
                beforeDelete()
                mutex.withLock { if (current === owner) delegate.deleteSession() }
            }
        }

    fun pkce(owner: Any, delegate: CodeVerifierCache, beforeSave: suspend () -> Unit): CodeVerifierCache =
        object : CodeVerifierCache {
            override suspend fun saveCodeVerifier(codeVerifier: String) {
                beforeSave()
                mutex.withLock { if (current === owner) delegate.saveCodeVerifier(codeVerifier) }
            }
            override suspend fun loadCodeVerifier(): String? = mutex.withLock {
                if (current === owner) delegate.loadCodeVerifier() else null
            }
            override suspend fun deleteCodeVerifier() = mutex.withLock {
                if (current === owner) delegate.deleteCodeVerifier()
            }
        }
}
