@file:OptIn(kotlin.time.ExperimentalTime::class)

package ph.edu.bsit.tcc.ojtdtr.auth

import android.content.Context
import io.ktor.client.engine.HttpClientEngine
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ph.edu.bsit.tcc.ojtdtr.BuildConfig
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Application authority: coordinator epochs plus revocable leases for SDK-owned background work. */
class AuthCoordinator internal constructor(context: Context,
    private val configuration: ClientConfiguration? = ClientConfiguration.parse(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_CLIENT_KEY),
    private val engineFactory: () -> HttpClientEngine? = { null },
    keyAlias: String = "dtr.native.auth.v1",
    private val beforeVerifierSave: suspend () -> Unit = {},
    private val beforeSessionDelete: suspend () -> Unit = {},
    private val onClientCreated: (SupabaseClient) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<AccountState>(AccountState.RestoringSession)
    val state = mutableState.asStateFlow()
    val configured = configuration != null
    private val epoch = AuthorizationEpoch()
    private val operationMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val storage = EncryptedAuthStorage(context, keyAlias)
    private val sessions = SecureSessionManager(storage)
    private val pkce = SecurePkceCache(storage)
    private var client: SupabaseClient? = null
    private val storageFence = SdkStorageFence()
    private var sessionObserver: Job? = null
    private var authorizedSession: UserSession? = null
    private var operation: Job? = null
    private var refresh: Job? = null
    private var expiry: Job? = null
    private enum class OAuthPhase { Idle, Waiting, Exchanging }
    private var phase = OAuthPhase.Idle

    init {
        operation = scope.launch {
            val generation = epoch.advance()
            try {
                val config = configuration
                if (config == null) {
                    publish(generation, AccountState.AccessUnavailable(Failure.Configuration))
                    return@launch
                }
                createClient()
                operationMutex.withLock {
                    val stored = sessions.loadSession()
                    if (stored != null) {
                        pkce.deleteCodeVerifier()
                        client!!.auth.importSession(stored, autoRefresh = false)
                        initialized.complete(Unit) // Logout need not wait for a profile request to finish.
                        resolveProfile(generation)
                        startRefresh(generation)
                    } else if (pkce.hasPending()) {
                        phase = OAuthPhase.Waiting
                        publish(generation, AccountState.Authenticating)
                        startExpiry(generation)
                    } else publish(generation, AccountState.SignedOut)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecureStorageFailure) { publish(generation, AccountState.AccessUnavailable(Failure.SecureStorage)) }
            catch (_: Exception) { publish(generation, AccountState.AccessUnavailable(Failure.Session, retryable = true)) }
            finally { initialized.complete(Unit) }
        }
    }

    private suspend fun createClient() {
        val config = configuration ?: return
        val owner = storageFence.replace() // Wait for an already-entered old storage operation to finish.
        val created = createSupabaseClient(config.url, config.clientKey) {
            httpEngine = engineFactory()
            defaultLogLevel = LogLevel.NONE
            install(Auth) {
                flowType = FlowType.PKCE
                scheme = "ph.edu.bsit.tcc.ojtdtr"
                host = "auth"
                sessionManager = storageFence.sessions(owner, sessions, beforeSessionDelete)
                codeVerifierCache = storageFence.pkce(owner, pkce, beforeVerifierSave)
                autoLoadFromStorage = false
                autoSaveToStorage = true
                alwaysAutoRefresh = false
            }
            install(Postgrest)
        }
        client = created
        onClientCreated(created)
        created.auth.awaitInitialization()
        sessionObserver = scope.launch {
            var acceptedSession = false
            created.auth.sessionStatus.collect { status ->
                if (client !== created) return@collect
                when (status) {
                    is SessionStatus.Authenticated -> {
                        acceptedSession = true
                        val authorized = authorizedSession
                        if (authorized != null && (authorized.user?.id != status.session.user?.id ||
                            authorized.accessToken != status.session.accessToken)) logout()
                    }
                    is SessionStatus.NotAuthenticated, is SessionStatus.RefreshFailure -> {
                        if (acceptedSession) logout() // Withdraw authority now; fence/clear and replace the client.
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun publish(generation: Long, value: AccountState) {
        if (epoch.isCurrent(generation)) mutableState.value = value
    }

    fun signIn(openCustomTab: (String) -> Unit) {
        if (mutableState.value != AccountState.SignedOut || !configured) return
        val generation = epoch.advance()
        mutableState.value = AccountState.Authenticating
        operation = scope.launch {
            initialized.await()
            operationMutex.withLock {
                var transaction: String? = null
                try {
                    val auth = client?.auth ?: throw IllegalStateException()
                    sessions.allowWrites()
                    val ownedTransaction = pkce.begin()
                    transaction = ownedTransaction
                    val url = auth.getOAuthUrl(Google, redirectUrl = CallbackPolicy.URI_VALUE) {
                        queryParams["prompt"] = "select_account"
                    }
                    val challenge = URI(url).rawQuery.split('&').map { it.split('=', limit = 2) }
                        .single { it[0] == "code_challenge" }[1].let { URLDecoder.decode(it, "UTF-8") }
                    pkce.bindChallenge(ownedTransaction, challenge)
                    pkce.awaitReady(ownedTransaction)
                    coroutineContext.ensureActive()
                    if (!epoch.isCurrent(generation)) return@withLock
                    phase = OAuthPhase.Waiting
                    openCustomTab(url) // URL is never retained in UI state or navigation.
                    startExpiry(generation)
                } catch (_: TimeoutCancellationException) {
                    // Setup readiness timing out is a failed attempt, not cancellation of this job.
                    val cleared = withContext(NonCancellable) { transaction?.let { discardTransaction(it) } ?: true }
                    if (epoch.isCurrent(generation)) phase = OAuthPhase.Idle
                    publish(generation, AccountState.AccessUnavailable(if (cleared) Failure.OAuth else Failure.SecureStorage, retryable = cleared))
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { transaction?.let { discardTransaction(it) } ?: true }
                    throw cancelled
                } catch (_: Exception) {
                    val cleared = transaction?.let { discardTransaction(it) } ?: true
                    if (epoch.isCurrent(generation)) phase = OAuthPhase.Idle
                    publish(generation, AccountState.AccessUnavailable(if (cleared) Failure.OAuth else Failure.SecureStorage))
                }
            }
        }
    }

    fun callback(raw: String) {
        scope.launch {
            initialized.await()
            // Duplicates/unsolicited callbacks cannot consume a verifier or replace an existing account.
            if (phase != OAuthPhase.Waiting) return@launch
            val parsed = CallbackPolicy.parse(raw)
            val generation = epoch.advance()
            expiry?.cancel()
            operation?.cancel()
            phase = OAuthPhase.Exchanging
            operation = scope.launch {
                operationMutex.withLock {
                    try {
                        if (parsed !is CallbackPolicy.Result.Code || !pkce.claim()) {
                            pkce.deleteCodeVerifier()
                            phase = OAuthPhase.Idle
                            publish(generation, AccountState.AccessUnavailable(Failure.Callback))
                            return@withLock
                        }
                        val auth = client?.auth ?: throw IllegalStateException()
                        // Exchange without auto-import. A cancelled/stale exchange never becomes an SDK session.
                        val session = auth.exchangeCodeForSession(parsed.value, saveSession = false)
                        coroutineContext.ensureActive()
                        if (!epoch.isCurrent(generation)) return@withLock
                        auth.importSession(session, autoRefresh = false)
                        resolveProfile(generation)
                        startRefresh(generation)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { publish(generation, AccountState.AccessUnavailable(Failure.OAuth)) }
                    finally {
                        val cleared = withContext(NonCancellable) { discardTransaction() }
                        phase = OAuthPhase.Idle
                        if (!cleared) publish(generation, AccountState.AccessUnavailable(Failure.SecureStorage))
                    }
                }
            }
        }
    }

    fun cancelAuthentication() {
        if (mutableState.value != AccountState.Authenticating) return
        logout()
    }

    fun retry() {
        val current = mutableState.value as? AccountState.AccessUnavailable ?: return
        if (!current.retryable || client == null) return
        val generation = epoch.advance()
        operation?.cancel(); refresh?.cancel()
        mutableState.value = AccountState.RestoringSession
        operation = scope.launch {
            initialized.await()
            operationMutex.withLock {
                try {
                    val auth = client!!.auth
                    if (auth.currentSessionOrNull() == null) {
                        val stored = sessions.loadSession()
                        if (stored == null) { publish(generation, AccountState.SignedOut); return@withLock }
                        auth.importSession(stored, autoRefresh = false)
                    }
                    resolveProfile(generation)
                    startRefresh(generation)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SecureStorageFailure) { publish(generation, AccountState.AccessUnavailable(Failure.SecureStorage)) }
                catch (_: Exception) { publish(generation, AccountState.AccessUnavailable(Failure.Session, retryable = true)) }
            }
        }
    }

    private suspend fun resolveProfile(generation: Long) {
        authorizedSession = null
        publish(generation, AccountState.LoadingProfile)
        val resolvingClient = client ?: return
        val auth = resolvingClient.auth
        var identity = auth.currentSessionOrNull()
        val user = try {
            val session = auth.currentSessionOrNull() ?: throw IllegalStateException()
            if (session.expiresAt <= Clock.System.now() + 60.seconds) auth.refreshCurrentSession()
            identity = auth.currentSessionOrNull() ?: throw IllegalStateException()
            val validated = auth.retrieveUserForCurrentSession(updateSession = false)
            check(validated.id.isNotBlank() && validated.id == auth.currentUserOrNull()?.id)
            validated
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecureStorageFailure) { publish(generation, AccountState.AccessUnavailable(Failure.SecureStorage)); return }
        catch (_: Exception) { publish(generation, AccountState.AccessUnavailable(Failure.Session, retryable = true)); return }
        val response = try {
            resolvingClient.from("profiles").select(columns = Columns.list("id", "role", "status")) {
                filter { eq("id", user.id) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(generation, AccountState.AccessUnavailable(Failure.ProfileRead, retryable = true)); return }
        val rows = try { response.decodeList<TrustedProfile>() }
        catch (_: Exception) { publish(generation, AccountState.AccessUnavailable(Failure.ProfileInvalid)); return }
        coroutineContext.ensureActive()
        val current = auth.currentSessionOrNull()
        if (client !== resolvingClient || current == null || current.user?.id != user.id ||
            current.accessToken != identity?.accessToken || auth.sessionStatus.value !is SessionStatus.Authenticated) {
            publish(generation, AccountState.AccessUnavailable(Failure.Session, retryable = true))
            return
        }
        if (!epoch.isCurrent(generation)) return
        authorizedSession = current
        publish(generation, ProfilePolicy.map(user.id, user.identities?.any { it.provider == "google" && it.userId == user.id } == true, rows))
    }

    private fun startRefresh(generation: Long) {
        refresh?.cancel()
        refresh = scope.launch {
            while (epoch.isCurrent(generation)) {
                delay(60_000)
                operationMutex.withLock {
                    if (!epoch.isCurrent(generation)) return@withLock
                    val session = client?.auth?.currentSessionOrNull()
                    if (session == null) { logout(); return@withLock }
                    if (session.expiresAt <= Clock.System.now() + 120.seconds) resolveProfile(generation)
                }
            }
        }
    }

    private fun startExpiry(generation: Long) {
        expiry?.cancel()
        expiry = scope.launch {
            try {
                delay(pkce.remainingMillis())
                if (epoch.isCurrent(generation) && phase == OAuthPhase.Waiting) {
                    val cleared = discardTransaction()
                    phase = OAuthPhase.Idle
                    publish(generation, AccountState.AccessUnavailable(if (cleared) Failure.OAuth else Failure.SecureStorage))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch.isCurrent(generation)) phase = OAuthPhase.Idle
                publish(generation, AccountState.AccessUnavailable(Failure.SecureStorage))
            }
        }
    }

    private suspend fun discardTransaction(id: String? = null): Boolean = try {
        if (id == null) pkce.deleteCodeVerifier() else pkce.discard(id)
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    internal suspend fun close() {
        scope.cancel()
        operation?.join(); refresh?.join(); expiry?.join()
        sessionObserver?.cancel()
        storageFence.retire()
        client?.close()
    }

    fun logout() {
        val generation = epoch.advance() // Immediately revoke all in-memory authorization.
        operation?.cancel(); refresh?.cancel(); expiry?.cancel()
        phase = OAuthPhase.Idle
        sessionObserver?.cancel()
        authorizedSession = null
        mutableState.value = AccountState.RestoringSession
        operation = scope.launch {
            initialized.await()
            try {
                storageFence.retire() // All old SDK writes/deletes are now inert, including delayed cleanup.
                // Clear/fence before waiting for any cancelled request to release the SDK mutation lock.
                var storageFailed = false
                try { sessions.blockAndClear() } catch (_: Exception) { storageFailed = true }
                try { pkce.deleteCodeVerifier() } catch (_: Exception) { storageFailed = true }
                operationMutex.withLock {
                    val previous = client
                    client = null
                    try { withTimeoutOrNull(5_000) { previous?.auth?.signOut(SignOutScope.LOCAL) } }
                    catch (_: Exception) { /* Native storage has already been cleared even if revocation fails. */ }
                    finally {
                        previous?.close() // Cancel its SDK scope; lease fences non-cancellable old work.
                    }
                    if (epoch.isCurrent(generation)) createClient()
                }
                publish(generation, if (storageFailed) AccountState.AccessUnavailable(Failure.SecureStorage) else AccountState.SignedOut)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(generation, AccountState.AccessUnavailable(Failure.SecureStorage)) }
        }
    }
}
