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
            val task=launch{try{reader.receipt(record);fail()}catch(e:ProofTransportFailure){assertEquals(ProofTransportProblem.Authorization,e.problem)}}
            entered.await();c.auth.importSession(session(b,"synthetic-replacement"),autoRefresh=false);release.complete(Unit);task.join()
            try{reader.receipt(record);fail()}catch(_:ProofTransportFailure){}
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

}
