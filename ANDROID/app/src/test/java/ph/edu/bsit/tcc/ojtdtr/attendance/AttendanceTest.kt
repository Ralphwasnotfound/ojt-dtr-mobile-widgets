package ph.edu.bsit.tcc.ojtdtr.attendance

import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AttendanceTest {
    private val uid = "11111111-1111-4111-8111-111111111111"
    private val id = "22222222-2222-4222-8222-222222222222"
    private val now = Instant.parse("2026-10-09T04:00:00Z")
    private fun profile(role: String = "student", status: String = "approved", owner: String = uid) =
        """[{"id":"$owner","role":"$role","status":"$status","required_hours":486}]"""
    private fun raw() = """[{"open_session_id":null,"open_time_in":null,"started_today":true,
        "completed_seconds":3600.5,"completed_sessions":1,"manila_day":"2026-10-09","starts_today":1,
        "next_action":"time_in","today_sessions":[{"id":"$id","student_uid":"$uid",
        "time_in":"2026-10-09T00:00:00Z","time_out":"2026-10-09T01:00:00.500Z",
        "start_day":"2026-10-09","session_ordinal":1}],"open_session_ordinal":null,
        "today_completed_seconds":3600.5,"days_present":1}]"""
    private fun change(key: String, value: JsonElement?, source: String = raw()): String {
        val row = (Json.parseToJsonElement(source) as JsonArray).single().jsonObject.toMutableMap()
        if (value == null) row.remove(key) else row[key] = value
        return JsonArray(listOf(JsonObject(row))).toString()
    }
    private fun summary(raw: String = raw(), profile: String = profile()) = AttendanceContract.parse(raw, profile, uid)
    private fun rejected(raw: String) {
        try { summary(raw); fail("accepted malformed response") }
        catch (failure: AttendanceReadFailure) { assertEquals(AttendanceProblem.InvalidResponse, failure.problem) }
    }
    @Test fun validSummaryAndExactRemainingHours() {
        val s = summary()
        assertEquals(BigDecimal("3600.5"), s.todayCompletedSeconds)
        assertEquals(BigDecimal("1745999.5"), s.remainingSeconds)
        assertNull(s.openSessionId)
        assertEquals(AttendanceAction.TimeIn, s.nextAction)
    }
    @Test fun everyRequiredSummaryFieldMustBePresent() {
        Json.parseToJsonElement(raw()).jsonArray.single().jsonObject.keys.forEach { rejected(change(it, null)) }
    }
    @Test fun malformedFieldTypesAndCardinalityAreRejected() {
        listOf("{}", "[]", "[null]", "[{},{}]", "invalid").forEach(::rejected)
        rejected(change("completed_seconds", JsonPrimitive("3600.5")))
        rejected(change("completed_seconds", JsonPrimitive(-1)))
        rejected(change("starts_today", JsonPrimitive(1.5)))
        rejected(change("started_today", JsonPrimitive("true")))
        rejected(change("manila_day", JsonPrimitive("2026-02-30")))
        rejected(change("today_sessions", JsonNull))
        rejected(change("open_session_id", JsonPrimitive(id)))
        rejected(change("today_completed_seconds", JsonPrimitive(0)))
        rejected(change("next_action", JsonPrimitive("time_out")))
        rejected(change("completed_sessions", JsonPrimitive(0)))
    }
    @Test fun sessionOwnerIntervalsAndOrdinalsAreValidated() {
        rejected(raw().replace("\"student_uid\":\"$uid\"", "\"student_uid\":\"$id\""))
        rejected(raw().replace("2026-10-09T01:00:00.500Z", "2026-10-08T23:00:00Z"))
        rejected(raw().replace("\"session_ordinal\":1", "\"session_ordinal\":2"))
        rejected(raw().replace("\"start_day\":\"2026-10-09\"", "\"start_day\":\"2026-10-08\""))
    }
    @Test fun onlyOwnApprovedStudentProfileIsAccepted() {
        for ((role, status) in listOf("student" to "pending", "student" to "rejected", "admin" to "approved",
            "student" to "completed", "student" to "archived")) {
            try { summary(profile = profile(role, status)); fail("unauthorized") }
            catch (failure: AttendanceReadFailure) { assertEquals(AttendanceProblem.AccessDenied, failure.problem) }
        }
        try { summary(profile = profile(owner = id)); fail("cross account") }
        catch (failure: AttendanceReadFailure) { assertEquals(AttendanceProblem.AccessDenied, failure.problem) }
        try { summary(profile = profile().replace("486", "null")); fail("missing required hours") }
        catch (failure: AttendanceReadFailure) { assertEquals(AttendanceProblem.InvalidResponse, failure.problem) }
    }
    @Test fun overnightOpenSessionSurvivesPhilippineMidnight() {
        var raw = change("today_sessions", JsonArray(emptyList()))
        raw = change("starts_today", JsonPrimitive(0), raw)
        raw = change("started_today", JsonPrimitive(false), raw)
        raw = change("today_completed_seconds", JsonPrimitive(0), raw)
        raw = change("open_session_id", JsonPrimitive(id), raw)
        raw = change("open_time_in", JsonPrimitive("2026-10-08T15:59:00Z"), raw)
        raw = change("open_session_ordinal", JsonPrimitive(1), raw)
        raw = change("next_action", JsonPrimitive("time_out"), raw)
        val s = summary(raw)
        assertTrue(s.overnight)
        assertEquals(AttendanceAction.TimeOut, s.nextAction)
        assertEquals(BigDecimal.ZERO, s.todayCompletedSeconds)
        assertEquals(BigDecimal("3600.5"), s.completedSeconds)
        assertNull(s.displaySession!!.timeOut)
    }
    @Test fun currentOpenSessionDoesNotAddRunningHours() {
        var raw = raw().replace("\"time_out\":\"2026-10-09T01:00:00.500Z\"", "\"time_out\":null")
        raw = change("open_session_id", JsonPrimitive(id), raw)
        raw = change("open_time_in", JsonPrimitive("2026-10-09T00:00:00Z"), raw)
        raw = change("open_session_ordinal", JsonPrimitive(1), raw)
        raw = change("next_action", JsonPrimitive("time_out"), raw)
        raw = change("today_completed_seconds", JsonPrimitive(0), raw)
        raw = change("completed_seconds", JsonPrimitive(0), raw)
        raw = change("completed_sessions", JsonPrimitive(0), raw)
        val s = summary(raw)
        assertFalse(s.overnight)
        assertEquals(BigDecimal.ZERO, s.completedSeconds)
        assertEquals(BigDecimal("1749600"), s.remainingSeconds)
        assertNull(s.displaySession!!.timeOut)
    }
    @Test fun twoCompletedSessionsUseServerTotalsAndNoFurtherStart() {
        val first = Json.parseToJsonElement(raw()).jsonArray.single().jsonObject["today_sessions"]!!.jsonArray.single().jsonObject
        val second = first.toMutableMap().apply {
            put("id", JsonPrimitive("33333333-3333-4333-8333-333333333333"))
            put("time_in", JsonPrimitive("2026-10-09T02:00:00Z"))
            put("time_out", JsonPrimitive("2026-10-09T03:00:00Z"))
            put("session_ordinal", JsonPrimitive(2))
        }
        var raw = change("today_sessions", JsonArray(listOf(first, JsonObject(second))))
        raw = change("starts_today", JsonPrimitive(2), raw)
        raw = change("next_action", JsonPrimitive("none"), raw)
        raw = change("completed_seconds", JsonPrimitive(BigDecimal("7200.5")), raw)
        raw = change("today_completed_seconds", JsonPrimitive(BigDecimal("7200.5")), raw)
        raw = change("completed_sessions", JsonPrimitive(2), raw)
        val s = summary(raw)
        assertEquals(AttendanceAction.None, s.nextAction)
        assertEquals(2, s.sessions.size)
        assertEquals(Instant.parse("2026-10-09T03:00:00Z"), s.displaySession!!.timeOut)
    }
    @Test fun aggregateDaysCannotExceedRowsOrExistWithoutRows() {
        rejected(change("days_present", JsonPrimitive(2))) // One closed row cannot span two start days.
        var empty = change("today_sessions", JsonArray(emptyList()))
        empty = change("starts_today", JsonPrimitive(0), empty)
        empty = change("started_today", JsonPrimitive(false), empty)
        empty = change("completed_seconds", JsonPrimitive(0), empty)
        empty = change("today_completed_seconds", JsonPrimitive(0), empty)
        empty = change("completed_sessions", JsonPrimitive(0), empty)
        rejected(empty) // No closed/open rows, but days_present still 1.
        val validEmpty = change("days_present", JsonPrimitive(0), empty)
        assertEquals(0L, summary(validEmpty).daysPresent)
        rejected(change("days_present", JsonPrimitive(0))) // A closed row requires at least one start day.
    }
    @Test fun historicalRowsMayShareManilaDaysAndExtremeCountsDoNotOverflow() {
        var historical = change("today_sessions", JsonArray(emptyList()))
        historical = change("starts_today", JsonPrimitive(0), historical)
        historical = change("started_today", JsonPrimitive(false), historical)
        historical = change("today_completed_seconds", JsonPrimitive(0), historical)
        historical = change("completed_sessions", JsonPrimitive(4), historical)
        assertEquals(1L, summary(historical).daysPresent) // SQL permits multiple rows sharing a date.
        historical = change("completed_sessions", JsonPrimitive(Long.MAX_VALUE), historical)
        historical = change("days_present", JsonPrimitive(Long.MAX_VALUE), historical)
        assertEquals(Long.MAX_VALUE, summary(historical).daysPresent)
    }
    @Test fun remainingHoursCannotBeNegative() {
        assertEquals(0, summary(change("completed_seconds", JsonPrimitive(2000000))).remainingSeconds.signum())
    }
    @Test fun unboundRepositoryCannotRead() = runBlocking {
        var calls = 0
        val repository = AttendanceRepository({ calls++; summary() }, { now })
        repository.refresh()
        assertEquals(0, calls)
        assertEquals(AttendanceState.AccountChanged, repository.state.value)
    }
    @Test fun networkFailureRetainsOnlyExplicitlyStaleData() = runBlocking {
        var failRead = false
        val repository = AttendanceRepository({ if (failRead) throw java.io.IOException(); summary() }, { now })
        repository.bind(Any()); repository.refresh()
        assertTrue(repository.state.value is AttendanceState.Fresh)
        failRead = true; repository.refresh()
        assertTrue(repository.state.value is AttendanceState.Stale)
        repository.clear(); repository.bind(Any()); repository.refresh()
        assertEquals(AttendanceState.Unavailable(AttendanceProblem.Network), repository.state.value)
    }
    @Test fun contractAndPermissionFailuresNeverRetainCachedData() = runBlocking {
        for (problem in listOf(AttendanceProblem.ContractUnavailable, AttendanceProblem.AccessDenied, AttendanceProblem.InvalidResponse)) {
            var failure: AttendanceProblem? = null
            val repository = AttendanceRepository({ failure?.let { throw AttendanceReadFailure(it) }; summary() }, { now })
            repository.bind(Any()); repository.refresh(); failure = problem; repository.refresh()
            assertEquals(if (problem == AttendanceProblem.AccessDenied) AttendanceState.AccessDenied else AttendanceState.Unavailable(problem), repository.state.value)
        }
    }
    @Test fun logoutDuringRequestDiscardsDelayedResult() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repository = AttendanceRepository({ entered.complete(Unit); release.await(); summary() }, { now })
        repository.bind(Any())
        val job = launch { repository.refresh() }
        entered.await(); repository.clear(); release.complete(Unit); job.join()
        assertEquals(AttendanceState.AccountChanged, repository.state.value)
    }
    @Test fun replacementCannotBeReauthorizedOrDeauthorizedByOldResponse() = runBlocking {
        for (oldFails in listOf(false, true)) {
            val old = Any(); val newer = Any()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val replacement = summary(change("completed_seconds", JsonPrimitive(7200)))
            val repository = AttendanceRepository({ owner ->
                if (owner === old) {
                    entered.complete(Unit); release.await()
                    if (oldFails) throw AttendanceReadFailure(AttendanceProblem.AccessDenied)
                    summary()
                } else replacement
            }, { now })
            repository.bind(old); val job = launch { repository.refresh() }; entered.await()
            repository.bind(newer); repository.refresh(); release.complete(Unit); job.join()
            assertEquals(replacement, (repository.state.value as AttendanceState.Fresh).summary)
        }
    }
    @Test fun authorizationWithdrawalDuringRefreshClearsPriorSnapshot() = runBlocking {
        var delayed = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repository = AttendanceRepository({ if (delayed) { entered.complete(Unit); release.await() }; summary() }, { now })
        repository.bind(Any()); repository.refresh(); delayed = true
        val job = launch { repository.refresh() }; entered.await(); repository.clear()
        release.complete(Unit); job.join()
        assertEquals(AttendanceState.AccountChanged, repository.state.value)
    }
    @Test fun newestRefreshWinsEvenWithSameAccount() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val newer = summary(change("completed_seconds", JsonPrimitive(7200)))
        val repository = AttendanceRepository({ if (++calls == 1) { entered.complete(Unit); release.await(); summary() } else newer }, { now })
        repository.bind(Any()); val old = launch { repository.refresh() }; entered.await()
        repository.refresh(); release.complete(Unit); old.join()
        assertEquals(newer, (repository.state.value as AttendanceState.Fresh).summary)
    }
    @Test fun previousManilaDayIsStaleInsteadOfFresh() = runBlocking {
        val repository = AttendanceRepository({ summary() }, { Instant.parse("2026-10-09T16:00:00Z") })
        repository.bind(Any()); repository.refresh()
        assertTrue(repository.state.value is AttendanceState.Stale)
    }
    @Test fun timeoutIsUnavailableNotZeroAttendance() = runBlocking {
        val repository = AttendanceRepository({ awaitCancellation() }, { now }, deadlineMillis = 20)
        repository.bind(Any()); repository.refresh()
        assertEquals(AttendanceState.Unavailable(AttendanceProblem.Network), repository.state.value)
    }
    @Test fun cancellationKeepsCoroutineCancellationSemantics() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val repository = AttendanceRepository({ entered.complete(Unit); awaitCancellation() }, { now })
        repository.bind(Any()); val job = launch { repository.refresh() }; entered.await()
        job.cancelAndJoin(); repository.clear()
        assertEquals(AttendanceState.AccountChanged, repository.state.value)
    }
    @Test fun lostSessionCannotFallbackToStaleDataOnNetworkFailure() = runBlocking {
        var valid = true
        var failRead = false
        val repository = AttendanceRepository(read = {
            if (failRead) { valid = false; throw java.io.IOException() }
            summary()
        }, clock = { now }, isCurrent = { valid })
        repository.bind(Any()); repository.refresh()
        assertTrue(repository.state.value is AttendanceState.Fresh)
        failRead = true; repository.refresh()
        assertEquals(AttendanceState.AccessDenied, repository.state.value)
    }
    @Test fun lostSessionRejectsSuccessfulResponseBeforePublication() = runBlocking {
        var valid = true
        val repository = AttendanceRepository(read = { valid = false; summary() }, clock = { now }, isCurrent = { valid })
        repository.bind(Any()); repository.refresh()
        assertEquals(AttendanceState.AccessDenied, repository.state.value)
    }
    @Test fun actualRpcErrorCodesAndHttpDenialsAreClassifiedSafely() {
        for (code in listOf("PGRST202", "42883")) assertEquals(AttendanceProblem.ContractUnavailable, attendanceProblem(code, 404))
        for (code in listOf("42501", "PGRST301", "PGRST302", "PGRST303")) assertEquals(AttendanceProblem.AccessDenied, attendanceProblem(code, 400))
        for (status in listOf(401, 403)) assertEquals(AttendanceProblem.AccessDenied, attendanceProblem(null, status))
        assertEquals(AttendanceProblem.Network, attendanceProblem(null, 503))
    }
    @Test fun presentationModelsContainNoCredentialsOrSdkClient() {
        val classes = listOf(AttendanceSummary::class.java, AttendanceSession::class.java,
            AttendanceState.Fresh::class.java, AttendanceState.Stale::class.java, AttendanceState.Unavailable::class.java)
        for (type in classes) for (field in type.declaredFields) {
            assertFalse(field.name.lowercase().contains("token"))
            assertFalse(field.name.lowercase().contains("verifier"))
            assertFalse(field.type.name.contains("supabase"))
        }
        assertFalse(summary().toString().contains(uid))
    }
}
