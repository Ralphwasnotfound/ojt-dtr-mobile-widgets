package ph.edu.bsit.tcc.ojtdtr.proof

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceAction

/** No session, image, location or server path belongs in presentation state. */
internal enum class SubmissionState { Ready, Preparing, Uploading, Finalizing, Completed, Failed, Cancelled, OutcomeUnknown, Disabled }
internal enum class SubmissionProblem { Authorization, InvalidProof, Denied, Conflict, Unavailable, Network, Disabled }
internal class SubmissionFailure(val problem: SubmissionProblem) : Exception()
internal class PreparedProof(val uploadId: String, val sessionId: String, val path: String, val expires: Instant)
internal class ConfirmedProof(val bytes: ByteArray, val fix: Fix)
internal class ProofReceipt(val id: String, val punchAt: Instant)

/** Injectable contract adapter; production construction never supplies a live implementation. */
internal interface ProofBackend {
    suspend fun prepare(requestId: String, action: AttendanceAction): String
    suspend fun upload(bucket: String, path: String, jpeg: ByteArray)
    suspend fun finalize(uploadId: String, fix: Fix): String
    suspend fun receipt(uploadId: String): String
}

/** Gate is inside every operation, independent of navigation/button visibility. No enable switch. */
internal object DisabledProofBackend : ProofBackend {
    private fun blocked(): Nothing = throw SubmissionFailure(SubmissionProblem.Disabled)
    override suspend fun prepare(requestId: String, action: AttendanceAction): String = blocked()
    override suspend fun upload(bucket: String, path: String, jpeg: ByteArray): Unit = blocked()
    override suspend fun finalize(uploadId: String, fix: Fix): String = blocked()
    override suspend fun receipt(uploadId: String): String = blocked()
}

internal object ProofContract {
    const val BUCKET = "attendance-proofs"
    fun uuid(value: String): String = value.also { require(UUID.fromString(it).toString() == it.lowercase()) }
    private fun JsonObject.text(key: String): String = (getValue(key) as JsonPrimitive).also { require(it.isString) }.content
    private fun JsonObject.number(key: String): BigDecimal = (getValue(key) as JsonPrimitive).also { require(!it.isString) }.content.toBigDecimal()
    fun prepared(raw: String, uid: String, now: Instant): PreparedProof {
        val row = (Json.parseToJsonElement(raw) as JsonArray).single() as JsonObject
        val upload = uuid(row.text("upload_id")); val session = uuid(row.text("attendance_session_id"))
        val path = row.text("photo_path")
        require(path == "${uuid(uid)}/$session/$upload/proof")
        val expiry = Instant.parse(row.text("expires_at")); require(expiry > now)
        return PreparedProof(upload, session, path, expiry)
    }
    fun receipt(raw: String, uid: String, action: AttendanceAction, ticket: PreparedProof, fix: Fix, query: Boolean = false): ProofReceipt? {
        val parsed = Json.parseToJsonElement(raw)
        val row = if (query) (parsed as JsonArray).let { require(it.size <= 1); if (it.isEmpty()) return null; it.single() as JsonObject }
            else parsed as JsonObject
        require(row.text("student_uid") == uid && row.text("upload_id") == ticket.uploadId &&
            row.text("attendance_session_id") == ticket.sessionId && row.text("photo_path") == ticket.path &&
            row.text("action_type") == wireAction(action))
        require(row.number("latitude").compareTo(fix.latitude.toBigDecimal()) == 0 &&
            row.number("longitude").compareTo(fix.longitude.toBigDecimal()) == 0 &&
            row.number("accuracy").compareTo(fix.accuracy.toString().toBigDecimal()) == 0)
        val id = uuid(row.text("id")); val punch = Instant.parse(row.text("official_punch_at"))
        require(!Instant.parse(row.text("attached_at")).isBefore(punch))
        return ProofReceipt(id, punch)
    }
    fun wireAction(action: AttendanceAction): String = when(action) {
        AttendanceAction.TimeIn -> "time_in"; AttendanceAction.TimeOut -> "time_out"; else -> error("action")
    }
}

/** Main-dispatcher owner. One attempt per authorization lease, never transferable. Durable metadata is optional and contains no proof material.
 * Unknown finalization permits receipt reads only; absence never permits another punch. */
internal class AttendanceSubmission(
    parent: CoroutineScope, private val backend: ProofBackend, private val uid: String,
    private val action: AttendanceAction, private val current: () -> Boolean,
    private val nextAction: () -> AttendanceAction?, private val revalidate: suspend () -> Boolean,
    private val refresh: suspend () -> Unit, private val elapsed: () -> Long,
    private val clock: () -> Instant = Instant::now, private val deadlineMillis: Long = 20_000,
    private val journal: ph.edu.bsit.tcc.ojtdtr.recovery.RecoveryJournal? = null,
    private val onJournalChanged: (Boolean) -> Unit = {},
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(SubmissionState.Ready)
    val state = mutable.asStateFlow()
    private val errors = MutableStateFlow<SubmissionProblem?>(null)
    val problem = errors.asStateFlow()
    private val requestId = UUID.randomUUID().toString()
    private var work: Job? = null
    private var ticket: PreparedProof? = null
    private var location: Fix? = null
    private var sent = false
    @Volatile private var closed = false
    private var invoked = false
    private var durable: ph.edu.bsit.tcc.ojtdtr.recovery.RecoveryRecord? = null
    private suspend fun persist(phase: ph.edu.bsit.tcc.ojtdtr.recovery.RecoveryPhase, prepared: PreparedProof? = ticket) {
        // The production disabled adapter dispatches nothing and must not create phantom attempts.
        if (backend === DisabledProofBackend || journal == null) return
        checkCurrent()
        val previous = durable
        try {
            durable = withContext(Dispatchers.IO) {
                if (previous == null) journal.begin(uid, requestId, ProofContract.wireAction(action), { !closed && current() })
                else journal.advance(previous, phase, { !closed && current() }, prepared?.uploadId, prepared?.sessionId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // An error can follow a successful rename. Block until an explicit read settles storage.
            if (!closed && current()) onJournalChanged(true)
            throw failure
        }
        checkCurrent()
        onJournalChanged(true)
    }
    private fun checkCurrent() { if (closed || !current()) throw SubmissionFailure(SubmissionProblem.Authorization) }
    private suspend fun authorize() { checkCurrent(); if (!revalidate()) throw SubmissionFailure(SubmissionProblem.Authorization); checkCurrent() }
    fun submit(proof: ConfirmedProof) {
        if (closed || invoked || work?.isActive == true || mutable.value != SubmissionState.Ready) { proof.bytes.fill(0); return }
        invoked = true
        work = scope.launch {
            try {
                authorize()
                if (nextAction() != action || action == AttendanceAction.None) throw SubmissionFailure(SubmissionProblem.Conflict)
                if (proof.bytes.size !in 1..5242880 || !proof.fix.valid(elapsed())) throw SubmissionFailure(SubmissionProblem.InvalidProof)
                location = proof.fix
                mutable.value = SubmissionState.Preparing
                persist(ph.edu.bsit.tcc.ojtdtr.recovery.RecoveryPhase.PrepareIntent)
                val raw = withTimeout(deadlineMillis) { backend.prepare(requestId, action) }
                checkCurrent()
                val prepared = ProofContract.prepared(raw, uid, clock()); ticket = prepared
                persist(ph.edu.bsit.tcc.ojtdtr.recovery.RecoveryPhase.Prepared)
                authorize()
                if (!proof.fix.valid(elapsed())) throw SubmissionFailure(SubmissionProblem.InvalidProof)
                mutable.value = SubmissionState.Uploading
                persist(ph.edu.bsit.tcc.ojtdtr.recovery.RecoveryPhase.UploadIntent)
                withTimeout(deadlineMillis) { backend.upload(ProofContract.BUCKET, prepared.path, proof.bytes) }
                persist(ph.edu.bsit.tcc.ojtdtr.recovery.RecoveryPhase.Uploaded)
                authorize()
                if (clock() >= prepared.expires || !proof.fix.valid(elapsed())) throw SubmissionFailure(SubmissionProblem.InvalidProof)
                persist(ph.edu.bsit.tcc.ojtdtr.recovery.RecoveryPhase.FinalizeIntent)
                mutable.value = SubmissionState.Finalizing; sent = true
                val response = withTimeout(deadlineMillis) { backend.finalize(prepared.uploadId, proof.fix) }
                checkCurrent()
                ProofContract.receipt(response, uid, action, prepared, proof.fix) ?: error("receipt")
                complete()
            } catch (_: TimeoutCancellationException) { failure(SubmissionProblem.Network) }
            catch (cancelled: CancellationException) {
                if (!closed && current()) mutable.value = if(mutable.value == SubmissionState.Completed) SubmissionState.Completed else if(sent) SubmissionState.OutcomeUnknown else SubmissionState.Cancelled
                throw cancelled
            } catch (error: ProofTransportFailure) { failure(error.submissionProblem()) }
            catch (failure: SubmissionFailure) { failure(failure.problem) }
            catch (_: Exception) { failure(SubmissionProblem.Unavailable) }
            finally { proof.bytes.fill(0) }
        }
    }
    private fun failure(problem: SubmissionProblem) {
        if (closed || mutable.value == SubmissionState.Completed) return
        if (!current()) { location = null; mutable.value = if(sent) SubmissionState.OutcomeUnknown else SubmissionState.Cancelled; return }
        if (!sent) location = null
        errors.value = problem
        mutable.value = if (sent) SubmissionState.OutcomeUnknown else if(problem == SubmissionProblem.Disabled)
            SubmissionState.Disabled else SubmissionState.Failed
    }
    private suspend fun complete() {
        checkCurrent()
        if (durable != null) {
            val confirmed = durable!!
            withContext(Dispatchers.IO) { journal!!.confirmAndClear(confirmed) { !closed && current() } }
            checkCurrent()
            onJournalChanged(false)
        }
        mutable.value = SubmissionState.Completed; errors.value = null; location = null
        // UI/widget is always updated by the existing trusted coordinator, never by receipt data.
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The confirmed punch remains completed; read refresh can fail independently. */ }
    }
    fun reconcile() {
        if (closed || !current() || work?.isActive == true || mutable.value != SubmissionState.OutcomeUnknown) return
        val prepared = ticket ?: return; val fix = location ?: return
        work = scope.launch {
            try {
                authorize()
                val raw = withTimeout(deadlineMillis) { backend.receipt(prepared.uploadId) }
                checkCurrent()
                if (ProofContract.receipt(raw, uid, action, prepared, fix, true) != null) complete()
                // Empty receipt can mean an in-flight transaction; never automatically finalize or replace.
            } catch (_: TimeoutCancellationException) { failure(SubmissionProblem.Network) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ProofTransportFailure) { failure(error.submissionProblem()) }
            catch (_: Exception) { failure(SubmissionProblem.Unavailable) }
        }
    }
    fun close() {
        if (closed) return
        closed = true; work?.cancel(); job.cancel(); location = null
        // Keep confirmed and definite terminal outcomes while releasing resources.
        // A dispatched punch is uncertain even when its local coroutine is cancelled.
        mutable.value = when (mutable.value) {
            SubmissionState.Completed, SubmissionState.Failed, SubmissionState.Cancelled,
            SubmissionState.OutcomeUnknown, SubmissionState.Disabled -> mutable.value
            else -> if (sent) SubmissionState.OutcomeUnknown else SubmissionState.Cancelled
        }
    }
    internal suspend fun awaitOperations() { job.children.toList().joinAll() }
}
