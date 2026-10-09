package ph.edu.bsit.tcc.ojtdtr.proof

import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ph.edu.bsit.tcc.ojtdtr.attendance.*
import java.math.BigDecimal

class AttendanceSubmissionTest {
    private val uid = "00000000-0000-4000-8000-000000000001"
    private val upload = "00000000-0000-4000-8000-000000000002"
    private val session = "00000000-0000-4000-8000-000000000003"
    private val now = Instant.parse("2026-10-09T04:00:00Z")
    private val fix = Fix(14.0, 121.0, 8f, 1000, true)
    private fun prepared() = """[{"upload_id":"$upload","attendance_session_id":"$session","photo_path":"$uid/$session/$upload/proof","expires_at":"2026-10-09T05:00:00Z"}]"""
    private fun receipt(action: AttendanceAction) = """{"id":"00000000-0000-4000-8000-000000000004","upload_id":"$upload","attendance_session_id":"$session","student_uid":"$uid","photo_path":"$uid/$session/$upload/proof","action_type":"${ProofContract.wireAction(action)}","latitude":14,"longitude":121,"accuracy":8,"official_punch_at":"2026-10-09T04:00:01Z","attached_at":"2026-10-09T04:00:02Z"}"""
    private inner class Backend(val identity: String = uid, val target: String = session) : ProofBackend {
        val calls = mutableListOf<String>(); val ids = mutableListOf<String>()
        var before: suspend (String) -> Unit = {}
        var response: String? = null
        override suspend fun prepare(requestId: String, action: AttendanceAction): String {
            calls += "prepare"; ids += requestId; before("prepare"); return prepared().replace(uid, identity).replace(session, target)
        }
        override suspend fun upload(bucket: String, path: String, jpeg: ByteArray) {
            calls += "upload"; assertEquals(ProofContract.BUCKET,bucket); assertEquals("$identity/$target/$upload/proof",path)
            assertEquals(3,jpeg.size); before("upload")
        }
        override suspend fun finalize(uploadId: String, fix: Fix): String {
            calls += "finalize"; assertEquals(upload,uploadId); before("finalize"); return response ?: receipt(AttendanceAction.TimeIn).replace(uid, identity).replace(session, target)
        }
        override suspend fun receipt(uploadId: String): String { calls += "receipt"; assertEquals(upload,uploadId); before("receipt"); return "[${response ?: receipt(AttendanceAction.TimeIn).replace(uid, identity).replace(session, target)}]" }
    }
    private class Owner(val role: String = "student", val status: String = "approved", val signedIn: Boolean = true) {
        private var lease = true
        var approved: Boolean
            get() = lease && signedIn && role == "student" && status == "approved"
            set(value) { lease = value }
        var refreshes = 0
        var summary: AttendanceSummary? = null
        private var action = AttendanceAction.TimeIn
        var next: AttendanceAction
            get() = summary?.nextAction ?: action
            set(value) { action = value }
        var elapsed = 1000L
    }
    // Server-summary fixtures; these tests exercise client handling, not live SQL enforcement.
    private fun attendance(starts: Int = 0, open: Boolean = false, overnight: Boolean = false): AttendanceSummary {
        val day = now.atZone(Manila).toLocalDate()
        val rows = if (overnight) emptyList() else (1..starts).map { ordinal ->
            val id = "00000000-0000-4000-8000-00000000000${ordinal + 4}"
            val timeIn = now.minusSeconds((5 - ordinal * 2) * 3600L)
            AttendanceSession(id, timeIn, if (open && ordinal == starts) null else timeIn.plusSeconds(3600), ordinal)
        }
        val opened = if (overnight) now.atZone(Manila).toLocalDate().atStartOfDay(Manila).toInstant().minusSeconds(60)
            else rows.lastOrNull()?.takeIf { open }?.timeIn
        val completed = rows.count { it.timeOut != null }
        return AttendanceSummary(day, rows, if (opened != null) if (overnight) session else rows.last().id else null,
            opened, if (opened != null) if (overnight) 2 else starts else null,
            if (opened != null) AttendanceAction.TimeOut else if (starts < 2) AttendanceAction.TimeIn else AttendanceAction.None,
            BigDecimal(completed * 3600), BigDecimal(completed * 3600), completed.toLong(),
            if (rows.isEmpty() && !overnight) 0 else 1, 486)
    }
    private fun CoroutineScope.coordinator(b: ProofBackend, o: Owner, action: AttendanceAction = o.next, deadline: Long = 1000, identity: String = uid) =
        AttendanceSubmission(this,b,identity,action,{o.approved},{o.next},{o.approved},{o.refreshes++},{o.elapsed},{now},deadline)
    private fun proof(f: Fix = fix) = ConfirmedProof(byteArrayOf(1,2,3),f)
    @Test fun timeInSuccessRefreshesOnlyAfterReceipt() = runBlocking {
        val b=Backend(); val o=Owner().apply { summary = attendance() }; val s=coordinator(b,o); val p=proof()
        s.submit(p); s.awaitOperations(); assertEquals(SubmissionState.Completed,s.state.value)
        assertEquals(listOf("prepare","upload","finalize"),b.calls); assertEquals(1,o.refreshes); assertTrue(p.bytes.all{it==0.toByte()}); s.close()
    }
    @Test fun timeOutClosesCurrentOpenSessionFixture() = runBlocking {
        val o = Owner().apply { summary = attendance(starts = 1, open = true) }
        val target = o.summary!!.openSessionId!!
        val b = Backend(target = target).apply { response = receipt(AttendanceAction.TimeOut).replace(session, target) }
        val s = coordinator(b, o)
        try { assertFalse(o.summary!!.overnight); s.submit(proof()); s.awaitOperations()
            assertEquals(SubmissionState.Completed, s.state.value); assertEquals(1, o.refreshes)
        } finally { s.close() }
    }
    @Test fun overnightTimeOutUsesPreviousManilaDayOpenSession() = runBlocking {
        val o = Owner().apply { summary = attendance(overnight = true) }
        assertTrue(o.summary!!.overnight); assertTrue(o.summary!!.sessions.isEmpty())
        assertEquals(o.summary!!.day.minusDays(1), o.summary!!.openTimeIn!!.atZone(Manila).toLocalDate())
        val b = Backend().apply { response = receipt(AttendanceAction.TimeOut) }
        val s = coordinator(b, o)
        try { s.submit(proof()); s.awaitOperations(); assertEquals(SubmissionState.Completed, s.state.value)
            assertEquals(listOf("prepare", "upload", "finalize"), b.calls); assertEquals(1, o.refreshes)
        } finally { s.close() }
    }
    @Test fun secondPermittedTimeInHasOneCompletedSessionAndDistinctReservation() = runBlocking {
        val o = Owner().apply { summary = attendance(starts = 1) }
        assertEquals(1, o.summary!!.completedSessions); assertEquals(BigDecimal(3600), o.summary!!.todayCompletedSeconds)
        assertEquals(AttendanceAction.TimeIn, o.summary!!.nextAction)
        val target = "00000000-0000-4000-8000-000000000008"
        val b = Backend(target = target); val s = coordinator(b, o)
        try { s.submit(proof()); s.awaitOperations(); assertEquals(SubmissionState.Completed, s.state.value)
            assertEquals(1, o.refreshes); assertEquals(1, b.ids.size)
        } finally { s.close() }
    }
    @Test fun serverDeniesThirdStartDespiteStaleClientOffer() = runBlocking {
        val o = Owner().apply { summary = attendance() } // Another device has since used both starts.
        val authoritative = attendance(starts = 2)
        assertEquals(2, authoritative.sessions.size); assertEquals(AttendanceAction.None, authoritative.nextAction)
        val b = Backend().apply { before = { if (it == "prepare") throw SubmissionFailure(SubmissionProblem.Denied) } }
        val s = coordinator(b, o)
        try { s.submit(proof()); s.awaitOperations(); assertEquals(listOf("prepare"), b.calls)
            assertEquals(SubmissionState.Failed, s.state.value); assertEquals(SubmissionProblem.Denied, s.problem.value)
            assertEquals(0, o.refreshes)
        } finally { s.close() }
    }
    @Test fun noneActionBlocksThirdStartWithoutBackendCall() = runBlocking {
        val b=Backend(); val o=Owner().apply{summary=attendance(starts=2)}; val s=coordinator(b,o)
        s.submit(proof()); s.awaitOperations(); assertEquals(SubmissionState.Failed,s.state.value); assertTrue(b.calls.isEmpty()); s.close()
    }
    @Test fun changedOpenSessionBlocksOldAction() = runBlocking {
        val b=Backend(); val o=Owner(); val s=coordinator(b,o); o.next=AttendanceAction.TimeOut
        s.submit(proof()); s.awaitOperations(); assertTrue(b.calls.isEmpty()); s.close()
    }
    private suspend fun CoroutineScope.denied(stage:String) {
        val b=Backend(); val o=Owner(); b.before={if(it==stage)throw SubmissionFailure(SubmissionProblem.Denied)}
        val s=coordinator(b,o); s.submit(proof()); s.awaitOperations(); assertEquals(0,o.refreshes)
        assertEquals(if(stage=="finalize")SubmissionState.OutcomeUnknown else SubmissionState.Failed,s.state.value); s.close()
    }
    @Test fun prepareDenied() = runBlocking { denied("prepare") }
    @Test fun uploadFailure() = runBlocking { denied("upload") }
    @Test fun finalizeDeniedIsConservativelyUncertain() = runBlocking { denied("finalize") }
    @Test fun finalizeTimeoutReconcilesWithSameUploadAndNeverResends() = runBlocking {
        val b=Backend(); val o=Owner(); b.before={if(it=="finalize")delay(100)}
        val s=coordinator(b,o,deadline=10); s.submit(proof()); s.awaitOperations()
        assertEquals(SubmissionState.OutcomeUnknown,s.state.value); assertEquals(0,o.refreshes)
        b.before={}; s.reconcile(); s.awaitOperations(); assertEquals(SubmissionState.Completed,s.state.value)
        assertEquals(1,b.calls.count{it=="finalize"}); assertEquals(1,b.ids.distinct().size); assertEquals(1,o.refreshes); s.close()
    }
    @Test fun emptyReconciliationDoesNotMeanFailureOrSuccess() = runBlocking {
        val b=object:ProofBackend {
            override suspend fun prepare(requestId:String,action:AttendanceAction)=prepared()
            override suspend fun upload(bucket:String,path:String,jpeg:ByteArray) {}
            override suspend fun finalize(uploadId:String,fix:Fix):String=throw java.io.IOException()
            override suspend fun receipt(uploadId:String)="[]"
        }
        val o=Owner();val s=coordinator(b,o);s.submit(proof());s.awaitOperations();s.reconcile();s.awaitOperations()
        assertEquals(SubmissionState.OutcomeUnknown,s.state.value);assertEquals(0,o.refreshes);s.close()
    }
    @Test fun repeatedTapsHaveOneRequestIdentifier() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val b=Backend();val o=Owner()
        b.before={if(it=="prepare"){entered.complete(Unit);release.await()}}
        val s=coordinator(b,o);s.submit(proof());entered.await();s.submit(proof());release.complete(Unit);s.awaitOperations()
        s.submit(proof());assertEquals(1,b.ids.size);assertEquals(1,o.refreshes);s.close()
    }
    private suspend fun CoroutineScope.revoke(stage:String, dismiss:Boolean=false) {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val completed=CompletableDeferred<Unit>()
        val b=Backend();val o=Owner();b.before={if(it==stage)withContext(NonCancellable){entered.complete(Unit);release.await();completed.complete(Unit)}}
        val s=coordinator(b,o);s.submit(proof());entered.await()
        if(dismiss)s.close() else o.approved=false
        release.complete(Unit);s.awaitOperations();assertTrue(completed.isCompleted);assertEquals(0,o.refreshes)
        if(stage=="prepare")assertFalse(b.calls.contains("upload"))
        if(stage=="upload")assertFalse(b.calls.contains("finalize"))
        assertNotEquals(SubmissionState.Completed,s.state.value);s.close()
    }
    @Test fun logoutDuringPrepare()=runBlocking{revoke("prepare")}
    @Test fun logoutDuringUpload()=runBlocking{revoke("upload")}
    @Test fun logoutDuringFinalize()=runBlocking{revoke("finalize")}
    @Test fun replacementRejectsOldCallback()=runBlocking{revoke("finalize")}
    @Test fun approvalWithdrawalRejectsUploadCallback()=runBlocking{revoke("upload")}
    @Test fun screenDismissalDuringFinalizeDoesNotClaimNoMutation()=runBlocking{revoke("finalize",true)}
    @Test fun sessionIdentityChangeDuringUploadFailsClosed()=runBlocking{revoke("upload")}
    private suspend fun CoroutineScope.accountCannotSubmit(o: Owner) {
        val b = Backend(); val s = coordinator(b, o)
        try { s.submit(proof()); s.awaitOperations(); assertTrue(b.calls.isEmpty()); assertEquals(0, o.refreshes)
            assertEquals(SubmissionState.Cancelled, s.state.value)
        } finally { s.close() }
    }
    @Test fun pendingStudentCannotSubmit() = runBlocking { accountCannotSubmit(Owner(status = "pending")) }
    @Test fun rejectedStudentCannotSubmit() = runBlocking { accountCannotSubmit(Owner(status = "rejected")) }
    @Test fun approvedAdminCannotSubmitStudentAttendance() = runBlocking { accountCannotSubmit(Owner(role = "admin")) }
    @Test fun signedOutAccountCannotSubmit() = runBlocking { accountCannotSubmit(Owner(signedIn = false)) }
    @Test fun staleAndInvalidGpsDoNotPrepare()=runBlocking {
        for(f in listOf(fix.copy(elapsedMillis=-1),fix.copy(latitude=91.0),fix.copy(accuracy=Float.NaN))) {
            val b=Backend();val o=Owner();val s=coordinator(b,o);s.submit(proof(f));s.awaitOperations();assertTrue(b.calls.isEmpty());s.close()
        }
    }
    @Test fun missingSelfieDoesNotPrepare()=runBlocking {
        val b=Backend();val o=Owner();val s=coordinator(b,o);s.submit(ConfirmedProof(byteArrayOf(),fix));s.awaitOperations();assertTrue(b.calls.isEmpty());s.close()
    }
    @Test fun currentGateCannotIssueAnyOperation()=runBlocking {
        val s=coordinator(DisabledProofBackend,Owner());s.submit(proof());s.awaitOperations();assertEquals(SubmissionState.Disabled,s.state.value);s.close()
        try{DisabledProofBackend.upload("attendance-proofs","anything",byteArrayOf(1));fail()}catch(e:SubmissionFailure){assertEquals(SubmissionProblem.Disabled,e.problem)}
        try{DisabledProofBackend.finalize(upload,fix);fail()}catch(e:SubmissionFailure){assertEquals(SubmissionProblem.Disabled,e.problem)}
    }
    @Test fun successfulReplacementRemainsAuthoritativeAfterOldFinalizeCompletes() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val done=CompletableDeferred<Unit>()
        val a=Owner(); val backendA=Backend(); backendA.before={ if(it=="finalize")withContext(NonCancellable){entered.complete(Unit);release.await();done.complete(Unit)} }
        val old=coordinator(backendA,a);old.submit(proof());entered.await();a.approved=false
        val b=Owner(); val studentB="00000000-0000-4000-8000-000000000009"; val replacement=coordinator(Backend(studentB),b,identity=studentB);replacement.submit(proof());replacement.awaitOperations()
        assertEquals(SubmissionState.Completed,replacement.state.value);assertEquals(1,b.refreshes)
        release.complete(Unit);old.awaitOperations();assertTrue(done.isCompleted);assertEquals(0,a.refreshes)
        assertEquals(SubmissionState.Completed,replacement.state.value);assertEquals(1,b.refreshes);old.close();replacement.close()
    }
    @Test fun delayedReceiptCannotPublishIntoReplacementAfterAuthorizationWithdrawal() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val a = Owner(); val backendA = Backend()
        backendA.before = { if (it == "finalize") throw java.io.IOException() }
        val old = coordinator(backendA, a)
        try {
            old.submit(proof()); old.awaitOperations()
            assertEquals(SubmissionState.OutcomeUnknown, old.state.value)
            backendA.before = { if (it == "receipt") withContext(NonCancellable) {
                entered.complete(Unit); release.await(); completed.complete(Unit)
            } }
            old.reconcile(); entered.await(); a.approved = false
            val b = Owner(); val studentB = "00000000-0000-4000-8000-000000000009"
            val replacement = coordinator(Backend(studentB), b, identity = studentB)
            try {
                replacement.submit(proof()); replacement.awaitOperations()
                assertEquals(SubmissionState.Completed, replacement.state.value)
                release.complete(Unit); old.awaitOperations()
                assertTrue(completed.isCompleted)
                assertEquals(SubmissionState.OutcomeUnknown, old.state.value)
                assertEquals(0, a.refreshes); assertEquals(1, b.refreshes)
                assertEquals(SubmissionState.Completed, replacement.state.value)
                assertEquals(1, backendA.calls.count { it == "finalize" })
                assertEquals(1, backendA.calls.count { it == "receipt" })
            } finally { replacement.close() }
        } finally { release.complete(Unit); old.close(); old.awaitOperations() }
    }
    @Test fun completionSurvivesCloseAndRepeatedCloseDuringBlockedRefresh() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val o = Owner(); val s = AttendanceSubmission(this, Backend(), uid, o.next, { o.approved }, { o.next },
            { o.approved }, { withContext(NonCancellable) { entered.complete(Unit); release.await(); finished.complete(Unit) } },
            { 1000 }, { now })
        try {
            s.submit(proof()); entered.await(); assertEquals(SubmissionState.Completed, s.state.value)
            s.close(); s.close(); release.complete(Unit); s.awaitOperations()
            assertTrue(finished.isCompleted); assertEquals(SubmissionState.Completed, s.state.value)
            s.submit(proof()); s.reconcile(); assertEquals(SubmissionState.Completed, s.state.value)
        } finally { release.complete(Unit); s.close(); s.awaitOperations() }
    }
    @Test fun failedAndDisabledRemainTerminalOnClose() = runBlocking {
        val o = Owner(); val b = Backend().apply { before = { throw SubmissionFailure(SubmissionProblem.Denied) } }
        val failed = coordinator(b, o); failed.submit(proof()); failed.awaitOperations(); failed.close()
        assertEquals(SubmissionState.Failed, failed.state.value)
        val gated = coordinator(DisabledProofBackend, Owner()); gated.submit(proof()); gated.awaitOperations(); gated.close()
        assertEquals(SubmissionState.Disabled, gated.state.value)
    }
    @Test fun unknownOutcomeSurvivesCloseAndLateFinalizeResponse() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val b = Backend().apply { before = { if (it == "finalize") withContext(NonCancellable) {
            entered.complete(Unit); release.await(); finished.complete(Unit)
        } } }
        val o = Owner(); val s = coordinator(b, o)
        try {
            s.submit(proof()); entered.await(); s.close(); assertEquals(SubmissionState.OutcomeUnknown, s.state.value)
            release.complete(Unit); s.awaitOperations(); assertTrue(finished.isCompleted)
            assertEquals(SubmissionState.OutcomeUnknown, s.state.value); assertEquals(0, o.refreshes)
            s.close(); assertEquals(SubmissionState.OutcomeUnknown, s.state.value)
        } finally { release.complete(Unit); s.close(); s.awaitOperations() }
    }
    @Test fun malformedOrForeignTicketRejected() {
        for(raw in listOf("[]",prepared().replace(uid,"00000000-0000-4000-8000-000000000009"),prepared().replace("/proof","/../proof")))
            try{ProofContract.prepared(raw,uid,now);fail()}catch(_:Exception){}
    }
    @Test fun receiptIdentityOrLocationConflictCannotSucceed() {
        val ticket=ProofContract.prepared(prepared(),uid,now)
        for(raw in listOf(receipt(AttendanceAction.TimeIn).replace("\"accuracy\":8","\"accuracy\":9"),receipt(AttendanceAction.TimeOut)))
            try{ProofContract.receipt(raw,uid,AttendanceAction.TimeIn,ticket,fix);fail()}catch(_:Exception){}
    }
}
