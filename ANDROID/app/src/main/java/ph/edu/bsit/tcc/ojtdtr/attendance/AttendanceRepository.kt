package ph.edu.bsit.tcc.ojtdtr.attendance

import java.time.Instant
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
) {
    private val mutableState = MutableStateFlow<AttendanceState>(AttendanceState.AccountChanged)
    val state = mutableState.asStateFlow()
    private var binding: Any? = null
    private var revision = 0L
    private var cached: AttendanceState.Fresh? = null

    internal fun bind(owner: Any) {
        if (binding === owner) return
        clear(); binding = owner
    }
    internal fun clear() {
        binding = null; revision++; cached = null
        mutableState.value = AttendanceState.AccountChanged
    }
    internal suspend fun refresh() {
        val owner = binding ?: return
        val request = ++revision
        if (!isCurrent(owner)) { fail(owner, request, AttendanceProblem.AccessDenied); return }
        mutableState.value = AttendanceState.Loading
        try {
            val summary = withTimeout(deadlineMillis) { read(owner) }
            if (binding !== owner || revision != request) return
            if (!isCurrent(owner)) { fail(owner, request, AttendanceProblem.AccessDenied); return }
            val snapshot = AttendanceState.Fresh(summary, clock())
            cached = snapshot
            mutableState.value = if (summary.day == clock().atZone(Manila).toLocalDate()) snapshot
                else AttendanceState.Stale(summary, snapshot.fetchedAt)
        } catch (_: TimeoutCancellationException) {
            fail(owner, request, AttendanceProblem.Network)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AttendanceReadFailure) { fail(owner, request, failure.problem) }
        catch (_: Exception) { fail(owner, request, AttendanceProblem.Network) }
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
