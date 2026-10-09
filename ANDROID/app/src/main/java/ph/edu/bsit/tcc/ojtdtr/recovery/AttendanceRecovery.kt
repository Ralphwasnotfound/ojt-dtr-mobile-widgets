package ph.edu.bsit.tcc.ojtdtr.recovery

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import ph.edu.bsit.tcc.ojtdtr.proof.ProofContract

/** Presentation deliberately carries no account, request, upload, session or receipt identifiers. */
enum class RecoveryStatus { Hidden, Checking, None, Unresolved, Confirmed, Unavailable }
internal fun interface RecoveryReader { suspend fun receipt(record: RecoveryRecord): String }

/** Main-dispatcher owner. Independent of Activity; reads only, never owns a mutation transport. */
internal class AttendanceRecovery(private val scope: CoroutineScope, private val journal: RecoveryJournal,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val deadline: Long = 15_000) {
    private val mutable = MutableStateFlow(RecoveryStatus.Hidden)
    val state = mutable.asStateFlow()
    private class Binding(val uid: String, val current: () -> Boolean, val reader: RecoveryReader) {
        val valid = AtomicBoolean(true)
        fun accepted() = valid.get() && current()
    }
    private var binding: Binding? = null
    private var work: Job? = null
    // Block until trusted startup inspection. A different account sees only a generic blocker.
    private var blocked = true
    fun attemptChanged(unresolved: Boolean) {
        if (unresolved) { blocked = true; mutable.value = RecoveryStatus.Unresolved }
        else check()
    }
    fun blocksSubmission(): Boolean = blocked || mutable.value == RecoveryStatus.Checking
    fun withdraw() {
        binding?.valid?.set(false); binding = null
        work?.cancel(); work = null; blocked = true; mutable.value = RecoveryStatus.Hidden
    }
    fun bind(uid: String, current: () -> Boolean, reader: RecoveryReader) {
        withdraw(); binding = Binding(uid, current, reader); check()
    }
    fun check() {
        val owner = binding ?: return
        if (!owner.accepted() || work?.isActive == true) return
        mutable.value = RecoveryStatus.Checking; blocked = true
        work = scope.launch {
            fun current() = binding === owner && owner.accepted()
            try {
                val record = withContext(io) { journal.load() }
                if (!current()) return@launch
                if (record == null) { blocked = false; mutable.value = RecoveryStatus.None; return@launch }
                if (record.owner != owner.uid) { mutable.value = RecoveryStatus.Unresolved; return@launch }
                // No exposed request lookup exists. Never replay prepare to retrieve a lost response.
                if (record.upload == null || record.session == null) { mutable.value = RecoveryStatus.Unresolved; return@launch }
                val raw = withTimeout(deadline) { owner.reader.receipt(record) }
                if (!current()) return@launch
                if (!confirmedReceipt(raw, record)) { mutable.value = RecoveryStatus.Unresolved; return@launch }
                withContext(io) { journal.confirmAndClear(record) { owner.valid.get() } }
                if (!current()) return@launch
                blocked = false; mutable.value = RecoveryStatus.Confirmed
            } catch (_: TimeoutCancellationException) {
                if (current()) mutable.value = RecoveryStatus.Unresolved
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) mutable.value = RecoveryStatus.Unavailable }
            finally {
                if (binding === owner && !owner.accepted()) {
                    owner.valid.set(false); binding = null; blocked = true; mutable.value = RecoveryStatus.Hidden
                }
            }
        }
    }
    suspend fun awaitIdle() { work?.join() }
}

/** A projection of immutable receipt identity/timestamps only. No GPS or path is read/persisted.
 * SQL binds the unique upload to the private request; public receipts have no request_id column.
 * Local journal's server-confirmed upload/session is the only supported join to that request. */
internal fun confirmedReceipt(raw: String, expected: RecoveryRecord): Boolean {
    require(raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
    val rows = Json.parseToJsonElement(raw) as JsonArray
    require(rows.size <= 1)
    if (rows.isEmpty()) return false
    val row = rows.single() as JsonObject
    fun text(key: String) = (row.getValue(key) as JsonPrimitive).also { require(it.isString) }.content
    require(row.keys == setOf("id", "student_uid", "upload_id", "attendance_session_id", "action_type", "official_punch_at", "attached_at"))
    ProofContract.uuid(text("id"))
    require(text("student_uid") == expected.owner && text("upload_id") == expected.upload &&
        text("attendance_session_id") == expected.session && text("action_type") == expected.action)
    val punch = Instant.parse(text("official_punch_at")); val attached = Instant.parse(text("attached_at"))
    require(!attached.isBefore(punch))
    return true
}
