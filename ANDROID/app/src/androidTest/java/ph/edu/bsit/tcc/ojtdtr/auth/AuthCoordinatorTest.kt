@file:OptIn(kotlin.time.ExperimentalTime::class)

package ph.edu.bsit.tcc.ojtdtr.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.Identity
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Real SDK + real Keystore + fake HTTP only. No requests are made to a hosted project. */
class AuthCoordinatorTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var alias: String
    private lateinit var storage: EncryptedAuthStorage
    private val owners = mutableListOf<AuthCoordinator>()
    private val config = ClientConfiguration.parse("https://test.invalid", "sb_publishable_" + "synthetic".repeat(4))!!
    private val userId = "00000000-0000-4000-8000-000000000001"
    private val user get() = UserInfo(aud = "authenticated", id = userId, identities = listOf(
        Identity(id = "test", identityData = buildJsonObject { }, provider = "google", userId = userId)))
    private fun session(expired: Boolean = false) = UserSession(accessToken = "synthetic-native-access",
        refreshToken = "synthetic-native-refresh", expiresIn = 3600, tokenType = "bearer", user = user,
        expiresAt = Clock.System.now() + if (expired) (-1).seconds else 3600.seconds)
    private fun profile(status: String = "approved") = "[{\"id\":\"$userId\",\"role\":\"student\",\"status\":\"$status\"}]"
    @Before fun prepare() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        root = File(app.noBackupFilesDir, "coordinator-test-$id").apply { mkdirs() }
        context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = root }
        alias = "dtr.auth.test.$id"
        storage = EncryptedAuthStorage(context, alias)
    }
    @After fun cleanup() = runBlocking<Unit> {
        owners.forEach { it.close() }
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    private fun coordinator(engine: MockEngine) = AuthCoordinator(context, config, { MockEngine(engine.config) }, alias).also { owners += it }
    private suspend fun await(owner: AuthCoordinator, expected: AccountState) = withTimeout(15_000) {
        owner.state.first { it == expected }
    }
    private fun engine(profiles: String, status: HttpStatusCode = HttpStatusCode.OK,
        tokens: AtomicInteger = AtomicInteger(), query: CompletableDeferred<String>? = null,
        entered: CompletableDeferred<Unit>? = null, release: CompletableDeferred<Unit>? = null,
        logoutStatus: HttpStatusCode = HttpStatusCode.NoContent) = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/user") -> respond(Json.encodeToString(user), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            path.endsWith("/token") -> {
                tokens.incrementAndGet()
                respond(Json.encodeToString(session()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            path.endsWith("/profiles") -> {
                query?.complete(request.url.encodedQuery)
                entered?.complete(Unit)
                // Deliberately finish an old response after cancellation to exercise the authorization fence.
                if (release != null) withContext(NonCancellable) { release.await() }
                respond(profiles, status, headersOf("Content-Type", "application/json"))
            }
            path.endsWith("/logout") -> respond(if (logoutStatus == HttpStatusCode.NoContent) "" else "{\"message\":\"synthetic revocation failure\"}", logoutStatus, headersOf("Content-Type", "application/json"))
            else -> error("Unexpected synthetic test endpoint")
        }
    }
    @Test fun restorationReadsOnlyOwnProfileAndUsesFreshStatus() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val query = CompletableDeferred<String>()
        val owner = coordinator(engine(profile("pending"), query = query))
        await(owner, AccountState.Pending)
        val actual = query.await()
        assertTrue(actual.contains("id=eq.$userId")); assertTrue(actual.contains("select=id"))
        assertFalse(actual.contains("email")); assertFalse(actual.contains("*"))
    }
    @Test fun failedReadNeverMeansMissingRegistration() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val owner = coordinator(engine("{\"message\":\"synthetic error\"}", HttpStatusCode.InternalServerError))
        await(owner, AccountState.AccessUnavailable(Failure.ProfileRead, retryable = true))
        assertNotEquals(AccountState.RegistrationRequired, owner.state.value)
    }
    @Test fun successfulMissingGoogleProfileRequiresRegistration() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val owner = coordinator(engine("[]"))
        await(owner, AccountState.RegistrationRequired)
    }
    @Test fun unknownAndMalformedProfileFailClosed() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val first = coordinator(engine(profile("archived")))
        await(first, AccountState.AccessUnavailable(Failure.ProfileInvalid)); first.close()
        val second = coordinator(engine("[{\"id\":42}]"))
        await(second, AccountState.AccessUnavailable(Failure.ProfileInvalid))
    }
    @Test fun logoutClearsSessionAndOldProfileCannotReauthorize() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = coordinator(engine(profile(), entered = entered, release = release))
        withTimeout(15_000) { entered.await() }
        owner.logout()
        withTimeout(5_000) { while (storage.read("session") != null) delay(10) }
        assertNotEquals(AccountState.StudentApproved, owner.state.value)
        release.complete(Unit)
        await(owner, AccountState.SignedOut)
        delay(100)
        assertEquals(AccountState.SignedOut, owner.state.value)
        assertNull(storage.read("session")); assertNull(storage.read("pkce"))
    }
    @Test fun remoteRevocationFailureStillClearsNativeSessionAndAuthorization() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val owner = coordinator(engine(profile(), logoutStatus = HttpStatusCode.InternalServerError))
        await(owner, AccountState.StudentApproved)
        owner.logout(); await(owner, AccountState.SignedOut)
        assertNull(storage.read("session")); assertNull(storage.read("pkce"))
        owner.callback("${CallbackPolicy.URI_VALUE}?code=unsolicited-code-123")
        delay(50); assertEquals(AccountState.SignedOut, owner.state.value)
    }
    @Test fun expiredRestoredSessionRefreshesBeforeAuthorization() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session(expired = true))
        val tokens = AtomicInteger()
        val owner = coordinator(engine(profile(), tokens = tokens))
        await(owner, AccountState.StudentApproved)
        assertEquals(1, tokens.get())
        assertTrue(SecureSessionManager(storage).loadSession()!!.expiresAt > Clock.System.now())
    }
    @Test fun pkceOAuthUsesSelectAccountAndDuplicateCallbackExchangesOnlyOnce() = runBlocking<Unit> {
        val tokens = AtomicInteger()
        val owner = coordinator(engine(profile(), tokens = tokens))
        await(owner, AccountState.SignedOut)
        val launched = CompletableDeferred<String>()
        owner.signIn { launched.complete(it) }
        val url = withTimeout(10_000) { launched.await() }
        assertTrue(url.contains("provider=google")); assertTrue(url.contains("prompt=select_account"))
        assertTrue(url.contains("code_challenge_method=s256")); assertFalse(url.contains("access_token"))
        owner.callback("${CallbackPolicy.URI_VALUE}?code=synthetic-code-1234")
        owner.callback("${CallbackPolicy.URI_VALUE}?code=synthetic-code-1234")
        await(owner, AccountState.StudentApproved)
        assertEquals(1, tokens.get()); assertNull(storage.read("pkce"))
        owner.logout(); await(owner, AccountState.SignedOut)
        assertNull(storage.read("session")); assertNull(storage.read("pkce"))
    }
    @Test fun coldCallbackUsesEncryptedTransactionFromPreviousOwner() = runBlocking<Unit> {
        val first = coordinator(engine(profile()))
        await(first, AccountState.SignedOut)
        val launched = CompletableDeferred<Unit>()
        first.signIn { launched.complete(Unit) }
        withTimeout(10_000) { launched.await() }
        first.close() // Closing the client does not delete a transaction awaiting the browser.
        val tokens = AtomicInteger()
        val restarted = coordinator(engine(profile(), tokens = tokens))
        restarted.callback("${CallbackPolicy.URI_VALUE}?code=synthetic-cold-code")
        await(restarted, AccountState.StudentApproved)
        assertEquals(1, tokens.get()); assertNull(storage.read("pkce"))
    }
    @Test fun invalidCallbackAndCancellationClearPendingTransaction() = runBlocking<Unit> {
        val tokens = AtomicInteger()
        val owner = coordinator(engine(profile(), tokens = tokens))
        await(owner, AccountState.SignedOut)
        val launched = CompletableDeferred<Unit>()
        owner.signIn { launched.complete(Unit) }; withTimeout(10_000) { launched.await() }
        owner.callback("${CallbackPolicy.URI_VALUE}#access_token=synthetic")
        await(owner, AccountState.AccessUnavailable(Failure.Callback))
        assertEquals(0, tokens.get()); assertNull(storage.read("pkce")); assertNull(storage.read("session"))
        owner.logout(); await(owner, AccountState.SignedOut)
        val secondLaunch = CompletableDeferred<Unit>()
        owner.signIn { secondLaunch.complete(Unit) }; withTimeout(10_000) { secondLaunch.await() }
        owner.cancelAuthentication(); await(owner, AccountState.SignedOut)
        assertNull(storage.read("pkce")); assertNull(storage.read("session"))
    }
    @Test fun setupTimeoutRemovesOwnedTransactionAndExitsAuthenticating() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val tokens = AtomicInteger()
        val fake = engine(profile(), tokens = tokens)
        val owner = AuthCoordinator(context, config, { MockEngine(fake.config) }, alias,
            beforeVerifierSave = { release.await() }).also { owners += it }
        await(owner, AccountState.SignedOut)
        var browserOpened = false
        owner.signIn { browserOpened = true }
        await(owner, AccountState.AccessUnavailable(Failure.OAuth, retryable = true))
        assertFalse(browserOpened)
        assertNull(storage.read("pkce")); assertNull(storage.read("session"))
        release.complete(Unit) // Late SDK save cannot resurrect the timed-out transaction.
        owner.callback("${CallbackPolicy.URI_VALUE}?code=synthetic-late-timeout")
        delay(100)
        assertNull(storage.read("pkce")); assertEquals(0, tokens.get())
        assertNotEquals(AccountState.Authenticating, owner.state.value)
        owner.retry(); await(owner, AccountState.SignedOut)
    }

    @Test fun oldSdkInvalidationCannotClearReplacementAndCurrentInvalidationWithdrawsApproval() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val clients = mutableListOf<SupabaseClient>()
        val invalidUser = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val deletes = AtomicInteger()
        val replacementUser = user.copy(id = "00000000-0000-4000-8000-000000000002")
        val replacement = session().copy(user = replacementUser, accessToken = "synthetic-replacement-access",
            refreshToken = "synthetic-replacement-refresh")
        val owner = AuthCoordinator(context, config, {
            MockEngine { request ->
                val jsonHeaders = headersOf("Content-Type", "application/json")
                when {
                    request.url.encodedPath.endsWith("/user") && invalidUser.get() ->
                        respond("{\"error_code\":\"session_not_found\",\"msg\":\"synthetic missing session\"}", HttpStatusCode.Unauthorized, jsonHeaders)
                    request.url.encodedPath.endsWith("/user") -> respond(Json.encodeToString(
                        if (clients.size == 1) user else replacementUser), HttpStatusCode.OK, jsonHeaders)
                    request.url.encodedPath.endsWith("/token") -> respond(Json.encodeToString(replacement), HttpStatusCode.OK, jsonHeaders)
                    request.url.encodedPath.endsWith("/profiles") -> respond(
                        if (clients.size == 1) profile() else "[{\"id\":\"${replacementUser.id}\",\"role\":\"admin\",\"status\":\"approved\"}]", HttpStatusCode.OK, jsonHeaders)
                    request.url.encodedPath.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                    else -> error("Unexpected synthetic endpoint")
                }
            }
        }, alias, beforeSessionDelete = {
            if (deletes.incrementAndGet() == 1) withContext(NonCancellable) {
                entered.complete(Unit); release.await()
            }
        }, onClientCreated = { clients += it }).also { owners += it }
        await(owner, AccountState.StudentApproved)
        val old = clients.single()
        invalidUser.set(true)
        assertTrue(runCatching { old.auth.retrieveUserForCurrentSession(updateSession = false) }.isFailure)
        withTimeout(5_000) { entered.await() } // Real SDK session_not_found background cleanup is delayed.
        owner.logout(); await(owner, AccountState.SignedOut)
        invalidUser.set(false)
        val launched = CompletableDeferred<Unit>()
        owner.signIn { launched.complete(Unit) }; withTimeout(10_000) { launched.await() }
        owner.callback("${CallbackPolicy.URI_VALUE}?code=synthetic-replacement-code")
        await(owner, AccountState.AdminApproved)
        release.complete(Unit)
        withTimeout(5_000) { old.auth.sessionStatus.first { it is io.github.jan.supabase.auth.status.SessionStatus.NotAuthenticated } }
        delay(100)
        assertEquals(AccountState.AdminApproved, owner.state.value)
        assertEquals(replacementUser.id, clients.last().auth.currentUserOrNull()?.id)
        assertEquals(replacement.accessToken, SecureSessionManager(storage).loadSession()?.accessToken)
        // Stale SDK persistence is fenced in both directions, not just deletes.
        old.auth.importSession(session(), autoRefresh = false)
        assertEquals(AccountState.AdminApproved, owner.state.value)
        old.auth.sessionManager.saveSession(session())
        assertEquals(replacement.accessToken, SecureSessionManager(storage).loadSession()?.accessToken)
        clients.last().auth.clearSession()
        await(owner, AccountState.SignedOut)
        assertNull(storage.read("session")); assertNull(storage.read("pkce"))
        assertNotEquals(AccountState.AdminApproved, owner.state.value)
    }

    @Test fun sessionDisappearingDuringProfileReadCannotPublishApproval() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val fake = engine(profile(), entered = entered, release = release)
        lateinit var sdk: SupabaseClient
        val owner = AuthCoordinator(context, config, { MockEngine(fake.config) }, alias,
            onClientCreated = { sdk = it }).also { owners += it }
        withTimeout(10_000) { entered.await() }
        sdk.auth.clearSession()
        assertNotEquals(AccountState.StudentApproved, owner.state.value)
        release.complete(Unit)
        await(owner, AccountState.SignedOut)
        assertNull(storage.read("session"))
    }

}
