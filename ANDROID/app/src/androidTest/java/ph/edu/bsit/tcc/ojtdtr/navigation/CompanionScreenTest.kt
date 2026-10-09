package ph.edu.bsit.tcc.ojtdtr.navigation

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import java.io.File
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import ph.edu.bsit.tcc.ojtdtr.MainActivity
import ph.edu.bsit.tcc.ojtdtr.R
import ph.edu.bsit.tcc.ojtdtr.attendance.*
import java.time.Instant
import ph.edu.bsit.tcc.ojtdtr.auth.AccountState
import ph.edu.bsit.tcc.ojtdtr.auth.Failure
import ph.edu.bsit.tcc.ojtdtr.auth.StudentIdentity
import ph.edu.bsit.tcc.ojtdtr.ui.screen.CompanionScreen
import ph.edu.bsit.tcc.ojtdtr.ui.theme.OjtDtrTheme

/** Real Compose rendering/accessibility on the emulator; no new test dependencies or hosted HTTP. */
class CompanionScreenTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun label(id: Int) = instrumentation.targetContext.getString(id)
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun texts() = nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text?.toString() }
    private fun waitFor(text: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (text !in texts() && System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync(); Thread.sleep(20)
        }
        assertTrue("Expected companion label: $text", text in texts())
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "u7.5c-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    private fun click(text: String) {
        waitFor(text)
        var node = nodes(instrumentation.uiAutomation.rootInActiveWindow).first { it.text?.toString() == text }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun render(state: MutableStateFlow<AccountState>, web: (WebDestination) -> Unit = {},
        refresh: () -> Unit = {}, attendance: MutableStateFlow<AttendanceState> = MutableStateFlow(AttendanceState.AccountChanged)): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also {
        it.onActivity { activity ->
            activity.setContent {
                OjtDtrTheme {
                    val account by state.collectAsState()
                    val dtr by attendance.collectAsState()
                    CompanionScreen(account, StudentIdentity("Synthetic UI Student", "SYNTHETIC-UI"), true,
                        onGoogle = {}, onCancel = {}, onRetry = {},
                        onSignOut = { state.value = AccountState.SignedOut }, onRefresh = refresh,
                        attendance = dtr, onAttendanceRefresh = {},
                        onWeb = web, onFoundation = {})
                }
            }
        }
    }

    @Test fun onlyApprovedStatesRenderTheirCompanionAndDtrHasNoInventedValues() {
        val state = MutableStateFlow<AccountState>(AccountState.StudentApproved)
        render(state).use { scenario ->
            waitFor(label(R.string.student_companion)); waitFor("Synthetic UI Student")
            waitFor("Attendance requires a current approved student account.")
            capture("student")
            scenario.onActivity { state.value = AccountState.AdminApproved }
            waitFor(label(R.string.admin_web_only))
            capture("admin")
            assertFalse(label(R.string.student_companion) in texts()); assertFalse("Synthetic UI Student" in texts())
            for ((account, message) in listOf(AccountState.Pending to R.string.pending_explanation,
                AccountState.Rejected to R.string.rejected_explanation,
                AccountState.AccessUnavailable(Failure.ProfileInvalid) to R.string.auth_access_unavailable)) {
                scenario.onActivity { state.value = account }
                waitFor(label(message))
                assertFalse(label(R.string.student_companion) in texts())
                assertFalse(label(R.string.admin_companion) in texts())
                assertFalse("Synthetic UI Student" in texts())
                assertFalse(label(R.string.open_registration) in texts())
            }
        }
    }

    @Test fun registrationHandoffAndReturnDoNotGrantAccessAndRefreshIsExplicit() {
        val state = MutableStateFlow<AccountState>(AccountState.RegistrationRequired)
        val destination = AtomicReference<WebDestination>()
        val refreshes = AtomicInteger()
        render(state, web = { destination.set(it) }, refresh = { refreshes.incrementAndGet() }).use {
            waitFor(label(R.string.open_registration)); capture("registration")
            click(label(R.string.open_registration))
            assertEquals(WebDestination.Registration, destination.get())
            assertEquals(AccountState.RegistrationRequired, state.value)
            assertFalse(label(R.string.student_companion) in texts())
            click(label(R.string.refresh_account))
            instrumentation.waitForIdleSync()
            assertEquals(1, refreshes.get()); assertEquals(AccountState.RegistrationRequired, state.value)
        }
    }

    @Test fun logoutImmediatelyRemovesApprovedContent() {
        val state = MutableStateFlow<AccountState>(AccountState.StudentApproved)
        render(state).use {
            click(label(R.string.auth_sign_out))
            waitFor(label(R.string.auth_signed_out))
            assertFalse(label(R.string.student_companion) in texts())
            assertFalse("Synthetic UI Student" in texts())
        }
    }
    @Test fun attendanceRendersValidatedDataAndIsolatesNonStudentAccounts() {
        val uid = "11111111-1111-4111-8111-111111111111"
        val raw = """[{"open_session_id":null,"open_time_in":null,"open_session_ordinal":null,
            "started_today":false,"starts_today":0,"next_action":"time_in","today_sessions":[],
            "completed_seconds":0,"today_completed_seconds":0,"completed_sessions":0,"days_present":0,"manila_day":"2026-10-09"}]"""
        val profile = """[{"id":"$uid","role":"student","status":"approved","required_hours":486}]"""
        val summary = AttendanceContract.parse(raw, profile, uid)
        val state = MutableStateFlow<AccountState>(AccountState.StudentApproved)
        val attendance = MutableStateFlow<AttendanceState>(AttendanceState.Fresh(summary, Instant.parse("2026-10-09T04:00:00Z")))
        render(state, attendance = attendance).use { scenario ->
            waitFor("Status: OUT"); waitFor("Today's completed hours: 0h 0m")
            waitFor("Required hours: 486")
            assertFalse("Time In" in texts()); assertFalse("Time Out" in texts())
            scenario.onActivity { attendance.value = AttendanceState.AccessDenied }
            waitFor("Attendance access denied. Refresh your account status or sign in again.")
            assertFalse("Required hours: 486" in texts())
            for (account in listOf(AccountState.AdminApproved, AccountState.Pending, AccountState.Rejected, AccountState.SignedOut)) {
                scenario.onActivity {
                    attendance.value = AttendanceState.Fresh(summary, Instant.parse("2026-10-09T04:00:00Z"))
                    state.value = account
                }
                instrumentation.waitForIdleSync()
                assertFalse("Required hours: 486" in texts())
                assertFalse("Status: OUT" in texts())
            }
        }
    }

}
