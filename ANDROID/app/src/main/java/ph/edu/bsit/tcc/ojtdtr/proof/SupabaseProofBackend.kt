package ph.edu.bsit.tcc.ojtdtr.proof

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceAction

/** Fixed classifications only: never retain HTTP bodies, paths or SDK exceptions. */
internal enum class ProofTransportProblem { Authorization, Rejected, Unavailable, OutcomeUnknown }
internal class ProofTransportFailure(val problem: ProofTransportProblem) : Exception()
internal fun ProofTransportFailure.submissionProblem(): SubmissionProblem = when (problem) {
    ProofTransportProblem.Authorization -> SubmissionProblem.Authorization
    ProofTransportProblem.Rejected -> SubmissionProblem.Denied
    ProofTransportProblem.Unavailable -> SubmissionProblem.Unavailable
    ProofTransportProblem.OutcomeUnknown -> SubmissionProblem.Network
}
internal class ProofWireResponse(val status: Int, val body: String)
internal interface ProofTransport {
    val ownerId: String?
    suspend fun rpc(name: String, arguments: JsonObject): ProofWireResponse
    suspend fun upload(path: String, bytes: ByteArray, mime: String): ProofWireResponse
    suspend fun receipt(uid: String, uploadId: String): ProofWireResponse
}

/** Isolated contract adapter. No default transport, no navigation factory, no enabling switch.
 * All calls belong to one coordinator lease; callers must supply its currentProof predicate.
 * Reservation metadata is validation context, not another attendance state machine. */
internal class SupabaseProofBackend(
    private val uid: String,
    private val current: () -> Boolean,
    private val transport: ProofTransport?,
    private val clock: () -> Instant = Instant::now,
    private val timeoutMillis: Long = 20_000,
) : ProofBackend {
    private val mutex = Mutex()
    private var request: String? = null
    private var action: AttendanceAction? = null
    private var ticket: PreparedProof? = null
    private var uploading = false
    private var uploaded = false
    private var finalizing = false
    private var location: Fix? = null
    private fun owner() {
        if (!current()) throw ProofTransportFailure(ProofTransportProblem.Authorization)
        ProofContract.uuid(uid)
    }
    private suspend fun call(operation: suspend (ProofTransport) -> ProofWireResponse): String {
        owner()
        val wire = transport ?: throw SubmissionFailure(SubmissionProblem.Disabled)
        if (wire.ownerId != uid) throw ProofTransportFailure(ProofTransportProblem.Authorization)
        try {
            val response = withTimeout(timeoutMillis) { owner(); operation(wire) }
            owner()
            if (response.status !in 200..299) throw ProofTransportFailure(when (response.status) {
                401, 403 -> ProofTransportProblem.Authorization
                404 -> ProofTransportProblem.Unavailable
                in 400..499 -> ProofTransportProblem.Rejected
                else -> ProofTransportProblem.OutcomeUnknown
            })
            return response.body
        } catch (_: TimeoutCancellationException) {
            throw ProofTransportFailure(ProofTransportProblem.OutcomeUnknown)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ProofTransportFailure) { throw failure }
        catch (failure: SubmissionFailure) { throw failure }
        catch (_: Exception) { throw ProofTransportFailure(ProofTransportProblem.OutcomeUnknown) }
    }
    private fun invalid(): Nothing = throw ProofTransportFailure(ProofTransportProblem.Rejected)
    private inline fun <T> validate(block: () -> T): T = try { block() }
        catch (_: Exception) { invalid() }
    override suspend fun prepare(requestId: String, action: AttendanceAction): String = mutex.withLock {
        owner()
        validate { ProofContract.uuid(requestId); ProofContract.wireAction(action) }
        if (request != null) invalid() // Submission owns retries; never dispatch a second reservation here.
        request = requestId; this.action = action
        val raw = call { it.rpc("attendance_proof_prepare", buildJsonObject {
            put("request_id", requestId); put("action_type", ProofContract.wireAction(action))
        }) }
        ticket = validate { ProofContract.prepared(raw, uid, clock()) }
        raw
    }
    override suspend fun upload(bucket: String, path: String, jpeg: ByteArray): Unit = mutex.withLock {
        owner()
        val reserved = ticket ?: invalid()
        if (bucket != ProofContract.BUCKET || path != reserved.path || clock() >= reserved.expires || uploading ||
            jpeg.size !in 3..5242880 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte() || jpeg[2] != 0xff.toByte()) invalid()
        uploading = true // A timeout does not authorize overwriting or automatic resend.
        call { it.upload(reserved.path, jpeg, "image/jpeg") }
        uploaded = true
    }
    override suspend fun finalize(uploadId: String, fix: Fix): String = mutex.withLock {
        owner()
        val reserved = ticket ?: invalid()
        if (uploadId != reserved.uploadId || !uploaded || finalizing || clock() >= reserved.expires ||
            !fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0 ||
            !fix.accuracy.isFinite() || fix.accuracy < 0) invalid()
        finalizing = true; location = fix
        val raw = call { it.rpc("attendance_proof_finalize", buildJsonObject {
            put("upload_id", uploadId); put("latitude", fix.latitude)
            put("longitude", fix.longitude); put("accuracy", fix.accuracy)
        }) }
        validate { ProofContract.receipt(raw, uid, action ?: invalid(), reserved, fix) ?: invalid() }
        raw
    }
    override suspend fun receipt(uploadId: String): String = mutex.withLock {
        owner()
        val reserved = ticket ?: invalid(); val fix = location ?: invalid()
        if (!finalizing || reserved.uploadId != uploadId) invalid()
        val raw = call { it.receipt(uid, reserved.uploadId) }
        validate { ProofContract.receipt(raw, uid, action ?: invalid(), reserved, fix, query = true) }
        raw // [] is an unknown outcome, not failure or permission to resubmit.
    }
}
