@file:OptIn(kotlin.time.ExperimentalTime::class)

package ph.edu.bsit.tcc.ojtdtr.proof

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** Fixed bound for the small reservation/receipt JSON contracts, including error bodies.
 * One sentinel byte distinguishes exact-limit EOF from overflow. Content-Length is untrusted.
 * No String/JSON allocation until bounded EOF; never drain an oversized response. */
internal const val PROOF_RESPONSE_MAX_BYTES = 64 * 1024
internal suspend fun readProofResponse(channel: ByteReadChannel): String {
    val bytes = ByteArray(PROOF_RESPONSE_MAX_BYTES + 1)
    try {
        var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = channel.readAvailable(bytes, count, bytes.size - count)
            if (read < 0) {
                channel.closedCause?.let { throw it }
                break
            }
            count += read
            if (count > PROOF_RESPONSE_MAX_BYTES)
                throw ProofTransportFailure(ProofTransportProblem.Unavailable)
        }
        currentCoroutineContext().ensureActive()
        return String(bytes, 0, count, Charsets.UTF_8)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ProofTransportFailure) { throw failure }
    catch (_: Exception) {
        // Do not retain a stream/decoder exception that might contain remote details.
        throw ProofTransportFailure(ProofTransportProblem.OutcomeUnknown)
    } finally {
        bytes.fill(0)
        channel.cancel(null)
    }
}

/** Reuses the coordinator's client/HTTP engine and captured SDK session; never creates auth.
 * Mandatory lease predicate also checks coordinator user/client/generation/approved authority.
 * No refresh/retry: a refreshed/replaced token requires a new approved coordinator lease.
 * This class has no production construction site. SDK client must keep LogLevel.NONE. */
internal class SdkProofTransport(
    private val client: SupabaseClient,
    private val current: () -> Boolean,
) : ProofTransport {
    private val session = client.auth.currentSessionOrNull()
    override val ownerId: String? get() = session?.user?.id
    private fun owner() {
        val live = client.auth.currentSessionOrNull()
        if (!current() || session == null || live == null || live.user?.id == null ||
            live.user?.id != session.user?.id || live.accessToken != session.accessToken ||
            live.expiresAt <= Clock.System.now() || client.auth.sessionStatus.value !is SessionStatus.Authenticated)
            throw ProofTransportFailure(ProofTransportProblem.Authorization)
    }
    private suspend fun request(path: String, method: HttpMethod, body: Any? = null,
                                mime: ContentType = ContentType.Application.Json): ProofWireResponse {
        owner()
        return client.httpClient.prepareRequest(client.supabaseHttpUrl.trimEnd('/') + path) {
            owner() // Recheck inside the request builder immediately before dispatch.
            this.method = method
            header(HttpHeaders.Authorization, "Bearer ${session!!.accessToken}")
            header("apikey", client.supabaseKey)
            header("x-upsert", "false")
            contentType(mime)
            if (body != null) setBody(body)
        }.execute { response ->
            val channel = response.bodyAsChannel()
            try {
                owner()
                ProofWireResponse(response.status.value, readProofResponse(channel)).also { owner() }
            } finally {
                // Also close when the lease is withdrawn before the reader is entered.
                channel.cancel(null)
            }
        }
    }
    override suspend fun rpc(name: String, arguments: JsonObject): ProofWireResponse {
        require(name in setOf("attendance_proof_prepare", "attendance_proof_finalize"))
        return request("/rest/v1/rpc/$name", HttpMethod.Post, arguments.toString())
    }
    override suspend fun upload(path: String, bytes: ByteArray, mime: String): ProofWireResponse {
        owner()
        require(mime == "image/jpeg" && bytes.size in 1..5242880)
        val parts = path.split('/')
        require(parts.size == 4 && parts[0] == session!!.user!!.id && parts[3] == "proof")
        parts.take(3).forEach { ProofContract.uuid(it) }
        return request("/storage/v1/object/${ProofContract.BUCKET}/$path", HttpMethod.Post, bytes, ContentType.Image.JPEG)
    }
    override suspend fun receipt(uid: String, uploadId: String): ProofWireResponse {
        owner(); require(uid == session!!.user!!.id)
        ProofContract.uuid(uid); ProofContract.uuid(uploadId)
        return request("/rest/v1/attendance_proofs?select=*&student_uid=eq.$uid&upload_id=eq.$uploadId", HttpMethod.Get)
    }
}
