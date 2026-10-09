package ph.edu.bsit.tcc.ojtdtr.proof

import java.time.Instant
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceAction

class SupabaseProofBackendTest {
    private val uid = "00000000-0000-4000-8000-000000000001"
    private val upload = "00000000-0000-4000-8000-000000000002"
    private val session = "00000000-0000-4000-8000-000000000003"
    private val request = "00000000-0000-4000-8000-000000000004"
    private val now = Instant.parse("2026-10-09T00:00:00Z")
    private val path get() = "$uid/$session/$upload/proof"
    private val fix = Fix(14.5, 121.0, 8f, 1, true)
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
    private val prepared get() = "[{\"upload_id\":\"$upload\",\"attendance_session_id\":\"$session\",\"photo_path\":\"$path\",\"expires_at\":\"2026-10-09T01:00:00Z\"}]"
    private val receipt get() = """{"id":"$request","student_uid":"$uid","upload_id":"$upload","attendance_session_id":"$session","photo_path":"$path","action_type":"time_in","latitude":14.5,"longitude":121.0,"accuracy":8,"official_punch_at":"2026-10-09T00:01:00Z","attached_at":"2026-10-09T00:01:00Z"}"""
    private inner class Wire : ProofTransport {
        override var ownerId: String? = uid
        var prepare = ProofWireResponse(200, prepared)
        var finish = ProofWireResponse(200, receipt)
        var read = ProofWireResponse(200, "[$receipt]")
        var storage = ProofWireResponse(200, "{}")
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var uploads = 0
        var readIdentity: Pair<String,String>? = null
        var barrier: (suspend (String) -> Unit)? = null
        override suspend fun rpc(name: String, arguments: JsonObject): ProofWireResponse {
            calls += name to arguments; barrier?.invoke(name)
            return if (name.endsWith("prepare")) prepare else finish
        }
        override suspend fun upload(path: String, bytes: ByteArray, mime: String): ProofWireResponse {
            assertEquals(this@SupabaseProofBackendTest.path, path); assertSame(jpeg, bytes)
            assertEquals("image/jpeg", mime); uploads++; barrier?.invoke("upload"); return storage
        }
        override suspend fun receipt(uid: String, uploadId: String): ProofWireResponse {
            readIdentity = uid to uploadId; barrier?.invoke("receipt"); return read
        }
    }
    private fun backend(w: Wire, current: () -> Boolean = { true }, deadline: Long = 1000) =
        SupabaseProofBackend(uid, current, w, { now }, deadline)
    private suspend fun ready(b: SupabaseProofBackend) { b.prepare(request, AttendanceAction.TimeIn); b.upload(ProofContract.BUCKET, path, jpeg) }
    private suspend fun failure(problem: ProofTransportProblem, call: suspend () -> Unit) {
        try { call(); fail("Expected fixed failure") }
        catch (e: ProofTransportFailure) { assertEquals(problem, e.problem); assertNull(e.message); assertNull(e.cause) }
    }
    @Test fun prepareUsesSqlArguments() = runBlocking {
        val w=Wire(); backend(w).prepare(request, AttendanceAction.TimeIn)
        assertEquals("attendance_proof_prepare",w.calls.single().first)
        assertEquals(buildJsonObject { put("request_id",request); put("action_type","time_in") },w.calls.single().second)
    }
    @Test fun timeOutUsesExactAction() = runBlocking {
        val w=Wire(); backend(w).prepare(request,AttendanceAction.TimeOut)
        assertEquals("time_out",w.calls.single().second["action_type"]!!.jsonPrimitive.content)
    }
    @Test fun malformedPreparationRejected() = runBlocking {
        for (raw in listOf("[]","{}","[null]",prepared.replace("upload_id","missing"),prepared.replace(upload,"invalid"))) {
            val w=Wire(); w.prepare=ProofWireResponse(200,raw)
            failure(ProofTransportProblem.Rejected) { backend(w).prepare(request,AttendanceAction.TimeIn) }
        }
    }
    @Test fun crossAccountPreparationRejected() = runBlocking {
        val w=Wire(); w.prepare=ProofWireResponse(200,prepared.replace(uid,request))
        failure(ProofTransportProblem.Rejected) { backend(w).prepare(request,AttendanceAction.TimeIn) }
    }
    @Test fun arbitraryReservedPathRejected() = runBlocking {
        val w=Wire(); w.prepare=ProofWireResponse(200,prepared.replace("/proof","/../proof"))
        failure(ProofTransportProblem.Rejected) { backend(w).prepare(request,AttendanceAction.TimeIn) }
    }
    @Test fun expiredReservationRejected() = runBlocking {
        val w=Wire(); w.prepare=ProofWireResponse(200,prepared.replace("01:00:00","00:00:00"))
        failure(ProofTransportProblem.Rejected) { backend(w).prepare(request,AttendanceAction.TimeIn) }
    }
    @Test fun missingTransportFailsClosed() = runBlocking {
        try { SupabaseProofBackend(uid,{true},null,{now}).prepare(request,AttendanceAction.TimeIn); fail() }
        catch(e:SubmissionFailure) { assertEquals(SubmissionProblem.Disabled,e.problem) }
    }
    @Test fun expiredAuthorityMakesNoRequest() = runBlocking {
        val w=Wire(); failure(ProofTransportProblem.Authorization) { backend(w,{false}).prepare(request,AttendanceAction.TimeIn) }; assertTrue(w.calls.isEmpty())
    }
    @Test fun correctUploadUsesReservedDestinationAndSameBuffer() = runBlocking {
        val w=Wire(); ready(backend(w)); assertEquals(1,w.uploads)
    }
    @Test fun incorrectBucketRejected() = runBlocking {
        val w=Wire(); val b=backend(w); b.prepare(request,AttendanceAction.TimeIn)
        failure(ProofTransportProblem.Rejected) { b.upload("public",path,jpeg) }; assertEquals(0,w.uploads)
    }
    @Test fun incorrectUploadPathRejected() = runBlocking {
        val w=Wire(); val b=backend(w); b.prepare(request,AttendanceAction.TimeIn)
        failure(ProofTransportProblem.Rejected) { b.upload(ProofContract.BUCKET,"$path/other",jpeg) }; assertEquals(0,w.uploads)
    }
    @Test fun oversizedUploadRejected() = runBlocking {
        val w=Wire(); val b=backend(w); b.prepare(request,AttendanceAction.TimeIn)
        failure(ProofTransportProblem.Rejected) { b.upload(ProofContract.BUCKET,path,ByteArray(5242881)) }; assertEquals(0,w.uploads)
    }
    @Test fun nonJpegRejected() = runBlocking {
        val w=Wire(); val b=backend(w); b.prepare(request,AttendanceAction.TimeIn)
        failure(ProofTransportProblem.Rejected) { b.upload(ProofContract.BUCKET,path,byteArrayOf(1,2,3)) }; assertEquals(0,w.uploads)
    }
    @Test fun uploadAuthorizationRejectionIsKnown() = runBlocking {
        val w=Wire(); val b=backend(w); b.prepare(request,AttendanceAction.TimeIn); w.storage=ProofWireResponse(403,"sensitive-server-body")
        failure(ProofTransportProblem.Authorization) { b.upload(ProofContract.BUCKET,path,jpeg) }
        failure(ProofTransportProblem.Rejected) { b.finalize(upload,fix) }
    }
    @Test fun uploadTimeoutCannotResendOrFinalize() = runBlocking {
        val w=Wire(); val b=backend(w,deadline=20); b.prepare(request,AttendanceAction.TimeIn); w.barrier={ awaitCancellation() }
        failure(ProofTransportProblem.OutcomeUnknown) { b.upload(ProofContract.BUCKET,path,jpeg) }
        failure(ProofTransportProblem.Rejected) { b.upload(ProofContract.BUCKET,path,jpeg) }
        failure(ProofTransportProblem.Rejected) { b.finalize(upload,fix) }; assertEquals(1,w.uploads)
    }
    @Test fun finalizeUsesExactNumericSqlArguments() = runBlocking {
        val w=Wire(); val b=backend(w); ready(b); assertEquals(receipt,b.finalize(upload,fix))
        assertEquals(buildJsonObject { put("upload_id",upload);put("latitude",14.5);put("longitude",121.0);put("accuracy",8f) },w.calls.last().second)
    }
    private suspend fun badFix(f:Fix) { val w=Wire(); val b=backend(w);ready(b);failure(ProofTransportProblem.Rejected){b.finalize(upload,f)};assertEquals(1,w.calls.size) }
    @Test fun invalidLatitudeRejected()=runBlocking { badFix(fix.copy(latitude=91.0));badFix(fix.copy(latitude=Double.NaN)) }
    @Test fun invalidLongitudeRejected()=runBlocking { badFix(fix.copy(longitude=-181.0));badFix(fix.copy(longitude=Double.POSITIVE_INFINITY)) }
    @Test fun invalidAccuracyRejected()=runBlocking { badFix(fix.copy(accuracy=-1f));badFix(fix.copy(accuracy=Float.NaN)) }
    @Test fun duplicateFinalizeCannotDispatch()=runBlocking {
        val w=Wire();val b=backend(w);ready(b);b.finalize(upload,fix)
        failure(ProofTransportProblem.Rejected){b.finalize(upload,fix)};assertEquals(2,w.calls.size)
    }
    @Test fun finalizeTimeoutAllowsOnlyReceiptRead()=runBlocking {
        val w=Wire();val b=backend(w,deadline=20);ready(b);w.barrier={awaitCancellation()}
        failure(ProofTransportProblem.OutcomeUnknown){b.finalize(upload,fix)};w.barrier=null
        failure(ProofTransportProblem.Rejected){b.finalize(upload,fix)};assertEquals("[$receipt]",b.receipt(upload))
    }
    @Test fun confirmedReceiptUsesOwnerAndUploadFilters()=runBlocking {
        val w=Wire();val b=backend(w);ready(b);b.finalize(upload,fix);b.receipt(upload);assertEquals(uid to upload,w.readIdentity)
    }
    @Test fun missingReceiptRemainsEmpty()=runBlocking {
        val w=Wire();val b=backend(w);ready(b);b.finalize(upload,fix);w.read=ProofWireResponse(200,"[]");assertEquals("[]",b.receipt(upload))
    }
    @Test fun malformedReceiptRejected()=runBlocking {
        for(raw in listOf("{}","[null]","[$receipt,$receipt]", "[${receipt.replace("official_punch_at","missing")}]")) {
            val w=Wire();val b=backend(w);ready(b);b.finalize(upload,fix);w.read=ProofWireResponse(200,raw)
            failure(ProofTransportProblem.Rejected){b.receipt(upload)}
        }
    }
    @Test fun crossAccountReceiptRejected()=runBlocking {
        val w=Wire();val b=backend(w);ready(b);b.finalize(upload,fix);w.read=ProofWireResponse(200,"[${receipt.replace(uid,request)}]")
        failure(ProofTransportProblem.Rejected){b.receipt(upload)}
    }
    @Test fun incorrectActionAndCoordinatesRejected()=runBlocking {
        for(raw in listOf(receipt.replace("time_in","time_out"),receipt.replace("14.5","14.6"))) {
            val w=Wire();val b=backend(w);ready(b);w.finish=ProofWireResponse(200,raw)
            failure(ProofTransportProblem.Rejected){b.finalize(upload,fix)}
        }
    }
    @Test fun accountSwitchDuringEveryStageRejectsLateResult()=runBlocking {
        for(stage in listOf("attendance_proof_prepare","upload","attendance_proof_finalize","receipt")) {
            var current=true; val w=Wire();val b=backend(w,{current})
            if(stage!="attendance_proof_prepare") b.prepare(request,AttendanceAction.TimeIn)
            if(stage in listOf("attendance_proof_finalize","receipt")) b.upload(ProofContract.BUCKET,path,jpeg)
            if(stage=="receipt") b.finalize(upload,fix)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            w.barrier={ if(it==stage) withContext(NonCancellable){entered.complete(Unit);release.await()} }
            val work=launch { failure(ProofTransportProblem.Authorization){when(stage){
                "attendance_proof_prepare"->b.prepare(request,AttendanceAction.TimeIn)
                "upload"->b.upload(ProofContract.BUCKET,path,jpeg)
                "attendance_proof_finalize"->b.finalize(upload,fix)
                else->b.receipt(upload)
            }} }
            entered.await();current=false;release.complete(Unit);work.join()
        }
    }
    @Test fun cancellationPreservedDuringEveryStage()=runBlocking {
        for(stage in listOf("attendance_proof_prepare","upload","attendance_proof_finalize","receipt")) {
            val w=Wire();val b=backend(w)
            if(stage!="attendance_proof_prepare") b.prepare(request,AttendanceAction.TimeIn)
            if(stage in listOf("attendance_proof_finalize","receipt")) b.upload(ProofContract.BUCKET,path,jpeg)
            if(stage=="receipt") b.finalize(upload,fix)
            val entered=CompletableDeferred<Unit>(); w.barrier={entered.complete(Unit);awaitCancellation()}
            val work=launch { when(stage){
                "attendance_proof_prepare"->b.prepare(request,AttendanceAction.TimeIn)
                "upload"->b.upload(ProofContract.BUCKET,path,jpeg)
                "attendance_proof_finalize"->b.finalize(upload,fix)
                else->b.receipt(upload)
            } };entered.await();work.cancelAndJoin();assertTrue(work.isCancelled)
        }
    }
    @Test fun repeatedPrepareCannotRegenerateRequest()=runBlocking {
        val w=Wire();val b=backend(w);b.prepare(request,AttendanceAction.TimeIn)
        failure(ProofTransportProblem.Rejected){b.prepare(request,AttendanceAction.TimeIn)};assertEquals(1,w.calls.size)
    }
    @Test fun knownRejectionAndMissingRpcRemainFailures()=runBlocking {
        for(status in listOf(400,401,403,404,409,500)) {
            val w=Wire();w.prepare=ProofWireResponse(status,"private-data")
            failure(if(status in listOf(401,403)) ProofTransportProblem.Authorization else if(status==404) ProofTransportProblem.Unavailable else if(status<500) ProofTransportProblem.Rejected else ProofTransportProblem.OutcomeUnknown){backend(w).prepare(request,AttendanceAction.TimeIn)}
        }
    }
    @Test fun overlappingFinalizeDispatchesExactlyOnce()=runBlocking {
        val w=Wire();val b=backend(w);ready(b)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        w.barrier={ if(it.endsWith("finalize")){entered.complete(Unit);release.await()} }
        val first=launch {b.finalize(upload,fix)};entered.await()
        val second=launch {failure(ProofTransportProblem.Rejected){b.finalize(upload,fix)}}
        release.complete(Unit);first.join();second.join();assertEquals(2,w.calls.size)
    }

    @Test fun mismatchedTransportIdentityFailsBeforePrepareDispatch()=runBlocking {
        val w=Wire();w.ownerId=request
        failure(ProofTransportProblem.Authorization){backend(w).prepare(request,AttendanceAction.TimeIn)}
        assertTrue(w.calls.isEmpty())
    }

    @Test fun workflowMapsTransportAuthorizationWithoutExposingResponse()=runBlocking {
        val w=Wire();w.prepare=ProofWireResponse(403,"private-server-body")
        val workflow=AttendanceSubmission(this,backend(w),uid,AttendanceAction.TimeIn,
            {true},{AttendanceAction.TimeIn},{true},{}, {1},{now})
        try {
            workflow.submit(ConfirmedProof(jpeg,fix));workflow.awaitOperations()
            assertEquals(SubmissionState.Failed,workflow.state.value)
            assertEquals(SubmissionProblem.Authorization,workflow.problem.value)
            assertTrue(jpeg.all{it==0.toByte()})
        } finally {workflow.close()}
    }
    @Test fun workflowKeepsUnknownAfterAdapterFinalizeTimeoutAndMissingReceipt()=runBlocking {
        val w=Wire();w.barrier={if(it.endsWith("finalize")) awaitCancellation()}
        val workflow=AttendanceSubmission(this,backend(w,deadline=20),uid,AttendanceAction.TimeIn,
            {true},{AttendanceAction.TimeIn},{true},{fail("Unconfirmed refresh")}, {1},{now})
        try {
            workflow.submit(ConfirmedProof(jpeg,fix));workflow.awaitOperations()
            assertEquals(SubmissionState.OutcomeUnknown,workflow.state.value)
            w.barrier=null;w.read=ProofWireResponse(200,"[]")
            workflow.reconcile();workflow.awaitOperations()
            assertEquals(SubmissionState.OutcomeUnknown,workflow.state.value)
            workflow.close();assertEquals(SubmissionState.OutcomeUnknown,workflow.state.value)
            assertEquals(2,w.calls.size)
        } finally {workflow.close()}
    }

    @Test fun fractionalCoordinatesRoundTripAndNumericStringsAreRejected()=runBlocking {
        val precise=fix.copy(latitude=14.599512345678,longitude=120.9842123456,accuracy=3.14f)
        val w=Wire();val b=backend(w);ready(b)
        w.finish=ProofWireResponse(200,receipt.replace("14.5",precise.latitude.toString())
            .replace("121.0",precise.longitude.toString()).replace("\"accuracy\":8", "\"accuracy\":3.14"))
        b.finalize(upload,precise)
        assertEquals(precise.latitude.toBigDecimal(),w.calls.last().second["latitude"]!!.jsonPrimitive.content.toBigDecimal())
        assertEquals(precise.longitude.toBigDecimal(),w.calls.last().second["longitude"]!!.jsonPrimitive.content.toBigDecimal())
        w.read=ProofWireResponse(200,"[${w.finish.body.replace("3.14", "\"3.14\"")}]")
        failure(ProofTransportProblem.Rejected){b.receipt(upload)}
    }
    @Test fun inconsistentReceiptTimestampAndIdentityRejected()=runBlocking {
        for(raw in listOf(receipt.replace("\"attached_at\":\"2026-10-09T00:01:00Z\"", "\"attached_at\":\"2026-10-09T00:00:00Z\""),
            receipt.replace("\"official_punch_at\":\"2026-10-09T00:01:00Z\"", "\"official_punch_at\":null"),
            receipt.replace("\"upload_id\":\"$upload\"", "\"upload_id\":\"$request\""))) {
            val w=Wire();val b=backend(w);ready(b);w.finish=ProofWireResponse(200,raw)
            failure(ProofTransportProblem.Rejected){b.finalize(upload,fix)}
        }
    }

    @Test fun boundedReaderAcceptsSmallAndExactLimitJson()=runBlocking {
        for(raw in listOf("{\"ok\":true}", "[]"+" ".repeat(PROOF_RESPONSE_MAX_BYTES-2),
            "\""+"é".repeat((PROOF_RESPONSE_MAX_BYTES-2)/2)+"\"")) {
            val channel=ByteReadChannel(raw.toByteArray())
            assertEquals(raw,readProofResponse(channel));assertTrue(channel.isClosedForRead)
        }
    }
    @Test fun boundedReaderRejectsOverflowWithFixedDiagnosticsAndCloses()=runBlocking {
        for(size in listOf(PROOF_RESPONSE_MAX_BYTES+1,PROOF_RESPONSE_MAX_BYTES*32)) {
            val channel=ByteReadChannel(ByteArray(size){'x'.code.toByte()})
            failure(ProofTransportProblem.Unavailable){readProofResponse(channel)}
            // Source-backed channels report remaining buffered bytes after cancel;
            // closedCause, rather than isClosedForRead, proves cancellation.
            assertNotNull(channel.closedCause)
        }
    }
    @Test fun boundedReaderHandlesChunkedUnknownLength()=runBlocking {
        val channel=ByteChannel(autoFlush=true)
        val writer=launch {channel.writeFully("[".toByteArray());yield();channel.writeFully("]".toByteArray());channel.close()}
        assertEquals("[]",readProofResponse(channel));writer.join();assertTrue(channel.isClosedForRead)
    }
    @Test fun boundedReaderCancellationClosesPartialStream()=runBlocking {
        val channel=ByteChannel(autoFlush=true);channel.writeFully("[".toByteArray())
        val reading=CompletableDeferred<Unit>()
        val observed=object:ByteReadChannel by channel {
            override suspend fun awaitContent(min:Int):Boolean {reading.complete(Unit);return channel.awaitContent(min)}
        }
        val work=launch{readProofResponse(observed)};reading.await();work.cancelAndJoin()
        assertTrue(work.isCancelled);assertTrue(channel.isClosedForRead)
    }
    @Test fun boundedReaderRejectsFailedStreamWithoutAcceptingPartialJson()=runBlocking {
        val channel=ByteChannel(autoFlush=true);channel.writeFully("[]".toByteArray())
        val error=java.io.IOException("synthetic private-response sentinel");channel.cancel(error)
        failure(ProofTransportProblem.OutcomeUnknown){readProofResponse(channel)}
        assertTrue(channel.isClosedForRead)
    }

}
