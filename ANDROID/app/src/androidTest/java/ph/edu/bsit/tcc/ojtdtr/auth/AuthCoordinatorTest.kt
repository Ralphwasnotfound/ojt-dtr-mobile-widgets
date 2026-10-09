@file:OptIn(kotlin.time.ExperimentalTime::class)

package ph.edu.bsit.tcc.ojtdtr.auth

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import ph.edu.bsit.tcc.ojtdtr.widget.WidgetPublication
import ph.edu.bsit.tcc.ojtdtr.widget.WidgetPresentation
import ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceState
import ph.edu.bsit.tcc.ojtdtr.attendance.Manila
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
        logoutStatus: HttpStatusCode = HttpStatusCode.NoContent,
        profileResponse: () -> String = { profiles }) = MockEngine { request ->
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
                respond(profileResponse(), status, headersOf("Content-Type", "application/json"))
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

    @Test fun explicitProfileRecheckUsesFreshServerStateAndClearsStudentIdentity() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val response = AtomicReference("[]")
        val owner = coordinator(engine("[]", profileResponse = { response.get() }))
        await(owner, AccountState.RegistrationRequired)
        // Opening or returning from an external page does not mutate native authority.
        val registration = ph.edu.bsit.tcc.ojtdtr.navigation.WebDestinations.urlFor(owner.state.value,
            ph.edu.bsit.tcc.ojtdtr.navigation.WebDestination.Registration)
        assertEquals("https://bsit-tcc-ojt-dtr.vercel.app/signup", registration)
        response.set(profile("pending"))
        delay(100); assertEquals(AccountState.RegistrationRequired, owner.state.value)
        owner.refreshProfile(); await(owner, AccountState.Pending)
        assertNull(owner.studentIdentity.value)
        response.set("[{\"id\":\"$userId\",\"role\":\"student\",\"status\":\"approved\",\"full_name\":\"Synthetic Student\",\"student_id\":\"SYNTHETIC-001\"}]")
        owner.refreshProfile(); await(owner, AccountState.StudentApproved)
        assertEquals(StudentIdentity("Synthetic Student", "SYNTHETIC-001"), owner.studentIdentity.value)
        response.set(profile("rejected"))
        owner.refreshProfile(); await(owner, AccountState.Rejected)
        assertNull(owner.studentIdentity.value)
        response.set(profile("unsupported"))
        owner.refreshProfile(); await(owner, AccountState.AccessUnavailable(Failure.ProfileInvalid))
        assertNull(owner.studentIdentity.value)
        owner.logout(); await(owner, AccountState.SignedOut)
        owner.refreshProfile(); delay(50); assertEquals(AccountState.SignedOut, owner.state.value)
    }

    @Test fun adminRecheckAndFailedStudentRecheckRemainFailClosed() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val response = AtomicReference(profile())
        val owner = coordinator(engine(profile(), profileResponse = { response.get() }))
        await(owner, AccountState.StudentApproved)
        response.set("[{\"id\":\"$userId\",\"role\":\"admin\",\"status\":\"approved\"}]")
        owner.refreshProfile(); await(owner, AccountState.AdminApproved)
        assertNull(owner.studentIdentity.value)
        response.set("[{\"id\":42}]")
        owner.refreshProfile(); await(owner, AccountState.AccessUnavailable(Failure.ProfileInvalid))
        owner.logout(); await(owner, AccountState.SignedOut)
        assertNull(storage.read("session")); assertNull(owner.studentIdentity.value)
    }

    /** Actual coordinator + SDK transport coverage; synthetic host only. */
    @Test fun attendanceUsesOwnedSessionAndDenialStopsBeforeRpc() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val account = AtomicReference("approved")
        val queries = AtomicInteger()
        val profileQuery = AtomicReference("")
        val clientCount = AtomicInteger()
        val mock = MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/user") -> Json.encodeToString(user)
                request.url.encodedPath.endsWith("/profiles") -> {
                    profileQuery.set(request.url.encodedQuery)
                    "[{\"id\":\"$userId\",\"role\":\"student\",\"status\":\"${account.get()}\",\"required_hours\":486}]"
                }
                request.url.encodedPath.endsWith("/rpc/attendance_summary") -> {
                    queries.incrementAndGet()
                    "[{\"open_session_id\":null,\"open_time_in\":null,\"open_session_ordinal\":null," +
                        "\"started_today\":false,\"starts_today\":0,\"next_action\":\"time_in\",\"today_sessions\":[]," +
                        "\"completed_seconds\":0,\"today_completed_seconds\":0,\"completed_sessions\":0,\"days_present\":0," +
                        "\"manila_day\":\"${java.time.LocalDate.now(ph.edu.bsit.tcc.ojtdtr.attendance.Manila)}\"}]"
                }
                else -> error("Unexpected synthetic attendance endpoint")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val owner = AuthCoordinator(context, config, { MockEngine(mock.config) }, alias,
            onClientCreated = { clientCount.incrementAndGet() }).also { owners += it }
        await(owner, AccountState.StudentApproved)
        withContext(kotlinx.coroutines.Dispatchers.Main) { owner.refreshAttendance() }
        withTimeout(10_000) { owner.attendance.state.first { it is ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceState.Fresh } }
        assertEquals(1, queries.get()); assertEquals(1, clientCount.get())
        assertTrue(profileQuery.get().contains("id=eq.$userId"))
        assertTrue(profileQuery.get().contains("required_hours"))
        // Normal coordinator construction can reach only the operation-level disabled adapter.
        withContext(Dispatchers.Main) {
            val ticket = owner.proofTicket()!!
            val submission = owner.createAttendanceSubmission(this, ticket)!!
            submission.submit(ph.edu.bsit.tcc.ojtdtr.proof.ConfirmedProof(byteArrayOf(1, 2, 3),
                ph.edu.bsit.tcc.ojtdtr.proof.Fix(14.0, 121.0, 8f, android.os.SystemClock.elapsedRealtime(), true)))
            submission.awaitOperations()
            assertEquals(ph.edu.bsit.tcc.ojtdtr.proof.SubmissionState.Disabled, submission.state.value)
            submission.close()
        }
        account.set("rejected")
        withContext(kotlinx.coroutines.Dispatchers.Main) { owner.refreshAttendance() }
        withTimeout(10_000) { owner.attendance.state.first { it == ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceState.AccessDenied } }
        assertEquals(1, queries.get()) // Known revoked profile never falls through to attendance.
    }

    @Test fun attendanceLogoutRejectsNonCooperativeSdkResponse() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val responseCompleted = CompletableDeferred<Unit>()
        val day = java.time.LocalDate.now(ph.edu.bsit.tcc.ojtdtr.attendance.Manila)
        val fixture = """[{"open_session_id":null,"open_time_in":null,"open_session_ordinal":null,
            "started_today":true,"starts_today":1,"next_action":"time_in","today_sessions":[{
            "id":"22222222-2222-4222-8222-222222222222","student_uid":"$userId",
            "time_in":"${day}T00:00:00Z","time_out":"${day}T00:45:00.250Z","start_day":"$day","session_ordinal":1}],
            "completed_seconds":9000.25,"today_completed_seconds":2700.25,"completed_sessions":3,"days_present":2,"manila_day":"$day"}]"""
        val ownProfile = profile().replace("}]", ",\"required_hours\":486}]")
        assertEquals(java.math.BigDecimal("2700.25"),
            ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceContract.parse(fixture, ownProfile, userId).todayCompletedSeconds)
        val mock = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/user") -> respond(Json.encodeToString(user), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                request.url.encodedPath.endsWith("/profiles") -> respond(profile().replace("}]", ",\"required_hours\":486}]"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                request.url.encodedPath.endsWith("/rpc/attendance_summary") -> {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    respond(fixture, HttpStatusCode.OK, headersOf("Content-Type", "application/json")).also {
                        responseCompleted.complete(Unit)
                    }
                }
                request.url.encodedPath.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                else -> error("Unexpected synthetic attendance endpoint")
            }
        }
        val owner = coordinator(mock)
        await(owner, AccountState.StudentApproved)
        withContext(kotlinx.coroutines.Dispatchers.Main) { owner.refreshAttendance() }
        withTimeout(10_000) { entered.await() }
        withContext(kotlinx.coroutines.Dispatchers.Main) { owner.logout() }
        assertEquals(ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceState.AccountChanged, owner.attendance.state.value)
        release.complete(Unit)
        withTimeout(10_000) {
            responseCompleted.await() // The delayed mock response was actually returned.
            owner.awaitAttendanceIdle() // The whole coordinator request exited, not just logout.
        }
        await(owner, AccountState.SignedOut)
        assertEquals(ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceState.AccountChanged, owner.attendance.state.value)
    }

    @Test fun successfulStudentReplacementWhileWidgetPublicationBlockedCannotRestoreStudentA() = runBlocking<Unit> {
        val studentB = "00000000-0000-4000-8000-000000000002"
        val active = AtomicReference(userId)
        val reads = AtomicInteger()
        val exchanges = AtomicInteger()
        fun currentUser() = UserInfo(aud = "authenticated", id = active.get())
        fun currentSession() = UserSession(accessToken = "synthetic-access-${active.get()}",
            refreshToken = "synthetic-refresh-${active.get()}", expiresIn = 3600, tokenType = "bearer",
            user = currentUser(), expiresAt = Clock.System.now() + 3600.seconds)
        SecureSessionManager(storage).saveSession(currentSession())
        val transport = MockEngine { request ->
            val uid = active.get()
            val body = when {
                request.url.encodedPath.endsWith("/user") -> Json.encodeToString(currentUser())
                request.url.encodedPath.endsWith("/token") -> {
                    exchanges.incrementAndGet(); Json.encodeToString(currentSession())
                }
                request.url.encodedPath.endsWith("/profiles") -> {
                    check(request.url.parameters["id"] == "eq.$uid")
                    """[{"id":"$uid","role":"student","status":"approved","required_hours":486}]"""
                }
                request.url.encodedPath.endsWith("/rpc/attendance_summary") -> {
                    reads.incrementAndGet()
                    val completed = if (uid == userId) 7200 else 14400
                    val day = java.time.Instant.now().atZone(Manila).toLocalDate()
                    """[{"open_session_id":null,"open_time_in":null,"open_session_ordinal":null,
                        "started_today":false,"starts_today":0,"next_action":"time_in","today_sessions":[],
                        "completed_seconds":$completed,"today_completed_seconds":0,"completed_sessions":2,"days_present":1,
                        "manila_day":"$day"}]"""
                }
                request.url.encodedPath.endsWith("/logout") -> ""
                else -> error("Unexpected synthetic replacement endpoint")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val owner = withContext(Dispatchers.Main) { coordinator(transport) }
        await(owner, AccountState.StudentApproved)
        val lifecycle = withContext(Dispatchers.Main) {
            owner.registerAttendanceLifecycle().also { owner.attendanceForeground(it, true) }
        }
        suspend fun fresh() = withTimeout(15000) {
            owner.attendance.state.first { it is AttendanceState.Fresh }; owner.awaitAttendanceIdle()
        }
        fresh()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val publishedB = CompletableDeferred<Unit>()
        val host = mutableMapOf<Int, WidgetPresentation>()
        val order = mutableListOf<String>()
        var blocked = false
        lateinit var publisher: WidgetPublication<Int>
        val observer = withContext(Dispatchers.Main) {
            publisher = WidgetPublication(current = { owner.widgetPresentation() }, instances = { listOf(1, 2) },
                update = { id ->
                    val captured = publisher.presentation.value
                    if (!blocked && captured.completed == "2h 0m") {
                        blocked = true; entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }
                    host[id] = captured
                    order += "$id:${captured.completed}"
                    if (host.size == 2 && host.values.all { it.completed == "4h 0m" }) publishedB.complete(Unit)
                })
            CoroutineScope(Dispatchers.Main).launch {
                publisher.observe(combine(owner.state, owner.attendance.state) { _, _ -> Unit })
            }
        }
        try {
            withTimeout(10000) { entered.await() }
            withContext(Dispatchers.Main) { owner.logout() }
            withTimeout(10000) { publisher.presentation.first { it.completed == null } }
            await(owner, AccountState.SignedOut)
            assertNull(storage.read("session"))
            active.set(studentB)
            val launched = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { owner.signIn { launched.complete(Unit) } }
            withTimeout(10000) { launched.await() }
            withContext(Dispatchers.Main) { owner.callback("${CallbackPolicy.URI_VALUE}?code=synthetic-student-b-code") }
            await(owner, AccountState.StudentApproved); fresh()
            withContext(Dispatchers.Main) { assertEquals("4h 0m", owner.widgetPresentation().completed) }
            assertEquals(studentB, SecureSessionManager(storage).loadSession()!!.user!!.id)
            assertEquals(1, exchanges.get()); assertNull(storage.read("pkce"))
            assertFalse(release.isCompleted)
            val beforeRelease = reads.get()
            release.complete(Unit)
            withTimeout(10000) { publishedB.await() }
            withContext(Dispatchers.Main) {
                assertEquals("4h 0m", publisher.presentation.value.completed)
                assertEquals("482h 0m", publisher.presentation.value.remaining)
                assertEquals(setOf(1, 2), host.keys)
                assertTrue(host.values.all { it.completed == "4h 0m" })
                val firstB = order.indexOfFirst { it.endsWith(":4h 0m") }
                assertTrue(firstB >= 0)
                assertFalse(order.drop(firstB).any { it.endsWith(":2h 0m") })
            }
            assertEquals(beforeRelease, reads.get())
            assertEquals(AccountState.StudentApproved, owner.state.value)
        } finally {
            release.complete(Unit); observer.cancelAndJoin()
            withContext(Dispatchers.Main) { owner.disposeAttendanceLifecycle(lifecycle) }
        }
    }

    @Test fun proofEntryRejectsEveryNonStudentAuthorization() = runBlocking<Unit> {
        for (status in listOf("pending", "rejected", "archived")) {
            SecureSessionManager(storage).saveSession(session())
            val owner = withContext(Dispatchers.Main) { coordinator(engine(profile(status))) }
            withTimeout(15000) { owner.state.first { it !in listOf(AccountState.RestoringSession, AccountState.LoadingProfile) } }
            withContext(Dispatchers.Main) { assertNull(owner.proofTicket()); owner.close() }
        }
        SecureSessionManager(storage).saveSession(session())
        val admin = withContext(Dispatchers.Main) { coordinator(engine(profile().replace("student", "admin"))) }
        await(admin, AccountState.AdminApproved)
        withContext(Dispatchers.Main) { assertNull(admin.proofTicket()); admin.logout() }
        await(admin, AccountState.SignedOut)
        withContext(Dispatchers.Main) { assertNull(admin.proofTicket()) }
    }
    @Test fun proofConfirmationRevalidatesApprovedProfileAndRevocationWithdrawsLease() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val response = AtomicReference(profile().replace("}]", ",\"required_hours\":486}]"))
        val owner = withContext(Dispatchers.Main) { coordinator(engine(response.get(), profileResponse = { response.get() })) }
        await(owner, AccountState.StudentApproved)
        var stopped = false
        withContext(Dispatchers.Main) {
            val ticket = owner.proofTicket()!!
            val remove = owner.registerProofCancellation(ticket) { stopped = true }
            assertTrue(owner.revalidateProof(ticket)); assertFalse(stopped)
            response.set(response.get().replace("approved", "rejected"))
            assertFalse(owner.revalidateProof(ticket))
            assertTrue(stopped); assertFalse(owner.currentProof(ticket)); assertNull(owner.proofTicket())
            remove()
        }
    }
    @Test fun logoutCancelsRealProofSessionAndDeletesNonCooperativeLateCapture() = runBlocking<Unit> {
        SecureSessionManager(storage).saveSession(session())
        val owner = withContext(Dispatchers.Main) { coordinator(engine(profile())) }
        await(owner, AccountState.StudentApproved)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        val proofRoot = File(root, "native-proof/device-proof")
        val camera = object : ph.edu.bsit.tcc.ojtdtr.proof.ProofCamera {
            override suspend fun capture(file: File) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await(); file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1)); done.complete(Unit) }
            }
            override fun stop() = Unit
        }
        val location = object : ph.edu.bsit.tcc.ojtdtr.proof.ProofLocation {
            override suspend fun acquire(): ph.edu.bsit.tcc.ojtdtr.proof.Fix = error("not requested")
            override fun stop() = Unit
        }
        val proof = withContext(Dispatchers.Main) {
            val ticket = owner.proofTicket()!!
            ph.edu.bsit.tcc.ojtdtr.proof.ProofSession(CoroutineScope(Dispatchers.Main), proofRoot, root, camera, location,
                { owner.currentProof(ticket) }, { owner.revalidateProof(ticket) }, { 1000 }, { it.isFile && it.length() > 0 }).also {
                owner.registerProofCancellation(ticket, it::close)
                it.capture(ph.edu.bsit.tcc.ojtdtr.proof.Permission.Granted)
            }
        }
        try {
            withTimeout(10000) { entered.await() }
            withContext(Dispatchers.Main) { owner.logout(); assertTrue(proof.state.value.closed) }
            release.complete(Unit); withTimeout(10000) { done.await(); proof.awaitOperations() }
            withContext(Dispatchers.Main) { assertFalse(proof.state.value.captured); assertEquals(0, proofRoot.walkTopDown().filter { it.isFile }.count()) }
            await(owner, AccountState.SignedOut)
        } finally { release.complete(Unit); withContext(Dispatchers.Main) { proof.close() } }
    }

    private suspend fun proofWithdrawalDuringBlockedWork(operation: String, withdrawal: String) {
        val studentB = "00000000-0000-4000-8000-000000000002"
        val active = AtomicReference(userId)
        val status = AtomicReference("approved")
        fun currentUser() = user.copy(id = active.get())
        fun currentSession() = session().copy(user = currentUser(), accessToken = "synthetic-proof-${active.get()}")
        SecureSessionManager(storage).saveSession(currentSession())
        val transport = MockEngine { request ->
            val uid = active.get()
            val body = when {
                request.url.encodedPath.endsWith("/user") -> Json.encodeToString(currentUser())
                request.url.encodedPath.endsWith("/token") -> Json.encodeToString(currentSession())
                request.url.encodedPath.endsWith("/profiles") -> {
                    check(request.url.parameters["id"] == "eq.$uid")
                    """[{"id":"$uid","role":"student","status":"${status.get()}","required_hours":486}]"""
                }
                request.url.encodedPath.endsWith("/logout") -> ""
                else -> error("Unexpected synthetic proof endpoint")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val owner = withContext(Dispatchers.Main) { coordinator(transport) }
        await(owner, AccountState.StudentApproved)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val proofRoot = File(root, "native-proof/old-proof")
        val lateCallback = CompletableDeferred<Unit>()
        var block = false
        val camera = object : ph.edu.bsit.tcc.ojtdtr.proof.ProofCamera {
            override suspend fun capture(file: File) {
                if (block && operation == "camera") {
                    entered.complete(Unit); withContext(NonCancellable) {
                        release.await(); file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1,2))
                        lateCallback.complete(Unit)
                    }
                } else { file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1,2)) }
            }
            override fun stop() = Unit
        }
        val location = object : ph.edu.bsit.tcc.ojtdtr.proof.ProofLocation {
            override suspend fun acquire(): ph.edu.bsit.tcc.ojtdtr.proof.Fix {
                if (block && operation == "gps") {
                    entered.complete(Unit)
                    return withContext(NonCancellable) {
                        release.await(); lateCallback.complete(Unit)
                        ph.edu.bsit.tcc.ojtdtr.proof.Fix(14.0,121.0,7f,1000,true)
                    }
                }
                return ph.edu.bsit.tcc.ojtdtr.proof.Fix(14.0,121.0,7f,1000,true)
            }
            override fun stop() = Unit
        }
        val ticket = withContext(Dispatchers.Main) { owner.proofTicket()!! }
        val proof = withContext(Dispatchers.Main) {
            ph.edu.bsit.tcc.ojtdtr.proof.ProofSession(CoroutineScope(Dispatchers.Main), proofRoot, root, camera, location,
                {owner.currentProof(ticket)}, {owner.revalidateProof(ticket)}, {1000}, {true})
        }
        val unregister = withContext(Dispatchers.Main) { owner.registerProofCancellation(ticket, proof::close) }
        var replacement: ph.edu.bsit.tcc.ojtdtr.proof.ProofSession? = null
        var unregisterB: (() -> Unit)? = null
        try {
            withContext(Dispatchers.Main) {
                proof.locate(ph.edu.bsit.tcc.ojtdtr.proof.Permission.Granted)
                proof.awaitOperations(); assertEquals(7f, proof.state.value.accuracy)
                if (operation == "gps") {
                    proof.capture(ph.edu.bsit.tcc.ojtdtr.proof.Permission.Granted)
                    proof.awaitOperations(); assertTrue(proof.state.value.captured)
                }
                block = true
                if (operation == "camera") proof.capture(ph.edu.bsit.tcc.ojtdtr.proof.Permission.Granted)
                else proof.locate(ph.edu.bsit.tcc.ojtdtr.proof.Permission.Granted)
            }
            withTimeout(10000) { entered.await() }
            if (withdrawal == "revocation") {
                status.set("rejected")
                withContext(Dispatchers.Main) { assertFalse(owner.revalidateProof(ticket)) }
            } else {
                withContext(Dispatchers.Main) { owner.logout() }
                await(owner, AccountState.SignedOut)
            }
            withContext(Dispatchers.Main) {
                assertTrue(proof.state.value.closed); assertFalse(owner.currentProof(ticket))
                assertNull(proof.previewFile()); assertNull(proof.state.value.accuracy)
            }
            if (withdrawal == "replacement") {
                active.set(studentB)
                val launched = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) { owner.signIn { launched.complete(Unit) } }
                withTimeout(10000) { launched.await() }
                withContext(Dispatchers.Main) { owner.callback("${CallbackPolicy.URI_VALUE}?code=synthetic-proof-student-b") }
                await(owner, AccountState.StudentApproved)
                assertEquals(studentB, SecureSessionManager(storage).loadSession()!!.user!!.id)
                withContext(Dispatchers.Main) {
                    val newTicket = owner.proofTicket()!!
                    assertNotSame(ticket, newTicket); assertFalse(owner.currentProof(ticket)); assertTrue(owner.currentProof(newTicket))
                    val freshCamera = object : ph.edu.bsit.tcc.ojtdtr.proof.ProofCamera {
                        override suspend fun capture(file: File) { file.writeBytes(byteArrayOf(3,4)) }
                        override fun stop() = Unit
                    }
                    val freshLocation = object : ph.edu.bsit.tcc.ojtdtr.proof.ProofLocation {
                        override suspend fun acquire() = ph.edu.bsit.tcc.ojtdtr.proof.Fix(15.0,122.0,11f,1000,false)
                        override fun stop() = Unit
                    }
                    replacement = ph.edu.bsit.tcc.ojtdtr.proof.ProofSession(CoroutineScope(Dispatchers.Main), File(root,"native-proof/new-proof"),root,
                        freshCamera,freshLocation,{owner.currentProof(newTicket)},{owner.revalidateProof(newTicket)},{1000},{true})
                    val newer = replacement!!
                    unregisterB = owner.registerProofCancellation(newTicket, newer::close)
                    assertFalse(newer.state.value.captured); assertNull(newer.state.value.accuracy); assertNull(newer.previewFile())
                    newer.capture(ph.edu.bsit.tcc.ojtdtr.proof.Permission.Granted); newer.awaitOperations()
                    newer.locate(ph.edu.bsit.tcc.ojtdtr.proof.Permission.Granted); newer.awaitOperations()
                    assertArrayEquals(byteArrayOf(3,4), newer.previewFile()!!.readBytes()); assertEquals(11f,newer.state.value.accuracy)
                }
            }
            assertFalse(release.isCompleted)
            release.complete(Unit)
            withTimeout(10000) { lateCallback.await(); proof.awaitOperations() } // Caller finally has completed, not merely fake callback.
            withContext(Dispatchers.Main) {
                assertTrue(proof.state.value.closed); assertFalse(proof.state.value.captured); assertNull(proof.state.value.accuracy)
                assertEquals(0,proofRoot.walkTopDown().filter {it.isFile}.count())
                replacement?.let {
                    assertTrue(it.allowed()); assertArrayEquals(byteArrayOf(3,4),it.previewFile()!!.readBytes())
                    assertEquals(11f,it.state.value.accuracy); assertEquals(false,it.state.value.precise)
                    assertEquals(AccountState.StudentApproved,owner.state.value)
                }
            }
        } finally {
            release.complete(Unit)
            withContext(Dispatchers.Main) { proof.close(); proof.awaitOperations(); replacement?.close(); unregister(); unregisterB?.invoke() }
        }
    }
    @Test fun approvedStudentReplacementDuringCapturePreservesNewProof() = runBlocking<Unit> { proofWithdrawalDuringBlockedWork("camera","replacement") }
    @Test fun approvedStudentReplacementDuringGpsPreservesNewProof() = runBlocking<Unit> { proofWithdrawalDuringBlockedWork("gps","replacement") }
    @Test fun logoutDuringGpsRejectsLateFix() = runBlocking<Unit> { proofWithdrawalDuringBlockedWork("gps","logout") }
    @Test fun revocationDuringGpsRejectsLateFix() = runBlocking<Unit> { proofWithdrawalDuringBlockedWork("gps","revocation") }
    @Test fun revocationDuringCaptureDeletesLateFile() = runBlocking<Unit> { proofWithdrawalDuringBlockedWork("camera","revocation") }

    /** Real coordinator/SDK leases and profile authorization; mutation adapter is test-only. */
    private suspend fun realSubmissionBarrier(stage: String, replacement: Boolean) {
        val studentB = "00000000-0000-4000-8000-000000000002"
        val active = AtomicReference(userId); val status = AtomicReference("approved")
        fun currentUser() = user.copy(id = active.get())
        fun currentSession() = session().copy(user = currentUser(), accessToken = "synthetic-submission-${active.get()}")
        SecureSessionManager(storage).saveSession(currentSession())
        val transport = MockEngine { request ->
            val uid = active.get()
            val body = when {
                request.url.encodedPath.endsWith("/user") -> Json.encodeToString(currentUser())
                request.url.encodedPath.endsWith("/token") -> Json.encodeToString(currentSession())
                request.url.encodedPath.endsWith("/profiles") -> {
                    check(request.url.parameters["id"] == "eq.$uid")
                    """[{"id":"$uid","role":"student","status":"${status.get()}","required_hours":486}]"""
                }
                request.url.encodedPath.endsWith("/rpc/attendance_summary") ->
                    """[{"open_session_id":null,"open_time_in":null,"open_session_ordinal":null,"started_today":false,
                    "starts_today":0,"next_action":"time_in","today_sessions":[],"completed_seconds":0,
                    "today_completed_seconds":0,"completed_sessions":0,"days_present":0,
                    "manila_day":"${java.time.LocalDate.now(Manila)}"}]"""
                request.url.encodedPath.endsWith("/logout") -> ""
                else -> error("Unexpected synthetic submission endpoint")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val owner = withContext(Dispatchers.Main) { coordinator(transport) }
        await(owner, AccountState.StudentApproved)
        withContext(Dispatchers.Main) { owner.refreshAttendance(); owner.awaitAttendanceIdle() }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
        val successfulOwners = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val upload = "00000000-0000-4000-8000-000000000007"
        val target = "00000000-0000-4000-8000-000000000008"
        val official = java.time.Instant.now()
        suspend fun before(uid: String, operation: String) {
            calls += "$uid:$operation"
            if (uid == userId && stage == "receipt" && operation == "finalize") throw java.io.IOException()
            if (uid == userId && operation == stage) withContext(NonCancellable) {
                entered.complete(Unit); release.await(); returned.complete(Unit)
            }
        }
        fun fakeBackend(uid: String) = object : ph.edu.bsit.tcc.ojtdtr.proof.ProofBackend {
            fun row() = """{"id":"00000000-0000-4000-8000-000000000009","upload_id":"$upload",
                "attendance_session_id":"$target","student_uid":"$uid","photo_path":"$uid/$target/$upload/proof",
                "action_type":"time_in","latitude":14,"longitude":121,"accuracy":8,
                "official_punch_at":"$official","attached_at":"$official"}"""
            override suspend fun prepare(requestId: String, action: ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceAction): String {
                before(uid, "prepare")
                return """[{"upload_id":"$upload","attendance_session_id":"$target","photo_path":"$uid/$target/$upload/proof",
                    "expires_at":"${java.time.Instant.now().plusSeconds(3600)}"}]"""
            }
            override suspend fun upload(bucket: String, path: String, jpeg: ByteArray) {
                assertEquals("attendance-proofs", bucket); assertEquals("$uid/$target/$upload/proof", path)
                before(uid, "upload")
            }
            override suspend fun finalize(uploadId: String, fix: ph.edu.bsit.tcc.ojtdtr.proof.Fix): String {
                before(uid, "finalize"); return row()
            }
            override suspend fun receipt(uploadId: String): String { before(uid, "receipt"); return "[${row()}]" }
        }
        fun operation(uid: String, ticket: Any) = ph.edu.bsit.tcc.ojtdtr.proof.AttendanceSubmission(
            CoroutineScope(Dispatchers.Main.immediate), fakeBackend(uid), uid,
            ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceAction.TimeIn,
            { owner.currentProof(ticket) }, { owner.permittedAttendanceAction() }, { owner.revalidateProof(ticket) },
            { successfulOwners += uid; owner.refreshAttendance(); owner.awaitAttendanceIdle() },
            android.os.SystemClock::elapsedRealtime)
        fun captured() = ph.edu.bsit.tcc.ojtdtr.proof.ConfirmedProof(byteArrayOf(1, 2, 3),
            ph.edu.bsit.tcc.ojtdtr.proof.Fix(14.0, 121.0, 8f, android.os.SystemClock.elapsedRealtime(), true))
        val ticket = withContext(Dispatchers.Main) { owner.proofTicket()!! }
        val old = withContext(Dispatchers.Main) { operation(userId, ticket) }
        val unregister = withContext(Dispatchers.Main) { owner.registerProofCancellation(ticket, old::close) }
        var newer: ph.edu.bsit.tcc.ojtdtr.proof.AttendanceSubmission? = null
        var unregisterB: (() -> Unit)? = null
        try {
            withContext(Dispatchers.Main) {
                old.submit(captured())
                if (stage == "receipt") { old.awaitOperations(); old.reconcile() }
            }
            withTimeout(10000) { entered.await() }
            if (replacement) {
                withContext(Dispatchers.Main) { owner.logout() }; await(owner, AccountState.SignedOut)
                active.set(studentB)
                val launch = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) { owner.signIn { launch.complete(Unit) } }
                withTimeout(10000) { launch.await() }
                withContext(Dispatchers.Main) { owner.callback("${CallbackPolicy.URI_VALUE}?code=synthetic-submission-b") }
                await(owner, AccountState.StudentApproved)
                withContext(Dispatchers.Main) {
                    owner.refreshAttendance(); owner.awaitAttendanceIdle()
                    val bTicket = owner.proofTicket()!!
                    assertNotSame(ticket, bTicket); assertFalse(owner.currentProof(ticket)); assertTrue(owner.currentProof(bTicket))
                    newer = operation(studentB, bTicket)
                    unregisterB = owner.registerProofCancellation(bTicket, newer!!::close)
                    newer!!.submit(captured()); newer!!.awaitOperations()
                    assertEquals(ph.edu.bsit.tcc.ojtdtr.proof.SubmissionState.Completed, newer!!.state.value)
                }
            } else {
                status.set("rejected")
                withContext(Dispatchers.Main) { assertFalse(owner.revalidateProof(ticket)) }
            }
            withContext(Dispatchers.Main) {
                assertFalse(owner.currentProof(ticket)); assertNull(owner.createAttendanceSubmission(this, ticket))
            }
            release.complete(Unit)
            withTimeout(10000) { returned.await(); old.awaitOperations() }
            withContext(Dispatchers.Main) {
                assertNotEquals(ph.edu.bsit.tcc.ojtdtr.proof.SubmissionState.Completed, old.state.value)
                assertEquals(if (replacement) listOf(studentB) else emptyList<String>(), successfulOwners)
                if (stage == "prepare") assertFalse(calls.contains("$userId:upload"))
                if (stage == "upload") assertFalse(calls.contains("$userId:finalize"))
                if (replacement) {
                    assertEquals(AccountState.StudentApproved, owner.state.value)
                    assertEquals(studentB, SecureSessionManager(storage).loadSession()!!.user!!.id)
                    assertEquals(ph.edu.bsit.tcc.ojtdtr.proof.SubmissionState.Completed, newer!!.state.value)
                }
            }
        } finally {
            release.complete(Unit)
            withContext(Dispatchers.Main) { old.close(); old.awaitOperations(); newer?.close(); unregister(); unregisterB?.invoke() }
        }
    }
    @Test fun realCoordinatorRevocationDuringSubmissionPrepare() = runBlocking<Unit> { realSubmissionBarrier("prepare", false) }
    @Test fun realCoordinatorRevocationDuringSubmissionUpload() = runBlocking<Unit> { realSubmissionBarrier("upload", false) }
    @Test fun realCoordinatorRevocationDuringSubmissionFinalize() = runBlocking<Unit> { realSubmissionBarrier("finalize", false) }
    @Test fun realCoordinatorReplacementRejectsLateFinalize() = runBlocking<Unit> { realSubmissionBarrier("finalize", true) }
    @Test fun realCoordinatorReplacementRejectsLateReceipt() = runBlocking<Unit> { realSubmissionBarrier("receipt", true) }

    @Test fun manualSamsungDeviceOnlyProofHardware() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("proofManual") == "true")
        SecureSessionManager(storage).saveSession(session())
        val owner = withContext(Dispatchers.Main) { coordinator(engine(profile().replace("}]", ",\"required_hours\":486}]"))) }
        await(owner, AccountState.StudentApproved)
        val finished = CompletableDeferred<Unit>()
        androidx.test.core.app.ActivityScenario.launch(ph.edu.bsit.tcc.ojtdtr.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                ph.edu.bsit.tcc.ojtdtr.ui.theme.OjtDtrTheme {
                    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    val account by owner.state.collectAsState()
                    if (open) ph.edu.bsit.tcc.ojtdtr.proof.ProofScreen(owner) { open = false }
                    else androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text("Synthetic approved account — hardware verification only")
                        androidx.compose.material3.Text("No production account, upload or attendance mutation")
                        androidx.compose.material3.Button(onClick = { open = true }, enabled = account == AccountState.StudentApproved) {
                            androidx.compose.material3.Text("Open device preview")
                        }
                        androidx.compose.material3.Button(onClick = owner::logout) { androidx.compose.material3.Text("Sign out synthetic account") }
                        androidx.compose.material3.Button(onClick = { finished.complete(Unit) }) { androidx.compose.material3.Text("Finish hardware check") }
                    }
                }
            } }
            withTimeout(600000) { finished.await() }
        }
    }

}
