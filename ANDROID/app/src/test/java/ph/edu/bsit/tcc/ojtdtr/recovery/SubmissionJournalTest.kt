package ph.edu.bsit.tcc.ojtdtr.recovery

import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ph.edu.bsit.tcc.ojtdtr.proof.*
import ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceAction

class SubmissionJournalTest {
    private val uid="00000000-0000-4000-8000-000000000001"
    private val upload="00000000-0000-4000-8000-000000000002"
    private val session="00000000-0000-4000-8000-000000000003"
    private val now=Instant.parse("2026-10-09T00:00:00Z")
    private val path get()="$uid/$session/$upload/proof"
    private class Memory:JournalStorage {
        var bytes:ByteArray?=null;var fail=false
        override fun read()=bytes?.copyOf()
        override fun replace(bytes:ByteArray){if(fail)throw IllegalStateException();this.bytes=bytes.copyOf()}
        override fun remove(){bytes=null}
    }
    private inner class Backend(val j:RecoveryJournal):ProofBackend {
        val calls=mutableListOf<String>();var stop:String?=null;var reject=false
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        suspend fun before(stage:String,phase:RecoveryPhase){calls+=stage;assertEquals(phase,j.load()!!.phase);if(stop==stage){entered.complete(Unit);release.await()};if(reject)throw SubmissionFailure(SubmissionProblem.Denied)}
        override suspend fun prepare(requestId:String,action:AttendanceAction):String {
            before("prepare",RecoveryPhase.PrepareIntent);assertEquals(requestId,j.load()!!.request)
            return """[{"upload_id":"$upload","attendance_session_id":"$session","photo_path":"$path","expires_at":"2026-10-09T01:00:00Z"}]"""
        }
        override suspend fun upload(bucket:String,path:String,jpeg:ByteArray){before("upload",RecoveryPhase.UploadIntent)}
        override suspend fun finalize(uploadId:String,fix:Fix):String {
            before("finalize",RecoveryPhase.FinalizeIntent)
            return """{"id":"$upload","student_uid":"$uid","upload_id":"$upload","attendance_session_id":"$session","photo_path":"$path","action_type":"time_in","latitude":14.5,"longitude":121.0,"accuracy":8,"official_punch_at":"2026-10-09T00:00:00Z","attached_at":"2026-10-09T00:00:01Z"}"""
        }
        override suspend fun receipt(uploadId:String):String{calls+="receipt";return "[]"}
    }
    private fun workflow(scope:CoroutineScope,backend:ProofBackend,j:RecoveryJournal,current:()->Boolean={true}) =
        AttendanceSubmission(scope,backend,uid,AttendanceAction.TimeIn,current,{AttendanceAction.TimeIn},{true},{},{1},{now},1000,j)
    private fun proof()=ConfirmedProof(byteArrayOf(-1,-40,-1),Fix(14.5,121.0,8f,1,true))
    @Test fun everyNetworkStageHasDurableIntentFirstAndCompletionClears()=runBlocking {
        val s=Memory();val j=RecoveryJournal(s);val b=Backend(j);val w=workflow(this,b,j);val p=proof()
        try{w.submit(p);w.awaitOperations();assertEquals(listOf("prepare","upload","finalize"),b.calls);assertEquals(SubmissionState.Completed,w.state.value);assertNull(j.load());assertTrue(p.bytes.all{it==0.toByte()})}finally{w.close()}
    }
    @Test fun storageFailureDispatchesNothing()=runBlocking {
        val s=Memory();s.fail=true;val j=RecoveryJournal(s);val b=Backend(j);val w=workflow(this,b,j)
        try{w.submit(proof());w.awaitOperations();assertTrue(b.calls.isEmpty());assertEquals(SubmissionState.Failed,w.state.value)}finally{w.close()}
    }
    @Test fun prepareInterruptionPreservesRequestAndBlocksReplacement()=runBlocking {interrupted("prepare",RecoveryPhase.PrepareIntent)}
    @Test fun uploadInterruptionPreservesServerIdentities()=runBlocking {interrupted("upload",RecoveryPhase.UploadIntent)}
    @Test fun finalizeInterruptionPreservesUnknownOutcome()=runBlocking {interrupted("finalize",RecoveryPhase.FinalizeIntent)}
    private suspend fun CoroutineScope.interrupted(stage:String,phase:RecoveryPhase) {
        val s=Memory();val j=RecoveryJournal(s);val b=Backend(j);b.stop=stage;val w=workflow(this,b,j)
        w.submit(proof());b.entered.await();val before=j.load()!!;w.close();w.awaitOperations()
        assertEquals(phase,RecoveryJournal(s).load()!!.phase);assertEquals(before.request,j.load()!!.request)
        val next=Backend(j);val replacement=workflow(this,next,j)
        try{replacement.submit(proof());replacement.awaitOperations();assertTrue(next.calls.isEmpty())}finally{replacement.close()}
        assertFalse(b.calls.contains("receipt"))
    }
    @Test fun disabledProductionWorkflowLeavesNoPhantomJournal()=runBlocking {
        val j=RecoveryJournal(Memory());val w=workflow(this,DisabledProofBackend,j)
        try{w.submit(proof());w.awaitOperations();assertEquals(SubmissionState.Disabled,w.state.value);assertNull(j.load())}finally{w.close()}
    }
    @Test fun duplicateConfirmationCannotIssueSecondNetworkAttempt()=runBlocking {
        val j=RecoveryJournal(Memory());val b=Backend(j);val w=workflow(this,b,j)
        try{w.submit(proof());w.submit(proof());w.awaitOperations();assertEquals(3,b.calls.size)}finally{w.close()}
    }
    @Test fun rejectionRetainsReservationEvidenceInsteadOfInventingResolution()=runBlocking {
        val j=RecoveryJournal(Memory());val b=Backend(j);b.reject=true;val w=workflow(this,b,j)
        try{w.submit(proof());w.awaitOperations();assertEquals(SubmissionState.Failed,w.state.value);assertEquals(RecoveryPhase.PrepareIntent,j.load()!!.phase)}finally{w.close()}
    }
    @Test fun authorizationWithdrawalCannotDispatchLaterStage()=runBlocking {
        val j=RecoveryJournal(Memory());val b=Backend(j);b.stop="prepare";var current=true;val w=workflow(this,b,j){current}
        try{w.submit(proof());b.entered.await();current=false;b.release.complete(Unit);w.awaitOperations();assertEquals(listOf("prepare"),b.calls);assertEquals(RecoveryPhase.PrepareIntent,j.load()!!.phase)}finally{w.close()}
    }
    @Test fun concurrentAuthoritativeRecoveryCannotOverwriteCompletedSubmission()=runBlocking {
        val j=RecoveryJournal(Memory());val b=Backend(j);b.stop="finalize";val w=workflow(this,b,j)
        try {
            w.submit(proof());b.entered.await()
            val recovery=AttendanceRecovery(this,j,Dispatchers.Unconfined)
            recovery.bind(uid,{true},RecoveryReader {"""[{"id":"$upload","student_uid":"$uid","upload_id":"$upload","attendance_session_id":"$session","action_type":"time_in","official_punch_at":"2026-10-09T00:00:00Z","attached_at":"2026-10-09T00:00:01Z"}]"""})
            recovery.awaitIdle();assertEquals(RecoveryStatus.Confirmed,recovery.state.value);assertNull(j.load())
            b.release.complete(Unit);w.awaitOperations()
            assertEquals(SubmissionState.Completed,w.state.value)
            assertEquals(3,b.calls.size)
        } finally {b.release.complete(Unit);w.close()}
    }

    @Test fun checkedFilesystemFaultPreventsPrepareAndBlocksUntilInspection()=runBlocking {
        for(stage in listOf("file","rename","directory")) {
            val root=java.nio.file.Files.createTempDirectory("submission-sync-fault-").toFile()
            val directory=java.io.File(root,"journal").apply{mkdirs()};val target=java.io.File(directory,"attempt.enc")
            val operations=object:JournalFileOperations {
                var directories=0
                override fun syncFile(channel:java.nio.channels.FileChannel){if(stage=="file")throw java.io.IOException();channel.force(true)}
                override fun rename(source:java.io.File,target:java.io.File){if(stage=="rename")throw java.io.IOException();java.nio.file.Files.move(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)}
                override fun syncDirectory(directory:java.io.File){directories++;if(stage=="directory"&&directories==2)throw java.io.IOException()}
            }
            val storage=object:JournalStorage {
                override fun read()=if(target.exists())target.readBytes()else null
                override fun replace(bytes:ByteArray)=replaceRecoveryCiphertext(target,bytes,operations)
                override fun remove(){target.delete()}
            }
            val j=RecoveryJournal(storage);val backend=Backend(j);var blocked=false
            val w=AttendanceSubmission(this,backend,uid,AttendanceAction.TimeIn,{true},{AttendanceAction.TimeIn},{true},{},{1},{now},1000,j,{blocked=it})
            try{w.submit(proof());w.awaitOperations();assertTrue(backend.calls.isEmpty());assertTrue(blocked)
                assertEquals(SubmissionState.Failed,w.state.value)
                if(stage=="directory")assertEquals(RecoveryPhase.PrepareIntent,RecoveryJournal(storage).load()!!.phase)
            }finally{w.close();root.deleteRecursively()}
        }
    }

}
