package ph.edu.bsit.tcc.ojtdtr.attendance

import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

// Fixed classifications only; never retain SDK exceptions, response bodies, or credentials in UI state.
enum class AttendanceProblem { AccessDenied, ContractUnavailable, InvalidResponse, Network }
internal fun attendanceProblem(code: String?, status: Int): AttendanceProblem = when {
    status == 401 || status == 403 || code in listOf("42501", "PGRST301", "PGRST302", "PGRST303") -> AttendanceProblem.AccessDenied
    code in listOf("PGRST202", "42883") || status == 404 -> AttendanceProblem.ContractUnavailable
    else -> AttendanceProblem.Network
}
internal class AttendanceReadFailure(val problem: AttendanceProblem) : Exception()
sealed interface AttendanceState {
    data object AccountChanged : AttendanceState
    data object Loading : AttendanceState
    data class Fresh(val summary: AttendanceSummary, val fetchedAt: Instant) : AttendanceState
    data class Refreshing(val summary: AttendanceSummary, val fetchedAt: Instant) : AttendanceState
    data class Stale(val summary: AttendanceSummary, val fetchedAt: Instant) : AttendanceState
    data object AccessDenied : AttendanceState
    data class Unavailable(val problem: AttendanceProblem) : AttendanceState
}

/** In-memory only. Mutations/bindings run on the coordinator's Main dispatcher.
 * Binding identity and request sequence also reject non-cooperative late completions. */
class AttendanceRepository internal constructor(
    private val read: suspend (Any) -> AttendanceSummary,
    private val clock: () -> Instant = Instant::now,
    private val deadlineMillis: Long = 15000,
    private val isCurrent: (Any) -> Boolean = { true },
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow<AttendanceState>(AttendanceState.AccountChanged)
    val state = mutableState.asStateFlow()
    private var binding: Any? = null
    private var revision = 0L
    private var cached: AttendanceState.Fresh? = null
    private var observedElapsed = 0L
    private var activeRequest: Long? = null

    companion object { const val FRESH_MILLIS = 120_000L }
    internal data class Wakeup(val delayMillis: Long, val midnight: Boolean)

    /** Age is monotonic; wall time is a display label and a conservative day/clock-change hint. */
    internal fun reassess(clockChanged: Boolean = false) {
        val owner = binding ?: return
        if (!isCurrent(owner)) { revision++; activeRequest = null; cached = null; mutableState.value = AttendanceState.AccessDenied; return }
        val snapshot = cached ?: return
        val age = elapsedMillis() - observedElapsed
        val wallAge = Duration.between(snapshot.fetchedAt, clock()).toMillis()
        if (mutableState.value is AttendanceState.Fresh && (clockChanged || age < 0 || age >= FRESH_MILLIS ||
            kotlin.math.abs(wallAge - age) > 5000 || snapshot.summary.day != clock().atZone(Manila).toLocalDate()))
            mutableState.value = AttendanceState.Stale(snapshot.summary, snapshot.fetchedAt)
    }

    internal fun nextWakeup(): Wakeup? {
        if (binding == null) return null
        val now = clock()
        val midnight = now.atZone(Manila).toLocalDate().plusDays(1).atStartOfDay(Manila).toInstant()
        val dayWait = Duration.between(now, midnight).toMillis().coerceAtLeast(1)
        val remaining = FRESH_MILLIS - (elapsedMillis() - observedElapsed)
        return if (mutableState.value is AttendanceState.Fresh && remaining > 0 && remaining < dayWait)
            Wakeup(remaining, false) else Wakeup(dayWait, true)
    }

    internal fun stopRefresh() {
        revision++; activeRequest = null
        val owner = binding
        mutableState.value = if (owner != null && isCurrent(owner)) cached?.let { AttendanceState.Stale(it.summary, it.fetchedAt) }
            ?: AttendanceState.AccountChanged else AttendanceState.AccountChanged
        if (owner == null || !isCurrent(owner)) cached = null
    }

    internal fun bind(owner: Any) {
        if (binding === owner) return
        clear(); binding = owner
    }
    internal fun clear() {
        binding = null; revision++; activeRequest = null; cached = null
        mutableState.value = AttendanceState.AccountChanged
    }
    internal suspend fun refresh() {
        val owner = binding ?: return
        if (activeRequest != null) return // Coalesce; do not cancel an identical read on every resume/recomposition.
        val request = ++revision
        activeRequest = request
        if (!isCurrent(owner)) { activeRequest = null; fail(owner, request, AttendanceProblem.AccessDenied); return }
        mutableState.value = cached?.let { AttendanceState.Refreshing(it.summary, it.fetchedAt) } ?: AttendanceState.Loading
        try {
            val summary = withTimeout(deadlineMillis) { read(owner) }
            if (binding !== owner || revision != request) return
            if (!isCurrent(owner)) { fail(owner, request, AttendanceProblem.AccessDenied); return }
            val snapshot = AttendanceState.Fresh(summary, clock())
            cached = snapshot
            observedElapsed = elapsedMillis()
            mutableState.value = if (summary.day == clock().atZone(Manila).toLocalDate()) snapshot
                else AttendanceState.Stale(summary, snapshot.fetchedAt)
        } catch (_: TimeoutCancellationException) {
            fail(owner, request, AttendanceProblem.Network)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AttendanceReadFailure) { fail(owner, request, failure.problem) }
        catch (_: Exception) { fail(owner, request, AttendanceProblem.Network) }
        finally { if (activeRequest == request) activeRequest = null }
    }
    private fun fail(owner: Any, request: Long, problem: AttendanceProblem) {
        if (binding !== owner || revision != request) return
        val safeProblem = if (isCurrent(owner)) problem else AttendanceProblem.AccessDenied
        mutableState.value = when (safeProblem) {
            AttendanceProblem.AccessDenied -> { cached = null; AttendanceState.AccessDenied }
            AttendanceProblem.Network -> cached?.let { AttendanceState.Stale(it.summary, it.fetchedAt) }
                ?: AttendanceState.Unavailable(safeProblem)
            else -> { cached = null; AttendanceState.Unavailable(safeProblem) }
        }
    }
}
