package ph.edu.bsit.tcc.ojtdtr.recovery

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ph.edu.bsit.tcc.ojtdtr.proof.*
import ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceAction
import java.time.Instant

class RecoveryTest {
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val req = "00000000-0000-4000-8000-000000000003"
    private val upload = "00000000-0000-4000-8000-000000000004"
    private val session = "00000000-0000-4000-8000-000000000005"
    private class Memory : JournalStorage {
        var bytes: ByteArray? = null
        var failRead = false; var failWrite = false; var failRemove = false
        override fun read() = if (failRead) throw IllegalStateException() else bytes?.copyOf()
        override fun replace(bytes: ByteArray) { if (failWrite) throw IllegalStateException(); this.bytes = bytes.copyOf() }
        override fun remove() { if (failRemove) throw IllegalStateException(); bytes = null }
    }
    private fun prepared(j: RecoveryJournal): RecoveryRecord {
        val r = j.begin(a,req,"time_in") {true}
        return j.advance(r,RecoveryPhase.Prepared,{true},upload,session)
    }
    private fun raw(r: RecoveryRecord) = """[{"id":"$req","student_uid":"${r.owner}","upload_id":"${r.upload}","attendance_session_id":"${r.session}","action_type":"${r.action}","official_punch_at":"2026-10-09T00:00:00Z","attached_at":"2026-10-09T00:00:01Z"}]"""
    private fun rejected(block: () -> Unit) { try {block();fail("Must fail closed")} catch(_:RecoveryStorageFailure){} }
    @Test fun writeReadRoundTrip() {val s=Memory();val j=RecoveryJournal(s);val r=prepared(j);assertEquals(r,RecoveryJournal(s).load())}
    @Test fun failedAtomicReplacementPreservesPrevious() {val s=Memory();val j=RecoveryJournal(s);val r=prepared(j);s.failWrite=true;rejected{j.advance(r,RecoveryPhase.UploadIntent,{true})};s.failWrite=false;assertEquals(r,j.load())}
    @Test fun corruptJournalFailsClosedAndRemains() {val s=Memory();s.bytes="broken".toByteArray();rejected{RecoveryJournal(s).load()};assertNotNull(s.bytes)}
    @Test fun unsupportedVersionRemainsBlocked() {val s=Memory();val j=RecoveryJournal(s);prepared(j);s.bytes=String(s.bytes!!).replace("\"schema\":2","\"schema\":3").toByteArray();rejected{j.load()};assertNotNull(s.bytes)}
    @Test fun encryptionFailureCannotDispatchIntent() {val s=Memory();s.failWrite=true;rejected{RecoveryJournal(s).begin(a,req,"time_in"){true}};assertNull(s.bytes)}
    @Test fun unavailableStorageFailsClosed() {val s=Memory();s.failRead=true;rejected{RecoveryJournal(s).begin(a,req,"time_in"){true}}}
    @Test fun duplicateWritersCannotOverwrite() {val j=RecoveryJournal(Memory());val r=j.begin(a,req,"time_in"){true};rejected{j.begin(b,upload,"time_out"){true}};assertEquals(r,j.load())}
    @Test fun staleRevisionCannotOverwrite() {val j=RecoveryJournal(Memory());val r=prepared(j);val newer=j.advance(r,RecoveryPhase.UploadIntent,{true});rejected{j.advance(r,RecoveryPhase.UploadIntent,{true})};assertEquals(newer,j.load())}
    @Test fun withdrawnWriterCannotAdvance() {val j=RecoveryJournal(Memory());val r=prepared(j);rejected{j.advance(r,RecoveryPhase.UploadIntent,{false})};assertEquals(r,j.load())}
    @Test fun ownerCannotChangeOnAdvance() {val j=RecoveryJournal(Memory());val r=prepared(j);rejected{j.advance(r.copy(owner=b),RecoveryPhase.UploadIntent,{true})};assertEquals(a,j.load()!!.owner)}
    @Test fun unknownFieldsCannotSmuggleCoordinates() {val s=Memory();val j=RecoveryJournal(s);prepared(j);s.bytes=(String(s.bytes!!).dropLast(1)+",\"latitude\":14.5}").toByteArray();rejected{j.load()}}
    @Test fun confirmedCleanupUsesExactCas() {val j=RecoveryJournal(Memory());val r=prepared(j);val c=j.advance(r,RecoveryPhase.Confirmed,{true});rejected{j.clearConfirmed(r){true}};j.clearConfirmed(c){true};assertNull(j.load())}
    @Test fun cleanupFailureRetainsConfirmedEvidence() {val s=Memory();val j=RecoveryJournal(s);val c=j.advance(prepared(j),RecoveryPhase.Confirmed,{true});s.failRemove=true;rejected{j.clearConfirmed(c){true}};assertEquals(c,j.load())}
    @Test fun schemaContainsNoProofOrCredentials() {val s=Memory();prepared(RecoveryJournal(s));val json=String(s.bytes!!);for(word in listOf("token","latitude","longitude","accuracy","photo_path","jpeg","lease","client"))assertFalse(json.contains(word))}
    @Test fun serverIdentifiersCannotBeReplaced() {val j=RecoveryJournal(Memory());val r=prepared(j);try{j.advance(r,RecoveryPhase.UploadIntent,{true},b,session);fail()}catch(_:IllegalArgumentException){};assertEquals(r,j.load())}

    private suspend fun checkRecord(r:RecoveryRecord?, reply:(RecoveryRecord)->String = ::raw):Pair<RecoveryStatus,Int> = coroutineScope {
        val s=Memory(); if(r!=null)s.bytes=kotlinx.serialization.json.Json.encodeToString(r).toByteArray()
        val j=RecoveryJournal(s);var calls=0
        val c=AttendanceRecovery(this,j,Dispatchers.Unconfined,20)
        c.bind(a,{true},RecoveryReader{calls++;reply(it)});c.awaitIdle()
        c.state.value to calls
    }
    @Test fun restartBeforePrepareHasNoNetwork()=runBlocking {assertEquals(RecoveryStatus.None to 0,checkRecord(null))}
    @Test fun restartDuringPrepareRetainsUncertainty()=runBlocking {val j=RecoveryJournal(Memory());assertEquals(RecoveryStatus.Unresolved to 0,checkRecord(j.begin(a,req,"time_in"){true}))}
    @Test fun lostPrepareResponseNeverLooksUpByMutation()=runBlocking {val j=RecoveryJournal(Memory());val r=j.begin(a,req,"time_in"){true};assertEquals(RecoveryStatus.Unresolved to 0,checkRecord(r))}
    @Test fun restartPreparedRequiresReceiptEvidence()=runBlocking {assertEquals(RecoveryStatus.Unresolved to 1,checkRecord(prepared(RecoveryJournal(Memory()))){"[]"})}
    @Test fun restartDuringUploadDoesNotRetry()=runBlocking {val j=RecoveryJournal(Memory());val r=j.advance(prepared(j),RecoveryPhase.UploadIntent,{true});assertEquals(RecoveryStatus.Unresolved to 1,checkRecord(r){"[]"})}
    @Test fun lostUploadAcknowledgmentRemainsUnknown()=runBlocking {val j=RecoveryJournal(Memory());val r=j.advance(prepared(j),RecoveryPhase.UploadIntent,{true});assertEquals(RecoveryStatus.Unresolved,checkRecord(r){"[]"}.first)}
    @Test fun restartAfterUploadDoesNotFinalize()=runBlocking {val j=RecoveryJournal(Memory());val r=j.advance(j.advance(prepared(j),RecoveryPhase.UploadIntent,{true}),RecoveryPhase.Uploaded,{true});assertEquals(RecoveryStatus.Unresolved to 1,checkRecord(r){"[]"})}
    @Test fun restartDuringFinalizeCanConfirmReceipt()=runBlocking {val j=RecoveryJournal(Memory());var r=prepared(j);r=j.advance(r,RecoveryPhase.UploadIntent,{true});r=j.advance(r,RecoveryPhase.Uploaded,{true});r=j.advance(r,RecoveryPhase.FinalizeIntent,{true});assertEquals(RecoveryStatus.Confirmed to 1,checkRecord(r))}
    @Test fun lostFinalizeResponseCanConfirmReceipt()=runBlocking {assertEquals(RecoveryStatus.Confirmed,checkRecord(prepared(RecoveryJournal(Memory()))).first)}
    @Test fun receiptMissingIsNotFailureOrPermission()=runBlocking {assertEquals(RecoveryStatus.Unresolved,checkRecord(prepared(RecoveryJournal(Memory()))){"[]"}.first)}
    @Test fun malformedReceiptCannotConfirm()=runBlocking {assertEquals(RecoveryStatus.Unavailable,checkRecord(prepared(RecoveryJournal(Memory()))){"[null]"}.first)}
    @Test fun otherOwnersReceiptCannotConfirm()=runBlocking {assertEquals(RecoveryStatus.Unavailable,checkRecord(prepared(RecoveryJournal(Memory()))){raw(it).replace(a,b)}.first)}
    @Test fun actionMismatchCannotConfirm()=runBlocking {assertEquals(RecoveryStatus.Unavailable,checkRecord(prepared(RecoveryJournal(Memory()))){raw(it).replace("time_in","time_out")}.first)}
    @Test fun uploadMismatchCannotConfirm()=runBlocking {assertEquals(RecoveryStatus.Unavailable,checkRecord(prepared(RecoveryJournal(Memory()))){raw(it).replace(upload,b)}.first)}
    @Test fun sessionMismatchCannotConfirm()=runBlocking {assertEquals(RecoveryStatus.Unavailable,checkRecord(prepared(RecoveryJournal(Memory()))){raw(it).replace(session,b)}.first)}
    @Test fun badServerTimestampCannotConfirm()=runBlocking {assertEquals(RecoveryStatus.Unavailable,checkRecord(prepared(RecoveryJournal(Memory()))){raw(it).replace("00:00:01","23:59:59").replace("2026-10-09T23","2026-10-08T23")}.first)}
    @Test fun networkErrorRetainsJournal()=runBlocking {assertEquals(RecoveryStatus.Unavailable,checkRecord(prepared(RecoveryJournal(Memory()))){throw IllegalStateException("synthetic")}.first)}
    @Test fun networkTimeoutExitsChecking()=runBlocking {coroutineScope{val j=RecoveryJournal(Memory());prepared(j);val c=AttendanceRecovery(this,j,Dispatchers.Unconfined,10);c.bind(a,{true},RecoveryReader{awaitCancellation()});c.awaitIdle();assertEquals(RecoveryStatus.Unresolved,c.state.value);assertNotNull(j.load())}}
    @Test fun untrustedAccountsNeverReadJournalOrNetwork()=runBlocking {for(account in listOf("expired","pending","rejected","admin","revoked","signed_out")){coroutineScope{val s=Memory();s.failRead=true;var reads=0;val c=AttendanceRecovery(this,RecoveryJournal(s),Dispatchers.Unconfined);c.bind(a,{false},RecoveryReader{reads++;"[]"});c.awaitIdle();assertEquals(0,reads);assertEquals(RecoveryStatus.Hidden,c.state.value)}}}
    @Test fun logoutWithdrawsPublication()=runBlocking {coroutineScope{val j=RecoveryJournal(Memory());val r=prepared(j);val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>();c.bind(a,{true},RecoveryReader{withContext(NonCancellable){entered.complete(Unit);release.await()};finished.complete(Unit);raw(it)});entered.await();val settled=async(start=CoroutineStart.UNDISPATCHED){c.awaitIdle()};c.withdraw();release.complete(Unit);finished.await();settled.await();assertEquals(RecoveryStatus.Hidden,c.state.value);assertEquals(r,j.load())}}
    @Test fun replacementRejectsDelayedAAndReturningACanRecover()=runBlocking {coroutineScope{
        val j=RecoveryJournal(Memory());val r=prepared(j);val c=AttendanceRecovery(this,j,Dispatchers.Unconfined)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>();var bCalls=0
        c.bind(a,{true},RecoveryReader{withContext(NonCancellable){entered.complete(Unit);release.await()};finished.complete(Unit);raw(it)})
        entered.await();val settled=async(start=CoroutineStart.UNDISPATCHED){c.awaitIdle()};c.bind(b,{true},RecoveryReader{bCalls++;raw(it)});c.awaitIdle();release.complete(Unit);finished.await();settled.await()
        assertEquals(0,bCalls);assertEquals(RecoveryStatus.Unresolved,c.state.value);assertEquals(r,j.load());assertTrue(c.blocksSubmission())
        c.bind(a,{true},RecoveryReader{raw(it)});c.awaitIdle();assertEquals(RecoveryStatus.Confirmed,c.state.value);assertNull(j.load())
    }}
    @Test fun revocationDuringReadPreservesEvidence()=runBlocking {coroutineScope{val j=RecoveryJournal(Memory());val r=prepared(j);var current=true;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);c.bind(a,{current},RecoveryReader{entered.complete(Unit);release.await();raw(it)});entered.await();current=false;c.withdraw();release.complete(Unit);assertEquals(r,j.load());assertEquals(RecoveryStatus.Hidden,c.state.value)}}
    @Test fun concurrentTriggersCoalesce()=runBlocking {coroutineScope{val j=RecoveryJournal(Memory());prepared(j);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var reads=0;val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);c.bind(a,{true},RecoveryReader{reads++;entered.complete(Unit);release.await();"[]"});entered.await();repeat(20){c.check()};release.complete(Unit);c.awaitIdle();assertEquals(1,reads)}}
    @Test fun activityObserverReplacementDoesNotOwnRecovery()=runBlocking {coroutineScope{val j=RecoveryJournal(Memory());prepared(j);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val c=AttendanceRecovery(this,j,Dispatchers.Unconfined);c.bind(a,{true},RecoveryReader{entered.complete(Unit);release.await();"[]"});entered.await();val first=c.state;val recreated=c.state;assertSame(first,recreated);release.complete(Unit);c.awaitIdle();assertEquals(RecoveryStatus.Unresolved,recreated.value)}}
    @Test fun repeatedProcessModelsKeepSameRequestWithoutMutation()=runBlocking {val r=prepared(RecoveryJournal(Memory()));repeat(3){assertEquals(RecoveryStatus.Unresolved to 1,checkRecord(r){"[]"})}}
    @Test fun localConfirmedMetadataStillRequiresServerRead()=runBlocking {val j=RecoveryJournal(Memory());val r=j.advance(prepared(j),RecoveryPhase.Confirmed,{true});assertEquals(RecoveryStatus.Unresolved,checkRecord(r){"[]"}.first)}
    @Test fun unavailableStorageBlocksNewSubmission()=runBlocking {coroutineScope{val s=Memory();s.failRead=true;val c=AttendanceRecovery(this,RecoveryJournal(s),Dispatchers.Unconfined);c.bind(a,{true},RecoveryReader{fail();"[]"});c.awaitIdle();assertEquals(RecoveryStatus.Unavailable,c.state.value);assertTrue(c.blocksSubmission())}}
    private class HostFiles(private val fail:String?=null):JournalFileOperations {
        val sequence=mutableListOf<String>()
        var calls=0
        override fun syncFile(channel:java.nio.channels.FileChannel){sequence+="file";if(fail=="file")throw java.io.IOException();channel.force(true)}
        override fun rename(source:java.io.File,target:java.io.File){sequence+="rename";if(fail=="rename")throw java.io.IOException();java.nio.file.Files.move(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)}
        override fun syncDirectory(directory:java.io.File){calls++;sequence+=if(calls==1)"parent"else"directory";if(fail=="directory"&&calls==2)throw java.io.IOException()}
    }
    private fun diskCase(fail:String?=null, test:(java.io.File,HostFiles)->Unit){
        val root=java.nio.file.Files.createTempDirectory("recovery-fault-").toFile()
        try{val directory=java.io.File(root,"journal").apply{mkdirs()};test(java.io.File(directory,"attempt.enc"),HostFiles(fail))}
        finally{root.deleteRecursively()}
    }
    @Test fun checkedWriterOrdersSyncRenameAndDirectoryCommit(){diskCase{target,ops->val bytes=ByteArray(30){1};replaceRecoveryCiphertext(target,bytes,ops);assertArrayEquals(bytes,target.readBytes());assertEquals(listOf("parent","file","rename","directory"),ops.sequence)}}
    @Test fun checkedFileSyncFailurePreservesPreviousRecord(){diskCase("file"){target,ops->val old=ByteArray(30){1};target.writeBytes(old);rejected{replaceRecoveryCiphertext(target,ByteArray(30){2},ops)};assertArrayEquals(old,target.readBytes());assertFalse(java.io.File(target.path+".new").exists());assertFalse(ops.sequence.contains("rename"))}}
    @Test fun checkedRenameFailurePreservesPreviousRecord(){diskCase("rename"){target,ops->val old=ByteArray(30){1};target.writeBytes(old);rejected{replaceRecoveryCiphertext(target,ByteArray(30){2},ops)};assertArrayEquals(old,target.readBytes());assertFalse(java.io.File(target.path+".new").exists())}}
    @Test fun checkedDirectorySyncFailureCannotClaimSuccess(){diskCase("directory"){target,ops->val newer=ByteArray(30){2};target.writeBytes(ByteArray(30){1});rejected{replaceRecoveryCiphertext(target,newer,ops)};assertArrayEquals(newer,target.readBytes())}}
    @Test fun symlinkPendingLeafCannotTruncateOtherFiles(){diskCase{target,ops->val sentinel=java.io.File(target.parentFile,"sentinel").apply{writeText("keep")};java.nio.file.Files.createSymbolicLink(java.nio.file.Paths.get(target.path+".new"),sentinel.toPath());rejected{replaceRecoveryCiphertext(target,ByteArray(30){2},ops)};assertEquals("keep",sentinel.readText());assertFalse(target.exists())}}
    @Test fun actualConcurrentBeginOnlyOneWriterWins(){
        val j=RecoveryJournal(Memory());val barrier=java.util.concurrent.CyclicBarrier(2);val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try{val results=listOf(a,b).map{uid->pool.submit<Boolean>{barrier.await();try{j.begin(uid,req,"time_in"){true};true}catch(_:RecoveryStorageFailure){false}}}
            assertEquals(1,results.count{it.get(5,java.util.concurrent.TimeUnit.SECONDS)});assertTrue(j.load()!!.owner in listOf(a,b))
        }finally{pool.shutdownNow()}
    }
    @Test fun actualConcurrentRevisionAdvanceOnlyOneWriterWins(){
        val j=RecoveryJournal(Memory());val original=prepared(j);val barrier=java.util.concurrent.CyclicBarrier(2);val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try{val results=(1..2).map{pool.submit<Boolean>{barrier.await();try{j.advance(original,RecoveryPhase.UploadIntent,{true});true}catch(_:RecoveryStorageFailure){false}}}
            assertEquals(1,results.count{it.get(5,java.util.concurrent.TimeUnit.SECONDS)});assertEquals(original.revision+1,j.load()!!.revision)
        }finally{pool.shutdownNow()}
    }
    @Test fun confirmationCanJoinNewerRevisionOfSameAttempt(){val j=RecoveryJournal(Memory());val original=prepared(j);j.advance(original,RecoveryPhase.UploadIntent,{true});j.confirmAndClear(original){true};assertNull(j.load())}
    @Test fun concurrentAuthoritativeConfirmersAreIdempotent(){
        val j=RecoveryJournal(Memory());val original=prepared(j);val barrier=java.util.concurrent.CyclicBarrier(2);val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try{val results=(1..2).map{pool.submit<Boolean>{barrier.await();j.confirmAndClear(original){true};true}}
            assertTrue(results.all{it.get(5,java.util.concurrent.TimeUnit.SECONDS)});assertNull(j.load())
        }finally{pool.shutdownNow()}
    }
    @Test fun oldReceiptCannotDeleteReplacementAttempt(){val j=RecoveryJournal(Memory());val original=prepared(j);j.confirmAndClear(original){true};val replacement=j.begin(b,upload,"time_out"){true};rejected{j.confirmAndClear(original){true}};assertEquals(replacement,j.load())}
    @Test fun withdrawnConfirmationCannotEraseEvidence(){val j=RecoveryJournal(Memory());val original=prepared(j);rejected{j.confirmAndClear(original){false}};assertEquals(original,j.load())}

}
