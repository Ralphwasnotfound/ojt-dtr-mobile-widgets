@file:OptIn(kotlin.time.ExperimentalTime::class)
package ph.edu.bsit.tcc.ojtdtr.recovery

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.user.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import ph.edu.bsit.tcc.ojtdtr.auth.*

/** Real coordinator/SDK/Keystore restoration, fake journal and HTTP; no production contact. */
class RecoveryCoordinatorTest {
    private val uid="00000000-0000-4000-8000-000000000001"
    private val other="00000000-0000-4000-8000-000000000002"
    private class Memory:JournalStorage {
        var bytes:ByteArray?=null
        override fun read()=bytes?.copyOf()
        override fun replace(bytes:ByteArray){this.bytes=bytes.copyOf()}
        override fun remove(){bytes=null}
    }
    private fun confirmedEnvelope() = """{"contract_version":1,"observed_at":"2026-10-09T00:00:00Z","student_uid":"$uid","request_id":"$other","lookup_status":"found","reservation":{"upload_id":"$other","attendance_session_id":"$uid","action_type":"time_in","state":"attached","expires_at":"2026-10-09T01:00:00Z","expired":false},"object_status":"present","receipt_status":"confirmed","receipt":{"id":"$other","student_uid":"$uid","upload_id":"$other","attendance_session_id":"$uid","action_type":"time_in","official_punch_at":"2026-10-09T00:00:00Z","attached_at":"2026-10-09T00:00:01Z"}}"""
    private fun user()=UserInfo(aud="authenticated",id=uid,identities=listOf(Identity(id="test",identityData=buildJsonObject{},provider="google",userId=uid)))
    private fun session()=UserSession(accessToken="synthetic-access",refreshToken="synthetic-refresh",expiresIn=3600,tokenType="bearer",user=user(),expiresAt=Clock.System.now()+3600.seconds)
    private suspend fun fixture(lostPrepare:Boolean=false,test:suspend (android.content.Context,String,Memory,MutableList<AuthCoordinator>)->Unit) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext;val id=UUID.randomUUID().toString()
        val root=File(app.noBackupFilesDir,"recovery-coordinator-test-$id").apply{mkdirs()}
        val context=object:ContextWrapper(app){override fun getNoBackupFilesDir()=root}
        val alias="dtr.recovery.coordinator.test.$id";val storage=Memory();val owners=mutableListOf<AuthCoordinator>()
        try{SecureSessionManager(EncryptedAuthStorage(context,alias)).saveSession(session());val j=RecoveryJournal(storage)
            val intent=j.begin(uid,other,"time_in"){true}
            if(!lostPrepare)j.advance(intent,RecoveryPhase.Prepared,{true},other,uid)
            test(context,alias,storage,owners)
        }finally{withContext(Dispatchers.Main){owners.forEach{it.close()}};root.deleteRecursively();KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}}
    }
    private fun engine(role:String="student",status:String="approved",receipt:suspend ()->String,seen:MutableList<String>)=MockEngine{r->
        seen+=r.method.value+" "+r.url.encodedPath
        val body=when{
            r.url.encodedPath.endsWith("/user")->Json.encodeToString(user())
            r.url.encodedPath.endsWith("/profiles")->"""[{"id":"$uid","role":"$role","status":"$status","required_hours":600}]"""
            r.url.encodedPath.contains("/rpc/attendance_proof_recovery_")->receipt()
            r.url.encodedPath.endsWith("/logout")->"{}"
            else->throw IllegalStateException("Unexpected fake endpoint")
        };respond(body,HttpStatusCode.OK,headersOf("Content-Type","application/json"))
    }
    @Test fun pendingRejectedAndAdminCoordinatorCannotReadRecovery()=runBlocking {
        for((role,status,expected)in listOf(Triple("student","pending",AccountState.Pending),Triple("student","rejected",AccountState.Rejected),Triple("admin","approved",AccountState.AdminApproved))){fixture{context,alias,storage,owners->
            val seen=mutableListOf<String>();val mock=engine(role,status,{fail("Unauthorized receipt read");"[]"},seen)
            val owner=withContext(Dispatchers.Main){AuthCoordinator(context,ClientConfiguration.parse("https://test.invalid","sb_publishable_"+"synthetic".repeat(4)),{MockEngine(mock.config)},alias,recoveryStorage=storage).also{owners+=it}}
            withTimeout(15000){owner.state.first{it==expected}}
            assertEquals(RecoveryStatus.Hidden,owner.recovery.value);assertFalse(seen.any{it.contains("attendance_proof_recovery")});assertNotNull(storage.bytes)
        }}
    }
    @Test fun actualLogoutBarrierRejectsCompletedOldRecoveryRead()=runBlocking {
        fixture{context,alias,storage,owners->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val completed=CompletableDeferred<Unit>();val seen=mutableListOf<String>()
            val mock=engine(receipt={withContext(NonCancellable){entered.complete(Unit);release.await()};completed.complete(Unit);confirmedEnvelope()},seen=seen)
            val owner=withContext(Dispatchers.Main){AuthCoordinator(context,ClientConfiguration.parse("https://test.invalid","sb_publishable_"+"synthetic".repeat(4)),{MockEngine(mock.config)},alias,recoveryStorage=storage).also{owners+=it}}
            try{withTimeout(15000){entered.await()}
                val settled=async(Dispatchers.Main,start=CoroutineStart.UNDISPATCHED){owner.awaitAttendanceIdle()}
                withContext(Dispatchers.Main){owner.logout()};release.complete(Unit)
                withTimeout(15000){completed.await();settled.await();owner.state.first{it==AccountState.SignedOut}}
                withContext(Dispatchers.Main){owner.awaitAttendanceIdle()}
                assertEquals(RecoveryStatus.Hidden,owner.recovery.value);assertNotNull(storage.bytes)
                assertTrue(seen.filter{it.contains("attendance_proof_recovery")}.all{it.startsWith("POST ")})
                assertFalse(seen.any{it.contains("prepare")||it.contains("finalize")||it.contains("/storage/")})
            }finally{release.complete(Unit)}
        }
    }
    @Test fun approvedBRemainsIsolatedAfterAOldReadActuallyCompletes()=runBlocking {
        fixture{context,alias,storage,owners->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val completed=CompletableDeferred<Unit>();val seenA=mutableListOf<String>()
            val mockA=engine(receipt={withContext(NonCancellable){entered.complete(Unit);release.await()};completed.complete(Unit);confirmedEnvelope()},seen=seenA)
            val config=ClientConfiguration.parse("https://test.invalid","sb_publishable_"+"synthetic".repeat(4))
            val ownerA=withContext(Dispatchers.Main){AuthCoordinator(context,config,{MockEngine(mockA.config)},alias,recoveryStorage=storage).also{owners+=it}}
            try {
                withTimeout(15000){entered.await()};val original=RecoveryJournal(storage).load()!!
                val settled=async(Dispatchers.Main,start=CoroutineStart.UNDISPATCHED){ownerA.awaitAttendanceIdle()}
                withContext(Dispatchers.Main){ownerA.logout()};withTimeout(15000){ownerA.state.first{it==AccountState.SignedOut}}
                val userB=UserInfo(aud="authenticated",id=other,identities=listOf(Identity(id="test-b",identityData=buildJsonObject{},provider="google",userId=other)))
                val sessionB=session().copy(user=userB,accessToken="synthetic-b-access",refreshToken="synthetic-b-refresh")
                SecureSessionManager(EncryptedAuthStorage(context,alias)).saveSession(sessionB)
                var receiptReadsB=0
                val mockB=MockEngine{r->
                    val body=when {
                        r.url.encodedPath.endsWith("/user")->Json.encodeToString(userB)
                        r.url.encodedPath.endsWith("/profiles")->"""[{"id":"$other","role":"student","status":"approved","required_hours":600}]"""
                        r.url.encodedPath.endsWith("/rpc/attendance_summary")->"""[{"manila_day":"2026-10-09","open_session_id":null,"open_time_in":null,"open_session_ordinal":null,"started_today":false,"starts_today":0,"next_action":"time_in","today_sessions":[],"completed_seconds":10800,"today_completed_seconds":0,"completed_sessions":1,"days_present":1}]"""
                        r.url.encodedPath.contains("attendance_proof_recovery")->{receiptReadsB++;throw IllegalStateException("Cross-owner read")}
                        else->throw IllegalStateException("Unexpected fake endpoint")
                    };respond(body,HttpStatusCode.OK,headersOf("Content-Type","application/json"))
                }
                val ownerB=withContext(Dispatchers.Main){AuthCoordinator(context,config,{MockEngine(mockB.config)},alias,attendanceClock={java.time.Instant.parse("2026-10-09T00:00:00Z")},recoveryStorage=storage).also{owners+=it}}
                withTimeout(15000){ownerB.state.first{it==AccountState.StudentApproved};ownerB.recovery.first{it==RecoveryStatus.Unresolved}}
                withContext(Dispatchers.Main){ownerB.refreshAttendance();ownerB.awaitAttendanceIdle()}
                assertTrue(ownerB.attendance.state.value is ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceState.Fresh)
                withContext(Dispatchers.Main){assertNull(ownerB.permittedAttendanceAction());assertNull(ownerB.createAttendanceSubmission(this,ownerB.proofTicket()!!));assertEquals("3h 0m",ownerB.widgetPresentation().completed)}
                release.complete(Unit);withTimeout(15000){completed.await();settled.await()}
                assertEquals(original,RecoveryJournal(storage).load());assertEquals(0,receiptReadsB)
                assertEquals(AccountState.StudentApproved,ownerB.state.value);assertEquals(RecoveryStatus.Unresolved,ownerB.recovery.value)
                withContext(Dispatchers.Main){assertEquals("3h 0m",ownerB.widgetPresentation().completed)}
            } finally {release.complete(Unit)}
        }
    }

    @Test fun actualApprovedCoordinatorRecoversLostPrepareAndConfirmsOnlyReceipt()=runBlocking {
        fixture(lostPrepare=true){context,alias,storage,owners->
            val seen=mutableListOf<String>()
            val mock=engine(receipt={confirmedEnvelope().replace("\"object_status\":\"present\"","\"object_status\":\"not_checked\"")},seen=seen)
            val owner=withContext(Dispatchers.Main){AuthCoordinator(context,ClientConfiguration.parse("https://test.invalid","sb_publishable_"+"synthetic".repeat(4)),{MockEngine(mock.config)},alias,recoveryStorage=storage).also{owners+=it}}
            withTimeout(15000){owner.state.first{it==AccountState.StudentApproved};owner.recovery.first{it==RecoveryStatus.Confirmed}}
            assertNull(storage.bytes)
            assertTrue(seen.contains("POST /rest/v1/rpc/attendance_proof_recovery_lookup"))
            assertFalse(seen.any{it.contains("prepare")||it.contains("finalize")||it.contains("/storage/")})
        }
    }

    @Test fun physicalActivityRecreationDoesNotOwnBlockedRecovery()=runBlocking {
        fixture{context,alias,storage,owners->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val completed=CompletableDeferred<Unit>();val seen=mutableListOf<String>()
            val mock=engine(receipt={withContext(NonCancellable){entered.complete(Unit);release.await()};completed.complete(Unit);confirmedEnvelope()},seen=seen)
            val owner=withContext(Dispatchers.Main){AuthCoordinator(context,ClientConfiguration.parse("https://test.invalid","sb_publishable_"+"synthetic".repeat(4)),{MockEngine(mock.config)},alias,recoveryStorage=storage).also{owners+=it}}
            try {
                withTimeout(15000){entered.await()}
                val before=RecoveryJournal(storage).load()
                androidx.test.core.app.ActivityScenario.launch(RecoveryReviewActivity::class.java).use{scenario->
                    var original:RecoveryReviewActivity?=null
                    scenario.onActivity{original=it}
                    scenario.recreate()
                    scenario.onActivity{assertNotSame(original,it)}
                    assertEquals(before,RecoveryJournal(storage).load())
                    assertEquals(RecoveryStatus.Checking,owner.recovery.value)
                    release.complete(Unit)
                    withTimeout(15000){completed.await();owner.recovery.first{it==RecoveryStatus.Confirmed}}
                    assertNull(storage.bytes)
                    assertEquals(1,seen.count{it.contains("attendance_proof_recovery")})
                    assertFalse(seen.any{it.contains("prepare")||it.contains("finalize")||it.contains("/storage/")})
                }
            }finally{release.complete(Unit)}
        }
    }

}
