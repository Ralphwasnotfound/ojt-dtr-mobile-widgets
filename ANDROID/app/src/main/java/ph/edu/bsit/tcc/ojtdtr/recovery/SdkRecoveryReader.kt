@file:OptIn(kotlin.time.ExperimentalTime::class)
package ph.edu.bsit.tcc.ojtdtr.recovery

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import kotlin.time.Clock
import ph.edu.bsit.tcc.ojtdtr.proof.*

/** GET receipts and two allowlisted read-only POST RPCs are exposed. Captures the existing coordinator session/client; cannot mutate/upload. */
internal class SdkRecoveryReader(private val client: SupabaseClient, private val current: () -> Boolean) : RecoveryReader {
    private val session = client.auth.currentSessionOrNull()
    private fun owner(record: RecoveryRecord) {
        val live = client.auth.currentSessionOrNull()
        if (!current() || session == null || live == null || live.user?.id != record.owner ||
            session.user?.id != record.owner || session.accessToken != live.accessToken ||
            live.expiresAt <= Clock.System.now() || client.auth.sessionStatus.value !is SessionStatus.Authenticated)
            throw ProofTransportFailure(ProofTransportProblem.Authorization)
    }
    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    override suspend fun observation(record: RecoveryRecord): RecoveryObservation {
        record.validate(); owner(record)
        val name = if (record.upload == null) "attendance_proof_recovery_lookup" else "attendance_proof_recovery_upload_status"
        val arguments = kotlinx.serialization.json.buildJsonObject {
            put("request_id", kotlinx.serialization.json.JsonPrimitive(record.request))
            record.upload?.let { put("upload_id", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        // A short-lived HTTP view shares the SDK engine/session, not an independent auth client.
        // Disable redirects BEFORE dispatch: final-origin checking alone is insufficient for POST.
        val http = client.httpClient.httpClient.config { followRedirects = false }
        try {
            val raw = http.prepareRequest(client.supabaseHttpUrl.trimEnd('/') + "/rest/v1/rpc/$name") {
                owner(record); method = HttpMethod.Post
                header(HttpHeaders.Authorization, "Bearer ${session!!.accessToken}")
                header("apikey", client.supabaseKey)
                contentType(ContentType.Application.Json); setBody(arguments.toString())
            }.execute { response ->
                val channel = response.bodyAsChannel()
                try {
                    owner(record)
                    val body = readProofResponse(channel)
                    owner(record)
                    if (response.status.value in setOf(401, 403))
                        throw ProofTransportFailure(ProofTransportProblem.Authorization)
                    if (response.status.value !in 200..299)
                        throw ProofTransportFailure(ProofTransportProblem.Unavailable)
                    body
                } finally { channel.cancel(null) }
            }
            owner(record)
            return try { recoveryEnvelope(raw, record, record.upload != null) }
            catch (_: Exception) { throw ProofTransportFailure(ProofTransportProblem.Unavailable) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: ProofTransportFailure) { throw failure }
        catch (_: Exception) { throw ProofTransportFailure(ProofTransportProblem.Unavailable) }
        finally { http.close() }
    }
    override suspend fun receipt(record: RecoveryRecord): String {
        record.validate(); owner(record)
        val upload = record.upload ?: throw RecoveryStorageFailure()
        return client.httpClient.prepareRequest(client.supabaseHttpUrl.trimEnd('/') + "/rest/v1/attendance_proofs") {
            owner(record); method = HttpMethod.Get
            header(HttpHeaders.Authorization, "Bearer ${session!!.accessToken}"); header("apikey", client.supabaseKey)
            url {
                parameters.append("select", "id,student_uid,upload_id,attendance_session_id,action_type,official_punch_at,attached_at")
                parameters.append("student_uid", "eq.${record.owner}")
                parameters.append("upload_id", "eq.$upload")
            }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            try {
                owner(record)
                val expectedOrigin = Url(client.supabaseHttpUrl)
                val receivedOrigin = response.call.request.url
                if (receivedOrigin.protocol != expectedOrigin.protocol || receivedOrigin.host != expectedOrigin.host ||
                    receivedOrigin.port != expectedOrigin.port)
                    throw ProofTransportFailure(ProofTransportProblem.Unavailable)
                val body = readProofResponse(channel)
                owner(record)
                if (response.status.value !in 200..299) throw ProofTransportFailure(ProofTransportProblem.Unavailable)
                body
            } finally { channel.cancel(null) }
        }
    }
}
