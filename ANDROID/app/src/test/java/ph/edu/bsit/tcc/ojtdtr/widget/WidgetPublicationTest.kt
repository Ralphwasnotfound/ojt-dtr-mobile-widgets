package ph.edu.bsit.tcc.ojtdtr.widget

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class WidgetPublicationTest {
    private val neutral = WidgetPresentation.Unknown
    private val approved = WidgetPresentation("Stale — open app to verify", "2h 0m", "484h 0m", "Timed Out", "Oct 9")
    @Test fun firstFailureDoesNotPreventSecondNeutralUpdateAndRetrySucceeds() = runBlocking {
        val calls = mutableListOf<Int>(); val delays = mutableListOf<Long>()
        val publisher = WidgetPublication({ neutral }, { listOf(1,2) }, { id ->
            calls += id; if (calls.size == 1) throw java.io.IOException()
        }, { delays += it })
        publisher.refresh()
        assertEquals(listOf(1,2,1), calls); assertEquals(listOf(250L), delays)
        assertEquals(neutral, publisher.presentation.value)
    }
    @Test fun retryExhaustionIsBoundedAndDoesNotRestoreApprovedValues() = runBlocking {
        var calls = 0; val delays = mutableListOf<Long>()
        val publisher = WidgetPublication({ neutral }, { listOf(1,2) }, { calls++; throw java.io.IOException() }, { delays += it })
        publisher.refresh(); assertEquals(6, calls); assertEquals(listOf(250L,1000L), delays)
        assertEquals(neutral, publisher.presentation.value)
    }
    @Test fun removedWidgetIsNotRetried() = runBlocking {
        var ids = listOf(1,2); val calls = mutableListOf<Int>()
        val publisher = WidgetPublication({ neutral }, { ids }, { id -> calls += id; if (id == 1) throw java.io.IOException() }, { ids = listOf(2) })
        publisher.refresh(); assertEquals(listOf(1,2), calls)
    }
    @Test fun cancellationPropagatesAndStopsRemainingInstancesAndRetries() = runBlocking {
        var calls = 0; var waits = 0
        val publisher = WidgetPublication({ neutral }, { listOf(1,2) }, { calls++; throw CancellationException() }, { waits++ })
        try { publisher.refresh(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(1, calls); assertEquals(0, waits)
    }
    @Test fun cancellationDuringRetryDelayPropagates() = runBlocking {
        var calls = 0
        val publisher = WidgetPublication({ neutral }, { listOf(1) }, { calls++; throw java.io.IOException() }, { throw CancellationException() })
        try { publisher.refresh(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
    @Test fun fatalJvmErrorsAreNotSwallowed() = runBlocking {
        val publisher = WidgetPublication({ neutral }, { listOf(1) }, { throw LinkageError("Synthetic fatal error") }, {})
        try { publisher.refresh(); fail("Fatal error swallowed") } catch (_: LinkageError) { }
    }
    @Test fun approvedUpdateFailuresDoNotStartNeutralRetryLoop() = runBlocking {
        var calls = 0; var waits = 0
        val publisher = WidgetPublication({ approved }, { listOf(1,2) }, { calls++; throw java.io.IOException() }, { waits++ })
        publisher.refresh(); assertEquals(2, calls); assertEquals(0, waits)
    }
    @Test fun failedEnumerationReceivesBoundedNeutralRecovery() = runBlocking {
        var enumerations = 0; var updates = 0
        val publisher = WidgetPublication({ neutral }, { if (++enumerations == 1) throw java.io.IOException(); listOf(1,2) }, { updates++ }, {})
        publisher.refresh(); assertEquals(2, enumerations); assertEquals(2, updates)
    }
    @Test fun newApprovalDuringNeutralRetryStopsOldRecovery() = runBlocking {
        var current = neutral; var calls = 0
        val publisher = WidgetPublication({ current }, { listOf(1) }, { calls++; throw java.io.IOException() }, { current = approved })
        publisher.refresh(); assertEquals(1, calls)
        assertEquals(approved, publisher.presentation.value) // Reassessment uses current authority, never old payload.
    }
    @Test fun concurrentRefreshesHaveOneEffectiveUpdateLoop() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var active = 0; var maximum = 0; var calls = 0
        val publisher = WidgetPublication({ neutral }, { listOf(1) }, {
            active++; maximum = maxOf(maximum,active); if (++calls == 1) { entered.complete(Unit); release.await() }; active--
        }, {})
        val a = launch { publisher.refresh() }; entered.await(); val b = launch { publisher.refresh() }
        yield(); assertEquals(1,calls); release.complete(Unit); a.join(); b.join(); assertEquals(1,maximum)
    }
    @Test fun withdrawalDuringNonCooperativePublicationClearsBeforeCancellationAndRepairsLateHostWrite() = runBlocking {
        for (reason in listOf("logout","revocation","replacement","sdk invalidation")) {
            var current = approved; val changes = MutableStateFlow(0)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val repaired = CompletableDeferred<Unit>()
            val host = mutableMapOf<Int, WidgetPresentation>(); var first = true
            lateinit var publisher: WidgetPublication<Int>
            publisher = WidgetPublication({ current }, { listOf(1,2) }, { id ->
                val captured = publisher.presentation.value
                if (first) { first = false; entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                host[id] = captured
                if (id == 2 && captured.completed == null) repaired.complete(Unit)
            }, {})
            val observer = launch { publisher.observe(changes.map { Unit }) }
            try {
                entered.await(); current = neutral; changes.value++
                // Observe synchronous withdrawal while old rendering has NOT completed.
                withTimeout(5000) { publisher.presentation.first { it.completed == null } }
                assertFalse(release.isCompleted)
                release.complete(Unit); withTimeout(5000) { repaired.await() }
                assertEquals(reason, neutral, host[1]); assertEquals(reason, neutral, host[2])
            } finally { release.complete(Unit); observer.cancelAndJoin() }
        }
    }
}
