@file:OptIn(kotlin.time.ExperimentalTime::class)
package ph.edu.bsit.tcc.ojtdtr.auth

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Text
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import ph.edu.bsit.tcc.ojtdtr.MainActivity
import ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceState
import ph.edu.bsit.tcc.ojtdtr.attendance.Manila
import ph.edu.bsit.tcc.ojtdtr.navigation.CompanionNavigation
import ph.edu.bsit.tcc.ojtdtr.ui.theme.OjtDtrTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Real lifecycle + production coordinator/repository, isolated Keystore and synthetic HTTP. */
class AttendanceLifecycleIntegrationTest {
    private lateinit var root: File
    private lateinit var alias: String
    private lateinit var owner: AuthCoordinator
    private lateinit var sdk: SupabaseClient
    private val uid = "11111111-1111-4111-8111-111111111111"
    private val user = UserInfo(aud = "authenticated", id = uid)
    private val status = AtomicReference("approved")
    private val mode = AtomicReference("normal")
    private val profiles = AtomicInteger()
    private val summaries = AtomicInteger()
    private val wall = AtomicReference(java.time.Instant.now())
    private val elapsed = java.util.concurrent.atomic.AtomicLong()
    private data class Wake(val ms: Long, val release: CompletableDeferred<Unit>, val active: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(true))
    private val wakes = kotlinx.coroutines.channels.Channel<Wake>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val pulse = mutableIntStateOf(0)
    private var profileEntered = CompletableDeferred<Unit>()
    private var profileRelease = CompletableDeferred<Unit>()
    private val summaryEntered = CompletableDeferred<Unit>()
    private val summaryRelease = CompletableDeferred<Unit>()
    private val summaryCompleted = CompletableDeferred<Unit>()

    @Before fun prepare() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        root = File(app.noBackupFilesDir, "attendance-lifecycle-test-$id").apply { mkdirs() }
        alias = "dtr.auth.test.$id"
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = root }
        SecureSessionManager(EncryptedAuthStorage(context, alias)).saveSession(UserSession(
            accessToken = "synthetic-native-access", refreshToken = "synthetic-native-refresh",
            expiresIn = 3600, tokenType = "bearer", user = user, expiresAt = Clock.System.now() + 3600.seconds))
        val engine = MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/user") -> Json.encodeToString(user)
                request.url.encodedPath.endsWith("/profiles") -> {
                    val authorizationRead = request.url.parameters["select"]?.contains("required_hours") != true
                    if (authorizationRead) {
                        profiles.incrementAndGet()
                        if (mode.get() == "blocked") { profileEntered.complete(Unit); profileRelease.await() }
                        if (mode.get() == "offline") throw java.io.IOException()
                    }
                    """[{"id":"$uid","role":"student","status":"${status.get()}","required_hours":486}]"""
                }
                request.url.encodedPath.endsWith("/rpc/attendance_summary") -> {
                    summaries.incrementAndGet()
                    if (mode.get() == "summaryBlocked") { summaryEntered.complete(Unit); withContext(NonCancellable) { summaryRelease.await() } }
                    """[{"open_session_id":null,"open_time_in":null,"open_session_ordinal":null,
                        "started_today":false,"starts_today":0,"next_action":"time_in","today_sessions":[],
                        "completed_seconds":0,"today_completed_seconds":0,"completed_sessions":0,"days_present":0,
                        "manila_day":"${wall.get().atZone(Manila).toLocalDate()}"}]"""
                }
                request.url.encodedPath.endsWith("/logout") -> ""
                else -> error("Unexpected synthetic lifecycle endpoint")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json")).also {
                if (request.url.encodedPath.endsWith("/rpc/attendance_summary") && mode.get() == "summaryBlocked") summaryCompleted.complete(Unit)
            }
        }
        withContext(Dispatchers.Main) {
            owner = AuthCoordinator(context,
                ClientConfiguration.parse("https://test.invalid", "sb_publishable_" + "synthetic".repeat(4)),
                { MockEngine(engine.config) }, alias, onClientCreated = { sdk = it }, attendanceClock = { wall.get() }, attendanceElapsed = { elapsed.get() },
                attendanceWait = { ms -> val wake = Wake(ms, CompletableDeferred()); wakes.send(wake); try { wake.release.await() } finally { wake.active.set(false) } })
        }
        withTimeout(15_000) { owner.state.first { it == AccountState.StudentApproved } }
    }
    @After fun cleanup() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { owner.close() }
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    private fun attach(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity -> activity.setContent {
            OjtDtrTheme {
                Text("Lifecycle test ${pulse.intValue}")
                CompanionNavigation(owner, {}, {})
            }
        } }
    }
    private suspend fun fresh() = withTimeout(15_000) {
        owner.attendance.state.first { it is AttendanceState.Fresh }
        owner.awaitAttendanceIdle()
    }
    @Test fun realResumeRecreationAndRecompositionRevalidateOnce() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario); fresh()
            val firstReads = summaries.get(); val firstProfiles = profiles.get()
            mode.set("summaryBlocked")
            scenario.onActivity { repeat(20) { pulse.intValue++; owner.refreshAttendance() } }
            withTimeout(10_000) { summaryEntered.await() }
            assertEquals(firstReads + 1, summaries.get())
            summaryRelease.complete(Unit); fresh(); mode.set("normal")
            // Manual requests coalesce while I/O is active; recomposition/resume alone add none.
            val beforePulse = summaries.get()
            scenario.onActivity { repeat(20) { pulse.intValue++ ;  } }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(beforePulse, summaries.get()); assertEquals(firstProfiles, profiles.get())
            scenario.moveToState(Lifecycle.State.CREATED)
            val beforeResume = summaries.get()
            scenario.moveToState(Lifecycle.State.RESUMED); fresh()
            assertEquals(beforeResume + 1, summaries.get()); assertEquals(firstProfiles + 1, profiles.get())
            scenario.recreate(); attach(scenario); fresh()
            assertEquals(firstProfiles + 2, profiles.get())
            assertTrue(summaries.get() >= firstReads + 2)
        }
    }
    @Test fun revocationDuringForegroundProfileValidationWithdrawsAttendance() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario); fresh(); scenario.moveToState(Lifecycle.State.CREATED)
            mode.set("blocked"); scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(10_000) { profileEntered.await() }
            assertNotEquals(AccountState.StudentApproved, owner.state.value)
            assertEquals(AttendanceState.AccountChanged, owner.attendance.state.value)
            val before = summaries.get(); status.set("rejected"); profileRelease.complete(Unit)
            withTimeout(10_000) { owner.state.first { it == AccountState.Rejected } }
            assertEquals(before, summaries.get()); assertEquals(AttendanceState.AccountChanged, owner.attendance.state.value)
        }
    }
    @Test fun offlineForegroundRevalidationDoesNotClaimCurrentApproval() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario); fresh(); scenario.moveToState(Lifecycle.State.CREATED)
            val before = summaries.get(); mode.set("offline"); scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(10_000) { owner.state.first { it == AccountState.AccessUnavailable(Failure.ProfileRead, true) } }
            assertEquals(before, summaries.get()); assertEquals(AttendanceState.AccountChanged, owner.attendance.state.value)
        }
    }
    @Test fun sdkInvalidationImmediatelyClearsForegroundAttendance() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario); fresh()
            withContext(Dispatchers.Main) { sdk.auth.clearSession() }
            withTimeout(10_000) { owner.state.first { it == AccountState.SignedOut } }
            assertEquals(AttendanceState.AccountChanged, owner.attendance.state.value)
        }
    }
    private suspend fun nextWake(): Wake = withTimeout(10000) {
        var wake = wakes.receive()
        while (!wake.active.get()) wake = wakes.receive()
        wake
    }
    private class ScreenOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun overlap(scenario: ActivityScenario<MainActivity>, a: ScreenOwner, b: ScreenOwner,
                        showA: androidx.compose.runtime.MutableState<Boolean>) {
        scenario.onActivity { activity ->
            a.registry.currentState = Lifecycle.State.RESUMED
            b.registry.currentState = Lifecycle.State.RESUMED
            activity.setContent { OjtDtrTheme {
                if (showA.value) CompositionLocalProvider(LocalLifecycleOwner provides a) { CompanionNavigation(owner, {}, {}) }
                CompositionLocalProvider(LocalLifecycleOwner provides b) { CompanionNavigation(owner, {}, {}) }
            } }
        }
    }
    @Test fun overlappingNavigationDisposalKeepsAutomaticExpiryAlive() = runBlocking<Unit> {
        val a = ScreenOwner(); val b = ScreenOwner(); val showA = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            overlap(scenario, a, b, showA); fresh()
            val expiry = nextWake()
            val reads = summaries.get(); val validations = profiles.get()
            scenario.onActivity { showA.value = false }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            withContext(Dispatchers.Main) {
                wall.set(wall.get().plusMillis(expiry.ms)); elapsed.addAndGet(expiry.ms); expiry.release.complete(Unit)
            }
            withTimeout(10000) { owner.attendance.state.first { it is AttendanceState.Stale } }
            assertEquals(reads, summaries.get()); assertEquals(validations, profiles.get())
            scenario.onActivity { b.registry.currentState = Lifecycle.State.CREATED }
            withContext(Dispatchers.Main) { owner.logout() }
            withTimeout(10000) { owner.state.first { it == AccountState.SignedOut } }
            assertEquals(AttendanceState.AccountChanged, owner.attendance.state.value)
        }
    }
    @Test fun overlappingNavigationMidnightCompletesQueuedCoordinatorFollowup() = runBlocking<Unit> {
        val a = ScreenOwner(); val b = ScreenOwner(); val showA = mutableStateOf(true)
        wall.set(wall.get().atZone(Manila).toLocalDate().atTime(23,59,59).atZone(Manila).toInstant())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            overlap(scenario, a, b, showA); fresh()
            nextWake().also { assertEquals(1000L, it.ms) }
            scenario.onActivity { a.registry.currentState = Lifecycle.State.CREATED }
            mode.set("summaryBlocked")
            withContext(Dispatchers.Main) { owner.refreshAttendance() }
            withTimeout(10000) { summaryEntered.await() }
            val reads = summaries.get()
            val midnight = nextWake(); assertEquals(1000L, midnight.ms)
            withContext(Dispatchers.Main) {
                wall.set(wall.get().plusSeconds(1)); elapsed.addAndGet(1000); midnight.release.complete(Unit)
            }
            // Wait for the boundary handler to schedule its next timer before releasing I/O.
            nextWake()
            mode.set("normal"); summaryRelease.complete(Unit)
            withTimeout(10000) {
                owner.attendance.state.first { it is AttendanceState.Fresh && summaries.get() == reads + 1 }
                owner.awaitAttendanceIdle()
            }
            assertEquals(reads + 1, summaries.get())
            // A replacement SDK identity cannot retain the old student's attendance, even with two owners.
            scenario.onActivity { a.registry.currentState = Lifecycle.State.RESUMED }
            withContext(Dispatchers.Main) {
                sdk.auth.importSession(UserSession(accessToken = "synthetic-replacement-access", refreshToken = "synthetic-replacement-refresh",
                    expiresIn = 3600, tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "33333333-3333-4333-8333-333333333333"),
                    expiresAt = Clock.System.now() + 3600.seconds), autoRefresh = false)
            }
            withTimeout(10000) { owner.state.first { it == AccountState.SignedOut } }
            assertEquals(AttendanceState.AccountChanged, owner.attendance.state.value)
        }
    }

    @Test fun logoutDuringAttendanceWithBothNavigationOwnersRejectsLateData() = runBlocking<Unit> {
        val a = ScreenOwner(); val b = ScreenOwner(); val showA = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            overlap(scenario, a, b, showA); fresh(); mode.set("summaryBlocked")
            withContext(Dispatchers.Main) { owner.refreshAttendance() }
            withTimeout(10000) { summaryEntered.await() }
            withContext(Dispatchers.Main) { owner.logout() }
            assertEquals(AttendanceState.AccountChanged, owner.attendance.state.value)
            summaryRelease.complete(Unit)
            withTimeout(10000) { summaryCompleted.await(); owner.awaitAttendanceIdle(); owner.state.first { it == AccountState.SignedOut } }
            assertEquals(AttendanceState.AccountChanged, owner.attendance.state.value)
        }
    }

}
