package ph.edu.bsit.tcc.ojtdtr.recovery

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryRpcTest {
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val request = "00000000-0000-4000-8000-000000000003"
    private val upload = "00000000-0000-4000-8000-000000000004"
    private val session = "00000000-0000-4000-8000-000000000005"
    private fun intent() = RecoveryRecord(owner=a,request=request,action="time_in",phase=RecoveryPhase.PrepareIntent)
    private fun envelope(lookup:String="found", objectStatus:String="not_checked", receipt:String="missing") = """{"contract_version":1,"observed_at":"2026-10-09T00:00:00Z","student_uid":"$a","request_id":"$request","lookup_status":"$lookup","reservation":${if(lookup=="found") """{"upload_id":"$upload","attendance_session_id":"$session","action_type":"time_in","state":"${if(receipt=="confirmed") "attached" else "pending"}","expires_at":"2026-10-09T01:00:00Z","expired":false}""" else "null"},"object_status":"$objectStatus","receipt_status":"$receipt","receipt":${if(receipt=="confirmed") """{"id":"$b","student_uid":"$a","upload_id":"$upload","attendance_session_id":"$session","action_type":"time_in","official_punch_at":"2026-10-09T00:00:00Z","attached_at":"2026-10-09T00:00:01Z"}""" else "null"}}"""
    private fun reject(raw:String, expected:RecoveryRecord=intent(), checked:Boolean=false) {
        try { recoveryEnvelope(raw,expected,checked); fail("Invalid envelope accepted") } catch(_:Exception) {}
    }
    @Test fun lookupRecoversOriginalIdentityWithoutSuccess() {val r=recoveryEnvelope(envelope(),intent(),false);assertEquals(upload,r.reservation!!.upload);assertFalse(r.confirmed)}
    @Test fun presentObjectIsNotAttendance() {assertFalse(recoveryEnvelope(envelope(objectStatus="present"),intent(),true).confirmed)}
    @Test fun missingObjectKeepsUncertainty() {assertFalse(recoveryEnvelope(envelope(objectStatus="missing"),intent(),true).confirmed)}
    @Test fun invalidObjectKeepsUncertainty() {assertFalse(recoveryEnvelope(envelope(objectStatus="invalid"),intent(),true).confirmed)}
    @Test fun exactReceiptConfirmsEvenWithoutObject() {assertTrue(recoveryEnvelope(envelope(objectStatus="missing",receipt="confirmed"),intent(),true).confirmed)}
    @Test fun notFoundHasNoIdentity() {assertNull(recoveryEnvelope(envelope("not_found",receipt="unknown"),intent(),false).reservation)}
    @Test fun unknownHasNoIdentity() {assertNull(recoveryEnvelope(envelope("unknown","unknown","unknown"),intent(),true).reservation)}
    @Test fun missingFieldsRejected() {val row=Json.parseToJsonElement(envelope()).jsonObject;row.keys.forEach{key->reject(JsonObject(row-key).toString())}}
    @Test fun extraSensitiveFieldRejected() {reject(envelope().dropLast(1)+",\"photo_path\":\"forbidden\"}")}
    @Test fun duplicateKeysRejected() {reject(envelope().replace("\"contract_version\":1","\"contract_version\":1,\"contract_version\":1"))}
    @Test fun unknownVersionRejected() {reject(envelope().replace("\"contract_version\":1","\"contract_version\":2"))}
    @Test fun quotedVersionRejected() {reject(envelope().replace("\"contract_version\":1","\"contract_version\":\"1\""))}
    @Test fun badUuidRejected() {reject(envelope().replace(upload,"1-1-1-1-1"))}
    @Test fun wrongOwnerRejected() {reject(envelope().replace(a,b))}
    @Test fun wrongRequestRejected() {reject(envelope().replace(request,b))}
    @Test fun wrongUploadRejected() {reject(envelope(),intent().copy(schema=2,recovered=true,upload=b,session=session))}
    @Test fun wrongSessionRejected() {reject(envelope(),intent().copy(schema=2,recovered=true,upload=upload,session=b))}
    @Test fun unknownEnumsRejected() {for(value in listOf("found","not_checked","missing","pending"))reject(envelope().replace("\"$value\"","\"future\""))}
    @Test fun invalidTimeRejected() {reject(envelope().replace("2026-10-09T01:00:00Z","infinity"))}
    @Test fun contradictoryExpiryRejected() {reject(envelope().replace("\"expired\":false","\"expired\":true"))}
    @Test fun lookupCannotClaimObjectRead() {reject(envelope(objectStatus="present"))}
    @Test fun attachedWithoutReceiptRejected() {reject(envelope().replace("\"pending\"","\"attached\""))}
    @Test fun unknownReceiptCannotConfirm() {reject(envelope(receipt="unknown"))}
    @Test fun oversizedRejected() {reject(" ".repeat(65537)+envelope())}
    @Test fun longQuotedFieldFailsWithoutStackOverflow() {reject("{\"padding\":\""+"x".repeat(30000)+"\"}")}
    @Test fun excessiveObjectNestingFailsWithoutStackOverflow() {reject("{\"x\":".repeat(5000)+"null"+"}".repeat(5000))}
    @Test fun arraysAreNeverRecoveryEnvelopes() {reject("["+envelope()+"]");reject(envelope().replace("\"receipt\":null","\"receipt\":[]"))}
    @Test fun trailingJsonIsRejected() {reject(envelope()+"{}");reject(envelope()+"true")}
    @Test fun replacementUtf8IsRejected() {reject(envelope().replace(a,a+"\uFFFD"))}
    @Test fun escapedDuplicateKeyRejected() {reject(envelope().replace("\"contract_version\":1","\"contract_version\":1,\"contract_\\u0076ersion\":1"))}
    @Test fun nullVersusAbsentAndTypesRejected() {val row=Json.parseToJsonElement(envelope()).jsonObject;for(key in row.keys){if(key!="receipt")reject(JsonObject(row+(key to JsonNull)).toString())};reject(envelope().replace("\"expired\":false","\"expired\":\"false\""))}
    @Test fun uppercaseUuidRejected() {reject(envelope().replace(a,"A0000000-0000-4000-8000-000000000001"),intent().copy(owner="a0000000-0000-4000-8000-000000000001"))}
    @Test fun failedIdentityMigrationRetainsExactOldRecord() {val store=Memory();val j=RecoveryJournal(store);val old=j.begin(a,request,"time_in"){true};store.failWrite=true;try{j.recoverIdentity(old,upload,session){true};fail()}catch(_:RecoveryStorageFailure){};assertEquals(old,j.load())}
    @Test fun knownIdentityCannotBeReplacedByEnrichment() {val j=RecoveryJournal(Memory());val old=j.recoverIdentity(j.begin(a,request,"time_in"){true},upload,session){true};try{j.recoverIdentity(old,b,session){true};fail()}catch(_:RecoveryStorageFailure){};assertEquals(old,j.load())}
    private class Memory:JournalStorage {
        var bytes:ByteArray?=null;var failWrite=false
        override fun read()=bytes?.copyOf()
        override fun replace(bytes:ByteArray){if(failWrite)throw IllegalStateException();this.bytes=bytes.copyOf()}
        override fun remove(){bytes=null}
    }
    private fun reader(reply:suspend(RecoveryRecord)->RecoveryObservation)=object:RecoveryReader {
        override suspend fun receipt(record:RecoveryRecord):String=error("Legacy read must not be used")
        override suspend fun observation(record:RecoveryRecord)=reply(record)
    }
    @Test fun schemaOneIntentEnrichesWithoutPhaseAdvance() {
        val store=Memory();store.bytes=Json.encodeToString(intent()).toByteArray();val j=RecoveryJournal(store);val old=j.load()!!
        val next=j.recoverIdentity(old,upload,session){true}
        assertEquals(2,next.schema);assertEquals(old.phase,next.phase);assertTrue(next.recovered);assertEquals(old.request,next.request)
        assertEquals(next,RecoveryJournal(store).load())
        try{j.advance(next,RecoveryPhase.Prepared,{true});fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun staleEnrichmentCannotReplaceJournal() {val j=RecoveryJournal(Memory());val old=j.begin(a,request,"time_in"){true};val next=j.recoverIdentity(old,upload,session){true};try{j.recoverIdentity(old,b,b){true};fail()}catch(_:RecoveryStorageFailure){};assertEquals(next,j.load())}
    @Test fun withdrawalPreventsEnrichment() {val j=RecoveryJournal(Memory());val old=j.begin(a,request,"time_in"){true};try{j.recoverIdentity(old,upload,session){false};fail()}catch(_:RecoveryStorageFailure){};assertEquals(old,j.load())}
    @Test fun foundRetainsJournalAndBlocksSubmission()=runBlocking {val j=RecoveryJournal(Memory());j.begin(a,request,"time_in"){true};val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);c.bind(a,{true},reader{recoveryEnvelope(envelope(),it,false)});c.awaitIdle();assertEquals(RecoveryStatus.Unresolved,c.state.value);assertTrue(c.blocksSubmission());assertEquals(RecoveryPhase.PrepareIntent,j.load()!!.phase)}
    @Test fun confirmedLookupCanClearLostPrepare()=runBlocking {val j=RecoveryJournal(Memory());j.begin(a,request,"time_in"){true};val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);c.bind(a,{true},reader{recoveryEnvelope(envelope(receipt="confirmed"),it,false)});c.awaitIdle();assertEquals(RecoveryStatus.Confirmed,c.state.value);assertNull(j.load())}
    @Test fun notFoundNeverAbandons()=runBlocking {val j=RecoveryJournal(Memory());val old=j.begin(a,request,"time_in"){true};val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);c.bind(a,{true},reader{recoveryEnvelope(envelope("not_found",receipt="unknown"),it,false)});c.awaitIdle();assertEquals(old,j.load());assertTrue(c.blocksSubmission())}
    @Test fun delayedAResponseCannotEnrichAfterBLogin()=runBlocking {
        val j=RecoveryJournal(Memory());val old=j.begin(a,request,"time_in"){true};val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val done=CompletableDeferred<Unit>()
        val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);c.bind(a,{true},reader{withContext(NonCancellable){entered.complete(Unit);release.await()};done.complete(Unit);recoveryEnvelope(envelope(receipt="confirmed"),it,false)})
        entered.await();val settled=async(start=CoroutineStart.UNDISPATCHED){c.awaitIdle()};var bCalls=0;c.bind(b,{true},reader{bCalls++;error("Forbidden")});c.awaitIdle();release.complete(Unit);done.await();settled.await()
        assertEquals(0,bCalls);assertEquals(old,j.load());assertEquals(RecoveryStatus.Unresolved,c.state.value)
        c.bind(a,{true},reader{recoveryEnvelope(envelope(receipt="confirmed"),it,false)});c.awaitIdle();assertNull(j.load())
    }
    @Test fun concurrentRecoveryCoalesces()=runBlocking {val j=RecoveryJournal(Memory());j.begin(a,request,"time_in"){true};val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0;val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);c.bind(a,{true},reader{calls++;entered.complete(Unit);release.await();recoveryEnvelope(envelope(),it,false)});entered.await();repeat(20){c.check()};release.complete(Unit);c.awaitIdle();assertEquals(1,calls)}
    @Test fun timeoutRetainsIntent()=runBlocking {val j=RecoveryJournal(Memory());val old=j.begin(a,request,"time_in"){true};val c=AttendanceRecovery(this,j,Dispatchers.Unconfined,10);c.bind(a,{true},reader{awaitCancellation()});c.awaitIdle();assertEquals(old,j.load());assertEquals(RecoveryStatus.Unresolved,c.state.value)}
}
