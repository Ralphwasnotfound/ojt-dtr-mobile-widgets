package ph.edu.bsit.tcc.ojtdtr.attendance

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class AttendanceFreshnessTest {
    private class Clock {
        var wall = Instant.parse("2026-10-09T04:00:00Z")
        var elapsed = 0L
        fun advance(ms: Long) { elapsed += ms; wall = wall.plusMillis(ms) }
    }
    private fun summary(day: LocalDate = LocalDate.parse("2026-10-09"), overnight: Boolean = false) =
        AttendanceSummary(day, emptyList(), if (overnight) "22222222-2222-4222-8222-222222222222" else null,
            if (overnight) Instant.parse("2026-10-08T15:59:00Z") else null, if (overnight) 1 else null,
            if (overnight) AttendanceAction.TimeOut else AttendanceAction.TimeIn,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, if (overnight) 1 else 0, 486)
    private fun repository(clock: Clock, valid: () -> Boolean = { true }, read: suspend (Any) -> AttendanceSummary = { summary() }) =
        AttendanceRepository(read, { clock.wall }, isCurrent = { valid() }, elapsedMillis = { clock.elapsed })

    @Test fun freshnessExpiresExactlyAtThresholdAndNeverReauthorizesFromCache() = runBlocking {
        val clock = Clock(); val repo = repository(clock); repo.bind(Any()); repo.refresh()
        clock.advance(AttendanceRepository.FRESH_MILLIS - 1); repo.reassess()
        assertTrue(repo.state.value is AttendanceState.Fresh)
        clock.advance(1); repo.reassess()
        val stale = repo.state.value as AttendanceState.Stale
        assertEquals(Instant.parse("2026-10-09T04:00:00Z"), stale.fetchedAt)
        clock.wall = stale.fetchedAt; repo.reassess()
        assertTrue(repo.state.value is AttendanceState.Stale) // Clock rollback cannot promote cached data.
    }
    @Test fun wallClockChangesOrMonotonicResetInvalidateFreshness() = runBlocking {
        for (jump in listOf(-3600000L, 3600000L)) {
            val clock = Clock(); val repo = repository(clock); repo.bind(Any()); repo.refresh()
            clock.wall = clock.wall.plusMillis(jump); repo.reassess()
            assertTrue(repo.state.value is AttendanceState.Stale)
        }
        val clock = Clock(); val repo = repository(clock); repo.bind(Any()); repo.refresh()
        clock.elapsed = -1; repo.reassess(); assertTrue(repo.state.value is AttendanceState.Stale)
    }
    @Test fun sdkInvalidationWithdrawsEvenARecentSnapshot() = runBlocking {
        val clock = Clock(); var valid = true; val repo = repository(clock, { valid })
        repo.bind(Any()); repo.refresh(); valid = false; repo.reassess()
        assertEquals(AttendanceState.AccessDenied, repo.state.value)
        valid = true; repo.reassess()
        assertEquals(AttendanceState.AccessDenied, repo.state.value) // No cache revival.
    }
    @Test fun midnightInvalidatesWithoutLocallyClosingOvernightSession() = runBlocking {
        val clock = Clock(); clock.wall = Instant.parse("2026-10-09T15:59:59Z")
        var serverDay = LocalDate.parse("2026-10-09")
        val repo = repository(clock, read = { summary(serverDay, overnight = true) })
        repo.bind(Any()); repo.refresh(); clock.advance(1000); repo.reassess()
        val stale = repo.state.value as AttendanceState.Stale
        assertEquals(serverDay, stale.summary.day); assertNotNull(stale.summary.openSessionId)
        assertEquals(AttendanceAction.TimeOut, stale.summary.nextAction)
        serverDay = serverDay.plusDays(1); repo.refresh()
        assertEquals(serverDay, (repo.state.value as AttendanceState.Fresh).summary.day)
        assertNotNull((repo.state.value as AttendanceState.Fresh).summary.openSessionId)
    }
    @Test fun deviceTimezoneCannotChangePhilippineDay() = runBlocking {
        val previous = TimeZone.getDefault()
        try {
            val clock = Clock(); val repo = repository(clock); repo.bind(Any()); repo.refresh()
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles")); repo.reassess()
            assertTrue(repo.state.value is AttendanceState.Fresh)
            assertEquals(LocalDate.parse("2026-10-09"), (repo.state.value as AttendanceState.Fresh).summary.day)
        } finally { TimeZone.setDefault(previous) }
    }
    @Test fun refreshingRetainsOnlyExplicitPreviousDataAndPauseRejectsLatePublication() = runBlocking {
        val clock = Clock(); var delayed = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = repository(clock, read = { if (delayed) { entered.complete(Unit); release.await() }; summary() })
        repo.bind(Any()); repo.refresh(); delayed = true
        val job = launch { repo.refresh() }; entered.await()
        assertTrue(repo.state.value is AttendanceState.Refreshing)
        repo.stopRefresh(); release.complete(Unit); job.join()
        assertTrue(repo.state.value is AttendanceState.Stale)
    }
    @Test fun authorizationLostDuringRefreshCannotRetainStaleData() = runBlocking {
        val clock = Clock(); var valid = true; var fail = false
        val repo = repository(clock, { valid }, { if (fail) { valid = false; throw java.io.IOException() }; summary() })
        repo.bind(Any()); repo.refresh(); fail = true; repo.refresh()
        assertEquals(AttendanceState.AccessDenied, repo.state.value)
    }
    private data class Sleep(val ms: Long, val release: CompletableDeferred<Unit>, var active: Boolean = true)
    @Test fun lifecycleDeduplicatesResumeAndCancelsTimersInBackground() = runBlocking {
        val clock = Clock(); val repo = repository(clock); repo.bind(Any()); repo.refresh()
        val sleeps = Channel<Sleep>(Channel.UNLIMITED)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var resumes = 0; var pauses = 0; var boundaries = 0
        val lifecycle = AttendanceLifecycle(scope, repo, { resumes++ }, { pauses++; repo.stopRefresh() }, { boundaries++ },
            wait = { ms -> val wake = Sleep(ms, CompletableDeferred()); sleeps.send(wake); try { wake.release.await() } finally { wake.active = false } })
        val token = lifecycle.register()
        try {
            lifecycle.foreground(token, true); lifecycle.foreground(token, true)
            val expiry = sleeps.receive(); assertEquals(120000L, expiry.ms); assertEquals(1, resumes)
            clock.advance(expiry.ms); expiry.release.complete(Unit)
            val midnight = sleeps.receive()
            assertTrue(repo.state.value is AttendanceState.Stale); assertEquals(0, boundaries)
            assertTrue(midnight.ms > 120000)
            lifecycle.foreground(token, false); assertEquals(1, pauses)
            clock.advance(midnight.ms); midnight.release.complete(Unit); yield()
            assertEquals(0, boundaries)
            lifecycle.foreground(token, true); assertEquals(2, resumes)
        } finally { lifecycle.close(); scope.cancel() }
    }
    @Test fun foregroundMidnightRequestsExactlyOneAuthoritativeRead() = runBlocking {
        val clock = Clock(); clock.wall = Instant.parse("2026-10-09T15:59:59Z")
        val repo = repository(clock, read = { summary(overnight = true) }); repo.bind(Any()); repo.refresh()
        val sleeps = Channel<Sleep>(Channel.UNLIMITED); val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var calls = 0
        val lifecycle = AttendanceLifecycle(scope, repo, {}, {}, { calls++ },
            wait = { ms -> val wake = Sleep(ms, CompletableDeferred()); sleeps.send(wake); try { wake.release.await() } finally { wake.active = false } })
        val token = lifecycle.register()
        try {
            lifecycle.foreground(token, true); val midnight = sleeps.receive(); assertEquals(1000L, midnight.ms)
            clock.advance(1000); midnight.release.complete(Unit); sleeps.receive()
            assertEquals(1, calls); assertTrue(repo.state.value is AttendanceState.Stale)
            assertNotNull((repo.state.value as AttendanceState.Stale).summary.openSessionId)
        } finally { lifecycle.close(); scope.cancel() }
    }
    @Test fun clockBroadcastIsForegroundOnlyAndDoesNotMakeCachedDataFresh() = runBlocking {
        val clock = Clock(); val repo = repository(clock); repo.bind(Any()); repo.refresh()
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); var calls = 0
        val lifecycle = AttendanceLifecycle(scope, repo, {}, {}, { calls++ }, wait = { awaitCancellation() })
        val token = lifecycle.register()
        try {
            lifecycle.clockChanged(); assertEquals(0, calls)
            lifecycle.foreground(token, true); lifecycle.clockChanged(); assertEquals(1, calls)
            assertTrue(repo.state.value is AttendanceState.Stale)
        } finally { lifecycle.close(); scope.cancel() }
    }
    @Test fun overlappingOwnersPreserveExpiryAndMidnightInEitherReleaseOrder() = runBlocking {
        for (disposeFirst in listOf(false, true)) for (reverse in listOf(false, true)) {
            val clock = Clock(); val repo = repository(clock); repo.bind(Any()); repo.refresh()
            val sleeps = Channel<Sleep>(Channel.UNLIMITED)
            suspend fun next(): Sleep {
                while (true) { val wake = sleeps.receive(); yield(); if (wake.active) return wake }
            }
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var resumes = 0; var pauses = 0; var boundaries = 0
            val lifecycle = AttendanceLifecycle(scope, repo, { resumes++ }, { pauses++; repo.stopRefresh() }, { boundaries++ },
                wait = { ms -> val wake = Sleep(ms, CompletableDeferred()); sleeps.send(wake); try { wake.release.await() } finally { wake.active = false } })
            val a = lifecycle.register(); val b = lifecycle.register()
            val first = if (reverse) b else a; val last = if (reverse) a else b
            try {
                lifecycle.foreground(a, true); val expiry = next()
                lifecycle.foreground(b, true); lifecycle.foreground(b, true)
                assertEquals(1, resumes); assertTrue(sleeps.tryReceive().isFailure)
                if (disposeFirst) lifecycle.dispose(first) else {
                    lifecycle.foreground(first, false); lifecycle.foreground(first, false); lifecycle.dispose(first)
                }
                lifecycle.foreground(first, true) // Disposed callbacks are inert.
                assertTrue(lifecycle.foreground); assertEquals(0, pauses)
                clock.advance(120000); expiry.release.complete(Unit)
                val midnight = next(); assertTrue(repo.state.value is AttendanceState.Stale)
                clock.advance(midnight.ms); midnight.release.complete(Unit); val next = next()
                assertEquals(1, boundaries)
                lifecycle.foreground(last, false); lifecycle.foreground(last, false)
                assertFalse(lifecycle.foreground); assertEquals(1, pauses)
                clock.advance(next.ms); next.release.complete(Unit); yield(); assertEquals(1, boundaries)
                lifecycle.dispose(last); assertEquals(1, pauses)
                val replacement = lifecycle.register(); lifecycle.foreground(replacement, true)
                assertTrue(lifecycle.foreground); assertEquals(2, resumes)
                lifecycle.close(); lifecycle.foreground(replacement, true)
                assertFalse(lifecycle.foreground)
            } finally { lifecycle.close(); scope.cancel() }
        }
    }

    @Test fun overlappingClockReceiversHandleEachEventOnceAndIgnoreDisposedOwners() = runBlocking {
        val clock = Clock(); val repo = repository(clock); repo.bind(Any()); repo.refresh()
        val scope = CoroutineScope(coroutineContext + SupervisorJob()); var events = 0
        val lifecycle = AttendanceLifecycle(scope, repo, {}, {}, { events++ }, wait = { awaitCancellation() })
        val a = lifecycle.register(); val b = lifecycle.register()
        try {
            lifecycle.foreground(a, true); lifecycle.foreground(b, true)
            lifecycle.clockChanged(b); lifecycle.clockChanged(a); assertEquals(1, events)
            lifecycle.dispose(a); lifecycle.clockChanged(a); lifecycle.clockChanged(b); assertEquals(2, events)
            lifecycle.foreground(b, false); lifecycle.clockChanged(b); assertEquals(2, events)
        } finally { lifecycle.close(); scope.cancel() }
    }

}
