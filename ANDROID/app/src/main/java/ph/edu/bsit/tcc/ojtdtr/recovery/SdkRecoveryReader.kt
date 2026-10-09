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

/** Only GET is exposed. Captures the existing coordinator session/client; cannot mutate/upload. */
internal class SdkRecoveryReader(private val client: SupabaseClient, private val current: () -> Boolean) : RecoveryReader {
    private val session = client.auth.currentSessionOrNull()
    private fun owner(record: RecoveryRecord) {
        val live = client.auth.currentSessionOrNull()
        if (!current() || session == null || live == null || live.user?.id != record.owner ||
            session.user?.id != record.owner || session.accessToken != live.accessToken ||
            live.expiresAt <= Clock.System.now() || client.auth.sessionStatus.value !is SessionStatus.Authenticated)
            throw ProofTransportFailure(ProofTransportProblem.Authorization)
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
