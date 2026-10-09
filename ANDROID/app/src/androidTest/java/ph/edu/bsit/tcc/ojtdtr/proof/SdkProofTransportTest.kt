@file:OptIn(kotlin.time.ExperimentalTime::class)
package ph.edu.bsit.tcc.ojtdtr.proof

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Actual pinned SDK HTTP encoding, test.invalid MockEngine only; no hosted endpoint. */
class SdkProofTransportTest {
    private val uid="00000000-0000-4000-8000-000000000001"
    private val id="00000000-0000-4000-8000-000000000002"
    private val other="00000000-0000-4000-8000-000000000003"
    private fun session(token:String="synthetic-access", user:String=uid)=UserSession(
        accessToken=token,refreshToken="synthetic-refresh",expiresIn=3600,tokenType="bearer",
        user=UserInfo(aud="authenticated",id=user),expiresAt=Clock.System.now()+3600.seconds)
    private fun client(engine:MockEngine)=createSupabaseClient("https://test.invalid","sb_publishable_synthetic_fixture_only") {
        httpEngine=engine;defaultLogLevel=LogLevel.NONE
        install(Auth) { autoLoadFromStorage=false;autoSaveToStorage=false;alwaysAutoRefresh=false }
    }
    @Test fun wireMethodsHeadersBodiesAndOwnerReceiptFilters()=runBlocking {
        val seen=mutableListOf<io.ktor.client.request.HttpRequestData>()
        val c=client(MockEngine { r->seen+=r;respond("{}",HttpStatusCode.OK,headersOf("Content-Type","application/json")) })
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val t=SdkProofTransport(c,{true})
            val prepare=buildJsonObject{put("request_id",id);put("action_type","time_in")}
            t.rpc("attendance_proof_prepare",prepare)
            val bytes=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte())
            t.upload("$uid/$other/$id/proof",bytes,"image/jpeg")
            val finish=buildJsonObject{put("upload_id",id);put("latitude",14.5);put("longitude",121.0);put("accuracy",8f)}
            t.rpc("attendance_proof_finalize",finish);t.receipt(uid,id)
            assertEquals(4,seen.size)
            assertTrue(seen.all {it.url.host=="test.invalid" && it.headers[HttpHeaders.Authorization]=="Bearer synthetic-access"})
            assertTrue(seen.all {it.headers["apikey"]=="sb_publishable_synthetic_fixture_only"})
            assertEquals("/rest/v1/rpc/attendance_proof_prepare",seen[0].url.encodedPath)
            assertEquals(prepare,Json.parseToJsonElement(String(seen[0].body.toByteArray())))
            assertEquals(HttpMethod.Post,seen[1].method)
            assertEquals("/storage/v1/object/attendance-proofs/$uid/$other/$id/proof",seen[1].url.encodedPath)
            assertEquals("false",seen[1].headers["x-upsert"])
            assertEquals(ContentType.Image.JPEG,seen[1].body.contentType)
            assertArrayEquals(bytes,seen[1].body.toByteArray())
            assertEquals(finish,Json.parseToJsonElement(String(seen[2].body.toByteArray())))
            assertEquals(HttpMethod.Get,seen[3].method)
            assertEquals("eq.$uid",seen[3].url.parameters["student_uid"])
            assertEquals("eq.$id",seen[3].url.parameters["upload_id"])
            assertEquals("*",seen[3].url.parameters["select"])
        } finally {c.close()}
    }
    @Test fun sessionTokenReplacementCannotUseCapturedTransport()=runBlocking {
        var calls=0;val c=client(MockEngine{calls++;respond("[]")})
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val t=SdkProofTransport(c,{true});c.auth.importSession(session("replacement"),autoRefresh=false)
            try {t.receipt(uid,id);fail()} catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Authorization,e.problem)}
            assertEquals(0,calls)
        } finally {c.close()}
    }
    @Test fun sdkIdentityReplacementWhileReadBlockedRejectsActualLateResponse()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val c=client(MockEngine {withContext(NonCancellable){entered.complete(Unit);release.await()};respond("[]")})
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val t=SdkProofTransport(c,{true})
            val work=launch {try {t.receipt(uid,id);fail()}catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Authorization,e.problem)}}
            entered.await();c.auth.importSession(session("replacement",other),autoRefresh=false);release.complete(Unit);work.join()
            assertEquals(other,c.auth.currentSessionOrNull()!!.user!!.id)
        } finally {release.complete(Unit);c.close()}
    }
    @Test fun forbiddenUploadAndInvalidLeaseNeverDispatch()=runBlocking {
        var calls=0;val c=client(MockEngine{calls++;respond("{}")})
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val t=SdkProofTransport(c,{true})
            for(path in listOf("../outside","$other/$other/$id/proof","$uid/$other/$id/other")) {
                try{t.upload(path,byteArrayOf(1),"image/jpeg");fail()}catch(_:IllegalArgumentException){}
            }
            try{t.upload("$uid/$other/$id/proof",byteArrayOf(1),"image/png");fail()}catch(_:IllegalArgumentException){}
            try{SdkProofTransport(c,{false}).receipt(uid,id);fail()}catch(_:ProofTransportFailure){}
            assertEquals(0,calls)
        } finally {c.close()}
    }
    @Test fun httpRejectionRemainsStatusWithoutLoggingBody()=runBlocking {
        val c=client(MockEngine{respond("private-server-body",HttpStatusCode.Forbidden)})
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val r=SdkProofTransport(c,{true}).receipt(uid,id);assertEquals(403,r.status)
        } finally {c.close()}
    }
    @Test fun encodedTraversalAndUnicodePathsNeverDispatch()=runBlocking {
        var calls=0;val c=client(MockEngine{calls++;respond("{}")})
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val t=SdkProofTransport(c,{true})
            for(path in listOf("$uid/$other/$id/proof%2fextra", "$uid/$other/$id/proof%252fextra",
                "$uid/$other/$id/..", "$uid/$other/$id/proof?x=1", "$uid/$other/$id/proof#x",
                "$uid/$other/$id/prоof", "$uid/$other/$id/proof\\outside", "$uid/%2f$other/$id/proof")) {
                try{t.upload(path,byteArrayOf(1),"image/jpeg");fail("Unexpected path accepted")}
                catch(_:IllegalArgumentException){}
            }
            assertEquals(0,calls)
        } finally {c.close()}
    }
    private suspend fun operation(t:SdkProofTransport,stage:String) {
        when(stage) {
            "prepare" -> t.rpc("attendance_proof_prepare",buildJsonObject{put("request_id",id);put("action_type","time_in")})
            "upload" -> t.upload("$uid/$other/$id/proof",byteArrayOf(1),"image/jpeg")
            "finalize" -> t.rpc("attendance_proof_finalize",buildJsonObject{put("upload_id",id);put("latitude",14.599512345678);put("longitude",120.9842123456);put("accuracy",3.14f)})
            else -> t.receipt(uid,id)
        }
    }
    @Test fun sdkReplacementAcrossEveryHttpStageRejectsLateAAndPreservesB()=runBlocking {
        for(stage in listOf("prepare","upload","finalize","receipt")) for(replaceUser in listOf(false,true)) {
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val seen=mutableListOf<io.ktor.client.request.HttpRequestData>()
            val c=client(MockEngine{r->
                seen+=r
                if(seen.size==1) withContext(NonCancellable){entered.complete(Unit);release.await()}
                respond("[]",HttpStatusCode.OK,headersOf("Content-Type","application/json"))
            })
            try {
                c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                val t=SdkProofTransport(c,{true})
                val work=launch {
                    try{operation(t,stage);fail("Late old-session response accepted")}
                    catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Authorization,e.problem)}
                }
                entered.await();val replacementUid=if(replaceUser) other else uid
                c.auth.importSession(session("replacement",replacementUid),autoRefresh=false)
                release.complete(Unit);work.join()
                assertEquals(replacementUid,c.auth.currentSessionOrNull()!!.user!!.id)
                assertEquals("Bearer synthetic-access",seen.single().headers[HttpHeaders.Authorization])
                assertEquals("[]",SdkProofTransport(c,{true}).receipt(replacementUid,id).body)
                assertEquals("Bearer replacement",seen.last().headers[HttpHeaders.Authorization])
                assertEquals(2,seen.size)
            } finally {release.complete(Unit);c.close()}
        }
    }
    @Test fun cancellationAcrossSdkHttpStagesPropagatesWithoutResend()=runBlocking {
        for(stage in listOf("prepare","upload","finalize","receipt")) {
            var calls=0;val entered=CompletableDeferred<Unit>()
            val c=client(MockEngine{calls++;entered.complete(Unit);awaitCancellation()})
            try {
                c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                val t=SdkProofTransport(c,{true})
                val work=launch{operation(t,stage)};entered.await();work.cancelAndJoin()
                assertTrue(work.isCancelled);assertEquals(1,calls)
            } finally {c.close()}
        }
    }
    @Test fun redirectProbePreservesSdkPostRestrictionAndStripsCrossOriginBearer()=runBlocking {
        val seen=mutableListOf<io.ktor.client.request.HttpRequestData>()
        val c=client(MockEngine{r->seen+=r
            if(r.url.host=="test.invalid") respond("",HttpStatusCode.Found,headersOf("Location","https://redirect.invalid/receipt"))
            else respond("[]",HttpStatusCode.OK)
        })
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val t=SdkProofTransport(c,{true})
            val post=t.rpc("attendance_proof_prepare",buildJsonObject{put("request_id",id);put("action_type","time_in")})
            assertEquals(302,post.status);assertEquals(1,seen.size)
            val get=t.receipt(uid,id);assertEquals(200,get.status);assertEquals(3,seen.size)
            assertEquals("redirect.invalid",seen.last().url.host)
            assertNull(seen.last().headers[HttpHeaders.Authorization])
            // The retained public client API key is not a privileged credential/JWT.
            assertEquals("sb_publishable_synthetic_fixture_only",seen.last().headers["apikey"])
        } finally {c.close()}
    }

    @Test fun boundedHttpSmallExactLimitAndUnknownLengthResponses()=runBlocking {
        for(raw in listOf("{\"ok\":true}","[]"+" ".repeat(PROOF_RESPONSE_MAX_BYTES-2))) {
            val channel=ByteReadChannel(raw.toByteArray())
            val c=client(MockEngine{respond(channel,HttpStatusCode.OK,headersOf("Content-Type","application/json"))})
            try {
                c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                val r=SdkProofTransport(c,{true}).receipt(uid,id)
                assertEquals(raw,r.body);assertTrue(channel.isClosedForRead)
            } finally {c.close()}
        }
    }
    @Test fun boundedHttpIgnoresUntrustedContentLength()=runBlocking {
        for(length in listOf("0","1","${Long.MAX_VALUE}","invalid")) {
            val channel=ByteReadChannel("[]".toByteArray())
            val c=client(MockEngine{respond(channel,HttpStatusCode.OK,headersOf("Content-Length",length))})
            try {
                c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                assertEquals("[]",SdkProofTransport(c,{true}).receipt(uid,id).body)
                assertTrue(channel.isClosedForRead)
            } finally {c.close()}
        }
    }
    @Test fun boundedHttpRejectsSuccessfulErrorAndMalformedOverflowWithoutBodyDiagnostics()=runBlocking {
        for(status in listOf(HttpStatusCode.OK,HttpStatusCode.Forbidden,HttpStatusCode.InternalServerError))
            for(length in listOf(null,"1","${PROOF_RESPONSE_MAX_BYTES}")) {
            val channel=ByteReadChannel(ByteArray(PROOF_RESPONSE_MAX_BYTES+1){'x'.code.toByte()})
            val headers=if(length==null) headersOf("Content-Type","application/json") else headersOf("Content-Length",length)
            val c=client(MockEngine{respond(channel,status,headers)})
            try {
                c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                try{SdkProofTransport(c,{true}).receipt(uid,id);fail("Overflow accepted")}
                catch(e:ProofTransportFailure){
                    assertEquals(ProofTransportProblem.Unavailable,e.problem);assertNull(e.message);assertNull(e.cause)
                    assertFalse(e.stackTraceToString().contains("xxxx"))
                }
                assertTrue(channel.isClosedForRead)
            } finally {c.close()}
        }
    }
    @Test fun boundedHttpStopsHugeStreamingResponseAndReleasesProducer()=runBlocking {
        val channel=ByteChannel(autoFlush=true);val finished=CompletableDeferred<Unit>();var written=0
        val writer=launch {
            val chunk=ByteArray(4096){'x'.code.toByte()}
            try {repeat(4096){channel.writeFully(chunk);written+=chunk.size}}
            catch(_:Exception) { /* Reader rejects and cancels this synthetic stream. */ }
            finally {channel.close();finished.complete(Unit)}
        }
        val c=client(MockEngine{respond(channel,HttpStatusCode.OK,headersOf("Content-Type","application/json"))})
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            try{SdkProofTransport(c,{true}).receipt(uid,id);fail("Huge stream accepted")}
            catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Unavailable,e.problem)}
            withTimeout(3000){finished.await();writer.join()}
            assertTrue(channel.isClosedForRead);assertTrue(written<4096*4096)
        } finally {channel.cancel(null);writer.cancelAndJoin();c.close()}
    }
    @Test fun boundedHttpCancellationDuringReadClosesWithoutResend()=runBlocking {
        val channel=ByteChannel(autoFlush=true);channel.writeFully("[".toByteArray())
        val entered=CompletableDeferred<Unit>();var calls=0
        val observed=object:ByteReadChannel by channel {
            override suspend fun awaitContent(min:Int):Boolean {entered.complete(Unit);return channel.awaitContent(min)}
        }
        val c=client(MockEngine{calls++;respond(observed,HttpStatusCode.OK)})
        try {
            c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val work=launch{SdkProofTransport(c,{true}).receipt(uid,id)}
            entered.await();work.cancelAndJoin()
            assertTrue(work.isCancelled);assertTrue(channel.isClosedForRead);assertEquals(1,calls)
        } finally {channel.cancel(null);c.close()}
    }

}
