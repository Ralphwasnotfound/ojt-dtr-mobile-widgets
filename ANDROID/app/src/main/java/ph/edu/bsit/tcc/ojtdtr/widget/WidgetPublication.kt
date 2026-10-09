package ph.edu.bsit.tcc.ojtdtr.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-dispatcher publication. Update operations receive IDs, never captured attendance payloads. */
internal class WidgetPublication<Id>(
    private val current: () -> WidgetPresentation,
    private val instances: suspend () -> List<Id>,
    private val update: suspend (Id) -> Unit,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutable = MutableStateFlow(WidgetPresentation.Unknown)
    val presentation = mutable.asStateFlow()
    private val mutex = Mutex()
    fun reassess() { mutable.value = current() }

    suspend fun observe(changes: Flow<Unit>) {
        changes.onEach { reassess() }.collectLatest { refresh() }
    }

    suspend fun refresh() = mutex.withLock {
        reassess()
        var enumerationFailed = false
        suspend fun enumerate(): List<Id>? = try { instances().distinct() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { enumerationFailed = true; null }
        suspend fun attempt(id: Id): Boolean {
            currentCoroutineContext().ensureActive()
            reassess() // Never publish a value captured before a suspension or account transition.
            return try {
                update(id)
                currentCoroutineContext().ensureActive()
                reassess()
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false } // A removed/broken instance must not abort the remaining IDs.
        }
        val failed = mutableSetOf<Id>()
        val initial = enumerate()
        if (initial != null) for (id in initial) if (!attempt(id)) failed += id
        // Only neutral invalidation is retried. No permanent worker/service or retry loop.
        for (delayMillis in listOf(250L, 1000L)) {
            reassess()
            if (mutable.value.completed != null || (!enumerationFailed && failed.isEmpty())) break
            wait(delayMillis)
            currentCoroutineContext().ensureActive()
            reassess()
            if (mutable.value.completed != null) break // New approval is handled by a new normal publication.
            val live = enumerate() ?: continue
            val targets = if (enumerationFailed) live else live.filter { it in failed }
            enumerationFailed = false
            failed.retainAll(live.toSet()) // Deleted instances need no recovery.
            for (id in targets) {
                reassess()
                if (mutable.value.completed != null) break
                if (attempt(id)) failed -= id else failed += id
            }
        }
        // Exhaustion leaves neutral in-memory authority; host delivery cannot be guaranteed.
        reassess()
    }
}
