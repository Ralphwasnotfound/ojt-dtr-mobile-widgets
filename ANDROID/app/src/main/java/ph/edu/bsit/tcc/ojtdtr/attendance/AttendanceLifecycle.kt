package ph.edu.bsit.tcc.ojtdtr.attendance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One foreground-only timer. Expiry marks stale; only a day boundary requests another read. */
internal class AttendanceLifecycle(
    private val scope: CoroutineScope,
    private val repository: AttendanceRepository,
    private val onResume: () -> Unit,
    private val onPause: () -> Unit,
    private val onBoundary: () -> Unit,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    var foreground = false
        private set
    private var timer: Job? = null
    private val owners = mutableMapOf<Any, Boolean>()
    private var closed = false

    fun register(): Any = Any().also { if (!closed) owners[it] = false }
    fun foreground(owner: Any, value: Boolean) {
        if (closed || !owners.containsKey(owner)) return
        owners[owner] = value
        transition(owners.values.any { it })
    }
    fun dispose(owner: Any) {
        if (owners.remove(owner) == null) return
        transition(owners.values.any { it })
    }
    private fun transition(value: Boolean) {
        if (foreground == value) return
        foreground = value
        timer?.cancel()
        if (!value) { onPause(); return }
        repository.reassess()
        onResume()
        timer = scope.launch {
            repository.state.collectLatest {
                // collectLatest reschedules when a read, expiry, or binding changes.
                while (foreground) {
                    repository.reassess()
                    val wakeup = repository.nextWakeup() ?: return@collectLatest
                    wait(wakeup.delayMillis)
                    if (!foreground) return@collectLatest
                    repository.reassess(clockChanged = wakeup.midnight)
                    if (wakeup.midnight) onBoundary()
                    // No age-driven network polling; after expiry the next wake is midnight.
                }
            }
        }
    }
    fun clockChanged(owner: Any? = null) {
        if (!foreground) return
        // Every registration receives the system broadcast; only the first resumed owner handles it.
        if (owner != null && owners.entries.firstOrNull { it.value }?.key !== owner) return
        repository.reassess(clockChanged = true)
        onBoundary()
    }
    fun close() { closed = true; owners.clear(); transition(false); timer?.cancel() }
}
