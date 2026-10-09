package ph.edu.bsit.tcc.ojtdtr.widget

import org.junit.Test
import org.junit.Assert.*
import ph.edu.bsit.tcc.ojtdtr.auth.AccountState
import ph.edu.bsit.tcc.ojtdtr.attendance.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class WidgetPresentationTest {
    private val summary = AttendanceSummary(LocalDate.parse("2026-10-09"), emptyList(), null, null, null,
        AttendanceAction.TimeIn, BigDecimal("7200"), BigDecimal.ZERO, 2, 1, 486)
    private val fresh = AttendanceState.Fresh(summary, Instant.parse("2026-10-09T04:00:00Z"))
    private fun derive(account: AccountState = AccountState.StudentApproved, state: AttendanceState = fresh, current: Boolean = true) =
        WidgetPresentation.derive(account, state, current)
    @Test fun signedOutHasNoOldValues() { assertEquals(WidgetPresentation("Unavailable — sign in to view attendance"), derive(AccountState.SignedOut)) }
    @Test fun approvedCurrentSnapshotProjectsOnlyMinimalValues() {
        val view = derive(); assertEquals("2h 0m", view.completed); assertEquals("484h 0m", view.remaining)
        assertEquals("Not timed in", view.status); assertTrue(view.message.startsWith("Stale"))
    }
    @Test fun pendingIsIsolated() { assertNull(derive(AccountState.Pending).completed) }
    @Test fun rejectedIsIsolated() { assertNull(derive(AccountState.Rejected).completed) }
    @Test fun adminIsIsolated() { assertNull(derive(AccountState.AdminApproved).completed) }
    @Test fun withdrawnAuthorizationHasNoValues() { assertEquals(WidgetPresentation.Unknown, derive(AccountState.LoadingProfile)) }
    @Test fun oldAccountBindingCannotPublish() { assertEquals(WidgetPresentation.Unknown, derive(current = false)) }
    @Test fun sdkInvalidationCannotPublish() { assertNull(derive(current = false).status) }
    @Test fun expiredSnapshotNeverClaimsFresh() {
        assertEquals(derive(), derive(state = AttendanceState.Stale(summary, fresh.fetchedAt)))
        assertFalse(derive().message.contains("Fresh"))
    }
    @Test fun absentSnapshotDoesNotInventZeros() { assertNull(derive(state = AttendanceState.AccountChanged).completed) }
    @Test fun deniedOrInvalidResponseDoesNotRetainData() {
        assertNull(derive(state = AttendanceState.AccessDenied).completed)
        assertNull(derive(state = AttendanceState.Unavailable(AttendanceProblem.InvalidResponse)).remaining)
    }
    @Test fun malformedSummaryFailsClosed() {
        assertEquals(WidgetPresentation.Unknown, derive(state = AttendanceState.Fresh(summary.copy(requiredHours = 0), fresh.fetchedAt)))
    }
    @Test fun widgetInstancesHaveIdenticalSafeProjectionAndNoInstanceCredentials() {
        assertEquals(derive(), derive())
        assertEquals(setOf("message", "completed", "remaining", "status", "checked"),
            WidgetPresentation::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }
    @Test fun openOvernightSessionDoesNotAddElapsedDeviceTimeToCompletedHours() {
        val open = summary.copy(openSessionId = "22222222-2222-4222-8222-222222222222",
            openTimeIn = Instant.parse("2026-10-08T14:00:00Z"), openOrdinal = 1, nextAction = AttendanceAction.TimeOut)
        val view = derive(state = AttendanceState.Fresh(open, fresh.fetchedAt))
        assertEquals("Currently timed in", view.status)
        assertEquals("2h 0m", view.completed); assertEquals("484h 0m", view.remaining)
        assertTrue(open.overnight)
    }
    @Test fun validNoAttendanceUsesServerZeroAndFullRequiredHours() {
        val empty = summary.copy(completedSeconds = BigDecimal.ZERO, completedSessions = 0, daysPresent = 0)
        val view = derive(state = AttendanceState.Fresh(empty, fresh.fetchedAt))
        assertEquals("0h 0m", view.completed); assertEquals("486h 0m", view.remaining)
        assertEquals("Not timed in", view.status)
    }
    @Test fun refreshingRetainsOnlyTrustedStaleSnapshotAndSuccessfulRefreshTime() {
        assertEquals(derive(), derive(state = AttendanceState.Refreshing(summary, fresh.fetchedAt)))
        assertEquals("Oct 9, 12:00 PM", derive().checked)
    }
    @Test fun deviceTimezoneDoesNotChangeManilaDisplay() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertEquals("Oct 9, 12:00 PM", derive().checked)
        } finally { java.util.TimeZone.setDefault(previous) }
    }

}
