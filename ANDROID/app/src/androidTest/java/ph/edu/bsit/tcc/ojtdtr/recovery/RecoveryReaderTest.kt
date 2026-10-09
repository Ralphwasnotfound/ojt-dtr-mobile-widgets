@file:OptIn(kotlin.time.ExperimentalTime::class)
package ph.edu.bsit.tcc.ojtdtr.recovery

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import ph.edu.bsit.tcc.ojtdtr.proof.*

/** Actual production GET/stream reader with a MockEngine; never a hosted endpoint. */
class RecoveryReaderTest {
    private val a="00000000-0000-4000-8000-000000000001"
    private val b="00000000-0000-4000-8000-000000000002"
    private val record get()=RecoveryRecord(owner=a,request=b,action="time_in",phase=RecoveryPhase.FinalizeIntent,upload=b,session=a)
    private fun session(uid:String=a,token:String="synthetic-access")=UserSession(accessToken=token,refreshToken="synthetic-refresh",expiresIn=3600,tokenType="bearer",user=UserInfo(aud="authenticated",id=uid),expiresAt=Clock.System.now()+3600.seconds)
    private fun client(engine:MockEngine)=createSupabaseClient("https://test.invalid","sb_publishable_synthetic_fixture_only"){
        httpEngine=engine;defaultLogLevel=LogLevel.NONE
        install(Auth){autoLoadFromStorage=false;autoSaveToStorage=false;alwaysAutoRefresh=false}
    }
    @Test fun actualReaderOnlyGetsOwnerFilteredMinimalReceipt()=runBlocking {
        val seen=mutableListOf<io.ktor.client.request.HttpRequestData>()
        val c=client(MockEngine{r->seen+=r;respond("[]")})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            assertEquals("[]",SdkRecoveryReader(c,{true}).receipt(record))
            val r=seen.single();assertEquals(HttpMethod.Get,r.method);assertEquals("/rest/v1/attendance_proofs",r.url.encodedPath)
            assertEquals("eq.$a",r.url.parameters["student_uid"]);assertEquals("eq.$b",r.url.parameters["upload_id"])
            assertEquals(setOf("id","student_uid","upload_id","attendance_session_id","action_type","official_punch_at","attached_at"),r.url.parameters["select"]!!.split(',').toSet())
        }finally{c.close()}
    }
    @Test fun replacementAcrossBlockedReadCannotPublishOrReuseOldReader()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val c=client(MockEngine{calls++;withContext(NonCancellable){entered.complete(Unit);release.await()};respond("[]")})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false);val reader=SdkRecoveryReader(c,{true})
            val task=launch{try{reader.observation(record);fail()}catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Authorization,e.problem)}}
            entered.await();c.auth.importSession(session(b,"synthetic-replacement"),autoRefresh=false);release.complete(Unit);task.join()
            try{reader.observation(record);fail()}catch(_:ProofTransportFailure){}
            assertEquals(1,calls);assertEquals(b,c.auth.currentSessionOrNull()!!.user!!.id)
        }finally{release.complete(Unit);c.close()}
    }
    @Test fun revokedLeaseAndWrongOwnerNeverDispatch()=runBlocking {
        var calls=0;val c=client(MockEngine{calls++;respond("[]")})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            try{SdkRecoveryReader(c,{false}).receipt(record);fail()}catch(_:ProofTransportFailure){}
            try{SdkRecoveryReader(c,{true}).receipt(record.copy(owner=b));fail()}catch(_:ProofTransportFailure){}
            assertEquals(0,calls)
        }finally{c.close()}
    }
    @Test fun oversizedRecoveryResponseUsesRealBoundedReader()=runBlocking {
        val channel=ByteReadChannel(ByteArray(PROOF_RESPONSE_MAX_BYTES+1){1})
        val c=client(MockEngine{respond(channel,HttpStatusCode.OK)})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            try{SdkRecoveryReader(c,{true}).receipt(record);fail()}catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Unavailable,e.problem);assertNull(e.message);assertNull(e.cause)}
            assertTrue(channel.isClosedForRead)
        }finally{c.close()}
    }
    @Test fun forbiddenRecoveryCannotBecomeCompletion()=runBlocking {
        val c=client(MockEngine{respond("private-synthetic-sentinel",HttpStatusCode.Forbidden)})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            try{SdkRecoveryReader(c,{true}).receipt(record);fail()}catch(e:ProofTransportFailure){assertNull(e.message);assertNull(e.cause)}
        }finally{c.close()}
    }
    @Test fun redirectedReceiptFromDifferentOriginCannotBeAccepted()=runBlocking {
        for(destination in listOf("https://other.invalid/receipt","https://test.invalid:8443/receipt","http://test.invalid/receipt")) {
            val c=client(MockEngine{r->
                if(r.url.encodedPath=="/rest/v1/attendance_proofs") respond("",HttpStatusCode.Found,headersOf("Location",destination))
                else respond("[]",HttpStatusCode.OK)
            })
            try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                try{SdkRecoveryReader(c,{true}).receipt(record);fail("Foreign-origin receipt accepted")}
                catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Unavailable,e.problem);assertNull(e.message);assertNull(e.cause)}
            }finally{c.close()}
        }
    }

    private fun rpcEnvelope(objectStatus:String="present") = """{"contract_version":1,"observed_at":"2026-10-09T00:00:00Z","student_uid":"$a","request_id":"$b","lookup_status":"found","reservation":{"upload_id":"$b","attendance_session_id":"$a","action_type":"time_in","state":"pending","expires_at":"2026-10-09T01:00:00Z","expired":false},"object_status":"$objectStatus","receipt_status":"missing","receipt":null}"""
    @Test fun actualRpcOnlyPostsExactReadArgumentsAndCanReuseSdkEngine()=runBlocking {
        val seen=mutableListOf<io.ktor.client.request.HttpRequestData>()
        val c=client(MockEngine{r->seen+=r;respond(rpcEnvelope(if(r.url.encodedPath.endsWith("lookup"))"not_checked" else "present"))})
        try {c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val reader=SdkRecoveryReader(c,{true})
            val lost=record.copy(phase=RecoveryPhase.PrepareIntent,upload=null,session=null)
            assertFalse(reader.observation(lost).confirmed)
            assertFalse(reader.observation(record).confirmed)
            assertEquals(2,seen.size)
            for(r in seen){assertEquals(HttpMethod.Post,r.method);assertTrue(r.url.encodedPath.startsWith("/rest/v1/rpc/attendance_proof_recovery_"))}
            val first=seen[0].body as io.ktor.http.content.TextContent
            val second=seen[1].body as io.ktor.http.content.TextContent
            assertEquals("{\"request_id\":\"$b\"}",first.text)
            assertEquals("{\"request_id\":\"$b\",\"upload_id\":\"$b\"}",second.text)
            assertEquals(a,c.auth.currentSessionOrNull()!!.user!!.id)
        } finally {c.close()}
    }
    @Test fun rpcRedirectIsRejectedBeforeSecondRequest()=runBlocking {
        for(code in listOf(HttpStatusCode.Found,HttpStatusCode.TemporaryRedirect,HttpStatusCode.PermanentRedirect)) {
            var calls=0;val c=client(MockEngine{calls++;respond("",code,headersOf("Location","https://other.invalid/leak"))})
            try {c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                try{SdkRecoveryReader(c,{true}).observation(record);fail()}catch(_:ProofTransportFailure){}
                assertEquals(1,calls)
            }finally{c.close()}
        }
    }
    @Test fun rpcRevocationDuringBlockedResponsePreservesLeaseSafety()=runBlocking {
        var current=true;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val c=client(MockEngine{withContext(NonCancellable){entered.complete(Unit);release.await()};respond(rpcEnvelope())})
        try {c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val job=launch{try{SdkRecoveryReader(c,{current}).observation(record);fail()}catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Authorization,e.problem)}}
            entered.await();current=false;release.complete(Unit);job.join()
        }finally{release.complete(Unit);c.close()}
    }
    @Test fun rpcSessionRefreshInvalidatesCapturedReader()=runBlocking {
        var calls=0;val c=client(MockEngine{calls++;respond(rpcEnvelope())})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false);val reader=SdkRecoveryReader(c,{true})
            c.auth.importSession(session(token="synthetic-refreshed"),autoRefresh=false)
            try{reader.observation(record);fail()}catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Authorization,e.problem)}
            assertEquals(0,calls);assertFalse(SdkRecoveryReader(c,{true}).observation(record).confirmed)
        }finally{c.close()}
    }
    @Test fun rpcServerErrorsAndMalformedBodiesAreSanitized()=runBlocking {
        for(code in listOf(HttpStatusCode.Unauthorized,HttpStatusCode.Forbidden,HttpStatusCode.NotFound,HttpStatusCode.TooManyRequests,HttpStatusCode.OK)) {
            val c=client(MockEngine{respond("private-synthetic-sentinel",code)})
            try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                try{SdkRecoveryReader(c,{true}).observation(record);fail()}catch(e:ProofTransportFailure){assertNull(e.message);assertNull(e.cause)}
            }finally{c.close()}
        }
    }
    @Test fun rpcCancellationClosesStreamAndPreservesCancellation()=runBlocking {
        val entered=CompletableDeferred<Unit>();val channel=io.ktor.utils.io.ByteChannel()
        val observed=object:ByteReadChannel by channel {
            override suspend fun awaitContent(min:Int):Boolean {
                entered.complete(Unit)
                return channel.awaitContent(min)
            }
        }
        val c=client(MockEngine{respond(observed,HttpStatusCode.OK)})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            val job=launch{SdkRecoveryReader(c,{true}).observation(record)};entered.await();job.cancelAndJoin();assertTrue(job.isCancelled);assertTrue(channel.isClosedForRead)
        }finally{channel.cancel(null);c.close()}
    }
    @Test fun rpcOversizedStreamIsRejectedAndClosed()=runBlocking {
        val channel=ByteReadChannel(ByteArray(PROOF_RESPONSE_MAX_BYTES+1){1});val c=client(MockEngine{respond(channel,HttpStatusCode.OK)})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            try{SdkRecoveryReader(c,{true}).observation(record);fail()}catch(_:ProofTransportFailure){}
            assertTrue(channel.isClosedForRead)
        }finally{c.close()}
    }

    @Test fun adversarialParserBodiesCannotCrashOrConfirmThroughSdk()=runBlocking {
        val bodies=listOf("{\"padding\":\""+"x".repeat(30000)+"\"}","{\"x\":".repeat(5000)+"null"+"}".repeat(5000),rpcEnvelope()+"{}",rpcEnvelope().replace("\"receipt\":null","\"receipt\":[]"))
        for(body in bodies){
            val c=client(MockEngine{respond(body,HttpStatusCode.OK)})
            try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
                try{SdkRecoveryReader(c,{true}).observation(record);fail()}catch(e:ProofTransportFailure){assertNull(e.message);assertNull(e.cause)}
            }finally{c.close()}
        }
    }
    @Test fun invalidUtf8ResponseIsNeverConfirmation()=runBlocking {
        val bytes=rpcEnvelope().toByteArray()+byteArrayOf(0xc3.toByte(),0x28)
        val c=client(MockEngine{respond(ByteReadChannel(bytes),HttpStatusCode.OK)})
        try{c.auth.awaitInitialization();c.auth.importSession(session(),autoRefresh=false)
            try{SdkRecoveryReader(c,{true}).observation(record);fail()}catch(e:ProofTransportFailure){assertNull(e.message)}
        }finally{c.close()}
    }

}
