package ph.edu.bsit.tcc.ojtdtr.proof

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ProofSessionTest {
    private fun fix(precise: Boolean = true) = Fix(14.0, 121.0, 8f, 1000, precise)
    private class Camera : ProofCamera {
        var stopped = 0
        var action: suspend (File) -> Unit = { it.writeBytes(byteArrayOf(1, 2)) }
        override suspend fun capture(file: File) = action(file)
        override fun stop() { stopped++ }
    }
    private class Location : ProofLocation {
        var stopped = 0
        var action: suspend () -> Fix = { Fix(14.0, 121.0, 8f, 1000, true) }
        override suspend fun acquire() = action()
        override fun stop() { stopped++ }
    }
    private suspend fun CoroutineScope.check(block: suspend (ProofSession, Camera, Location, File) -> Unit) {
        val root = Files.createTempDirectory("proof-unit-").toFile()
        val camera = Camera(); val location = Location()
        val session = ProofSession(this, File(root,"native-proof/session"), root, camera, location, { true }, { true }, { 1000 }, { it.isFile && it.length() > 0 })
        try { block(session, camera, location, root) } finally { session.close(); root.deleteRecursively() }
    }
    @Test fun confirmedProofTransferredOnceAndDeletedAfterConsumerCompletion() = runBlocking {
        val root = Files.createTempDirectory("submission-proof-").toFile()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var consumed = 0; var bytes: ByteArray? = null
        val s = ProofSession(this, File(root,"native-proof/session"), root, Camera(), Location(),
            { true }, { true }, { 1000 }, { it.isFile && it.length() > 0 },
            onConfirmed = { proof -> consumed++; bytes = proof.bytes; entered.complete(Unit); release.await() })
        try {
            s.capture(Permission.Granted); s.awaitOperations(); s.locate(Permission.Granted); s.awaitOperations()
            s.confirm(); entered.await(); s.confirm(); assertEquals(1,consumed)
            release.complete(Unit); s.awaitOperations(); assertTrue(s.state.value.confirmed)
            assertTrue(bytes!!.all { it == 0.toByte() }); assertEquals(0,root.walkTopDown().count { it.isFile })
        } finally { s.close(); root.deleteRecursively() }
    }
    @Test fun corruptCaptureCannotReachSubmissionConsumer() = runBlocking {
        val root = Files.createTempDirectory("submission-invalid-").toFile(); var consumed = false
        val s = ProofSession(this, File(root,"native-proof/session"), root, Camera(), Location(),
            { true }, { true }, { 1000 }, { false }, onConfirmed = { consumed = true })
        try { s.capture(Permission.Granted); s.awaitOperations(); s.locate(Permission.Granted); s.awaitOperations()
            s.confirm(); s.awaitOperations(); assertFalse(consumed); assertFalse(s.state.value.confirmed)
        } finally { s.close(); root.deleteRecursively() }
    }
    @Test fun cameraGrantedCapturesAndRetakeDeletesPrivateFile() = runBlocking {
        check { s, _, _, root ->
            s.capture(Permission.Granted); s.awaitOperations(); assertTrue(s.state.value.captured)
            assertEquals(1, root.walkTopDown().filter { it.isFile }.count())
            s.retake(); assertFalse(s.state.value.captured); assertEquals(0,root.walkTopDown().filter { it.isFile }.count())
        }
    }
    @Test fun cameraDeniedDoesNotCapture() = runBlocking {
        check { s, _, _, root -> s.capture(Permission.Denied); s.awaitOperations(); assertEquals(ProofProblem.PermissionDenied,s.state.value.problem); assertEquals(0,root.walkTopDown().filter { it.isFile }.count()) }
    }
    @Test fun permanentDenialHasSettingsState() = runBlocking {
        check { s, _, _, _ -> s.capture(Permission.SettingsRequired); assertEquals(ProofProblem.SettingsRequired,s.state.value.problem) }
    }
    @Test fun unavailableCameraFailsClosed() = runBlocking {
        check { s, c, _, _ -> c.action = { throw ProofFailure(ProofProblem.CameraUnavailable) }; s.capture(Permission.Granted); s.awaitOperations(); assertFalse(s.state.value.captured); assertEquals(ProofProblem.CameraUnavailable,s.state.value.problem) }
    }
    @Test fun partialCaptureFailureDeletesOutput() = runBlocking {
        check { s, c, _, root -> c.action = { it.writeBytes(byteArrayOf(1)); throw java.io.IOException() }; s.capture(Permission.Granted); s.awaitOperations(); assertEquals(ProofProblem.CaptureFailed,s.state.value.problem); assertEquals(0,root.walkTopDown().filter { it.isFile }.count()) }
    }
    @Test fun confirmRevalidatesAndDeletesAllProofWithoutUpload() = runBlocking {
        check { s, _, _, root -> s.capture(Permission.Granted); s.awaitOperations(); s.locate(Permission.Granted); s.awaitOperations(); s.confirm(); s.awaitOperations(); assertTrue(s.state.value.confirmed); assertNull(s.state.value.accuracy); assertEquals(0,root.walkTopDown().filter { it.isFile }.count()) }
    }
    @Test fun preciseLocationPublishesAccuracyOnly() = runBlocking {
        check { s, _, _, _ -> s.locate(Permission.Granted); s.awaitOperations(); assertEquals(8f,s.state.value.accuracy); assertEquals(true,s.state.value.precise) }
    }
    @Test fun approximateLocationIsAcceptedAndLabelled() = runBlocking {
        check { s, _, l, _ -> l.action = { fix(false) }; s.locate(Permission.Granted); s.awaitOperations(); assertEquals(false,s.state.value.precise) }
    }
    @Test fun coordinatesAndAccuracyValidationRejectsInvalidValues() {
        for (bad in listOf(fix().copy(latitude=Double.NaN),fix().copy(latitude=91.0),fix().copy(longitude=181.0),
            fix().copy(longitude=Double.POSITIVE_INFINITY),fix().copy(accuracy=Float.NaN),fix().copy(accuracy=0f),
            fix().copy(accuracy=-1f),fix().copy(accuracy=Float.POSITIVE_INFINITY))) assertFalse(bad.valid(1000))
    }
    @Test fun oldFutureAndMissingMonotonicTimesAreRejected() {
        assertFalse(fix().valid(31001)); assertFalse(fix().valid(999)); assertFalse(fix().copy(elapsedMillis=-1).valid(1000))
        assertTrue(fix().valid(31000))
    }
    @Test fun invalidFixCannotEnableConfirmation() = runBlocking {
        check { s, _, l, _ -> l.action = { fix().copy(accuracy=Float.NaN) }; s.locate(Permission.Granted); s.awaitOperations(); assertNull(s.state.value.accuracy); assertEquals(ProofProblem.InvalidLocation,s.state.value.problem) }
    }
    @Test fun providerDisabledIsUnavailable() = runBlocking {
        check { s, _, l, _ -> l.action = { throw ProofFailure(ProofProblem.LocationUnavailable) }; s.locate(Permission.Granted); s.awaitOperations(); assertEquals(ProofProblem.LocationUnavailable,s.state.value.problem) }
    }
    @Test fun locationPermissionDeniedDoesNotRequest() = runBlocking {
        check { s, _, l, _ -> l.action = { error("must not request") }; s.locate(Permission.Denied); s.awaitOperations(); assertEquals(ProofProblem.PermissionDenied,s.state.value.problem) }
    }
    @Test fun locationTimeoutStopsAcquisition() = runBlocking {
        val root=Files.createTempDirectory("proof-timeout").toFile(); val c=Camera(); val l=Location()
        l.action={ awaitCancellation() }
        val s=ProofSession(this,File(root,"native-proof/session"),root,c,l,{true},{true},{1000},{it.isFile && it.length()>0},1)
        try { s.locate(Permission.Granted); withTimeout(1000) { s.state.first { it.problem == ProofProblem.Timeout } }; assertEquals(ProofProblem.Timeout,s.state.value.problem); assertTrue(l.stopped>0) }
        finally { s.close(); root.deleteRecursively() }
    }
    @Test fun closingCancelsLocationAndClearsSensitiveDisplay() = runBlocking {
        check { s, c, l, _ ->
            val entered=CompletableDeferred<Unit>(); val cancelled=CompletableDeferred<Unit>()
            l.action={ entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
            s.locate(Permission.Granted); entered.await(); s.close(); cancelled.await()
            assertTrue(s.state.value.closed); assertNull(s.state.value.accuracy); assertTrue(c.stopped>0)
        }
    }
    @Test fun logoutAndReplacementDuringNonCooperativeCaptureDeleteLateFile() = runBlocking {
        for (reason in listOf("logout","replacement")) {
            var current=true; val root=Files.createTempDirectory("proof-race").toFile(); val c=Camera(); val l=Location()
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val finished=CompletableDeferred<Unit>()
            c.action={ file -> entered.complete(Unit); withContext(NonCancellable) { release.await(); file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1)); finished.complete(Unit) } }
            val s=ProofSession(this,File(root,"native-proof/session"),root,c,l,{current},{true},{1000},{it.isFile && it.length()>0})
            try {
                s.capture(Permission.Granted); entered.await(); current=false; s.close(); release.complete(Unit); finished.await(); s.awaitOperations()
                assertTrue(reason,s.state.value.closed); assertFalse(s.state.value.captured)
                assertEquals(0,root.walkTopDown().filter { it.isFile }.count())
            } finally { release.complete(Unit); s.close(); root.deleteRecursively() }
        }
    }
    @Test fun revokedDuringLocationCannotPublishLateCoordinates() = runBlocking {
        var current=true; val root=Files.createTempDirectory("proof-location-race").toFile(); val c=Camera(); val l=Location()
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val done=CompletableDeferred<Unit>()
        l.action={ entered.complete(Unit); withContext(NonCancellable) { release.await(); done.complete(Unit); fix() } }
        val s=ProofSession(this,File(root,"native-proof/session"),root,c,l,{current},{true},{1000},{it.isFile && it.length()>0})
        try { s.locate(Permission.Granted); entered.await(); current=false; s.close(); release.complete(Unit); done.await(); s.awaitOperations(); assertNull(s.state.value.accuracy); assertTrue(s.state.value.closed) }
        finally { release.complete(Unit); s.close(); root.deleteRecursively() }
    }
    @Test fun unauthorizedAccountsCannotStartHardware() = runBlocking {
        for (account in listOf("pending","rejected","admin","signed out")) {
            val root=Files.createTempDirectory("proof-denied").toFile(); val c=Camera(); val l=Location()
            c.action={error("unauthorized capture")}; l.action={error("unauthorized location")}
            val s=ProofSession(this,File(root,"native-proof/session"),root,c,l,{false},{false},{1000},{it.isFile && it.length()>0})
            s.capture(Permission.Granted); s.locate(Permission.Granted); s.awaitOperations(); assertTrue(account,s.state.value.closed); root.deleteRecursively()
        }
    }
    @Test fun confirmationRejectionClearsSelfie() = runBlocking {
        val root=Files.createTempDirectory("proof-confirm-denied").toFile(); val s=ProofSession(this,File(root,"native-proof/session"),root,Camera(),Location(),{true},{false},{1000},{it.isFile && it.length()>0})
        try { s.capture(Permission.Granted); s.awaitOperations(); s.locate(Permission.Granted); s.awaitOperations(); s.confirm(); s.awaitOperations(); assertTrue(s.state.value.closed); assertEquals(0,root.walkTopDown().filter{it.isFile}.count()) }
        finally { s.close(); root.deleteRecursively() }
    }
    @Test fun duplicateCaptureIsCoalesced() = runBlocking {
        check { s, c, _, _ ->
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var calls=0
            c.action={ calls++; entered.complete(Unit); release.await(); it.writeBytes(byteArrayOf(1)) }
            try { s.capture(Permission.Granted); entered.await(); s.capture(Permission.Granted); release.complete(Unit); s.awaitOperations(); assertEquals(1,calls) }
            finally { release.complete(Unit) }
        }
    }
    @Test fun uiStateContainsNoCoordinatesImagesOrCredentials() {
        assertEquals(setOf("captured","busy","accuracy","precise","confirmed","problem","closed"),
            ProofState::class.java.declaredFields.filterNot{java.lang.reflect.Modifier.isStatic(it.modifiers)}.map{it.name}.toSet())
    }
    @Test fun invalidImageFailsCaptureCleansUpAndCanRetry() = runBlocking {
        val root = Files.createTempDirectory("proof-invalid").toFile()
        var valid = false
        val s = ProofSession(this, File(root,"native-proof/session"), root, Camera(), Location(), { true }, { true }, {1000}, { valid })
        try {
            s.capture(Permission.Granted); s.awaitOperations()
            assertFalse(s.state.value.captured); assertEquals(ProofProblem.CaptureFailed, s.state.value.problem)
            assertEquals(0, root.walkTopDown().filter { it.isFile }.count())
            s.locate(Permission.Granted); s.awaitOperations(); s.confirm(); s.awaitOperations()
            assertFalse(s.state.value.confirmed)
            valid = true; s.capture(Permission.Granted); s.awaitOperations(); assertTrue(s.state.value.captured)
        } finally { s.close(); root.deleteRecursively() }
    }
    @Test fun deletedOrCorruptImageCannotBeConfirmedBeforeOrDuringRevalidation() = runBlocking {
        for (during in listOf(false, true)) for (deleted in listOf(false, true)) {
            val root = Files.createTempDirectory("proof-changed").toFile()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val s = ProofSession(this, File(root,"native-proof/session"), root, Camera(), Location(), { true }, {
                entered.complete(Unit); release.await(); true
            }, {1000}, { it.readBytes().contentEquals(byteArrayOf(1,2)) })
            try {
                s.capture(Permission.Granted); s.awaitOperations(); s.locate(Permission.Granted); s.awaitOperations()
                val file = s.previewFile()!!
                if (during) { s.confirm(); entered.await() }
                if (deleted) file.delete() else file.writeBytes(byteArrayOf(0))
                if (!during) s.confirm()
                release.complete(Unit); s.awaitOperations()
                assertFalse(s.state.value.confirmed); assertFalse(s.state.value.captured)
                assertNull(s.state.value.accuracy); assertEquals(ProofProblem.CaptureFailed, s.state.value.problem)
                assertEquals(0, root.walkTopDown().filter { it.isFile }.count())
            } finally { release.complete(Unit); s.close(); root.deleteRecursively() }
        }
    }
    @Test fun latePreviewFailureCannotInvalidateANewerCapture() = runBlocking {
        check { s, _, _, _ ->
            s.capture(Permission.Granted); s.awaitOperations(); val old = s.previewFile()!!
            s.retake(); s.capture(Permission.Granted); s.awaitOperations(); val newer = s.previewFile()!!
            s.rejectImage(old); assertSame(newer, s.previewFile()); assertTrue(s.state.value.captured)
            s.rejectImage(newer); assertFalse(s.state.value.captured); assertFalse(newer.exists())
            s.capture(Permission.Granted); s.awaitOperations(); assertTrue(s.state.value.captured)
        }
    }
    @Test fun duplicateConfirmUsesOneRevalidationAndWaitsForCompletion() = runBlocking {
        val root = Files.createTempDirectory("proof-double-confirm").toFile()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val s = ProofSession(this, File(root,"native-proof/session"), root, Camera(), Location(), {true}, {
            calls++; entered.complete(Unit); release.await(); true
        }, {1000}, {true})
        try {
            s.capture(Permission.Granted); s.awaitOperations(); s.locate(Permission.Granted); s.awaitOperations()
            s.confirm(); entered.await(); s.confirm(); release.complete(Unit); s.awaitOperations()
            assertEquals(1, calls); assertTrue(s.state.value.confirmed)
            assertEquals(0, root.walkTopDown().filter {it.isFile}.count())
        } finally { release.complete(Unit); s.close(); root.deleteRecursively() }
    }
    @Test fun lateCameraAndGpsCallbacksAfterEveryWithdrawalAwaitCallerCleanup() = runBlocking {
        for (operation in listOf("camera", "gps")) for (reason in listOf("dismissal", "logout", "replacement", "revocation")) {
            val root = Files.createTempDirectory("proof-withdrawal").toFile()
            var authorized = true
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val c = Camera(); val l = Location()
            c.action = { file -> entered.complete(Unit); withContext(NonCancellable) {
                release.await(); file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1,2))
            } }
            l.action = { entered.complete(Unit); withContext(NonCancellable) { release.await(); fix() } }
            val s = ProofSession(this, File(root,"native-proof/session"), root, c, l, {authorized}, {true}, {1000}, {true})
            try {
                if (operation == "camera") s.capture(Permission.Granted) else s.locate(Permission.Granted)
                entered.await(); if (reason != "dismissal") authorized = false
                s.close(); release.complete(Unit); s.awaitOperations()
                assertTrue("$reason/$operation", s.state.value.closed); assertNull(s.previewFile())
                assertNull(s.state.value.accuracy); assertFalse(s.state.value.captured)
                assertEquals(0, root.walkTopDown().filter {it.isFile}.count())
            } finally { release.complete(Unit); s.close(); root.deleteRecursively() }
        }
    }

    @Test fun imageValidatorProgrammingFailureIsNotSwallowedByCapture() = runBlocking {
        val root = Files.createTempDirectory("proof-programming").toFile()
        val failure = CompletableDeferred<Throwable>()
        val parent = CoroutineScope(coroutineContext + CoroutineExceptionHandler { _, error -> failure.complete(error) })
        val s = ProofSession(parent, File(root,"native-proof/session"), root, Camera(), Location(), {true}, {true}, {1000}, {
            throw IllegalStateException("synthetic validator programming failure")
        })
        try {
            s.capture(Permission.Granted); s.awaitOperations()
            assertTrue(failure.await() is IllegalStateException)
            assertFalse(s.state.value.captured); assertFalse(s.state.value.confirmed)
            assertEquals(0, root.walkTopDown().filter {it.isFile}.count())
        } finally { s.close(); root.deleteRecursively() }
    }

    @Test fun logoutUnlinksSessionSymlinkAndPreservesOtherStudentAndAppFiles() = runBlocking {
        val cache = Files.createTempDirectory("proof-session-links").toFile()
        val directory = File(cache,"native-proof/student-a").apply { mkdirs() }
        val other = File(cache,"student-b/keep.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        val s = ProofSession(this,directory,cache,Camera(),Location(),{true},{true},{1000},{true})
        try {
            s.capture(Permission.Granted); s.awaitOperations()
            Files.createSymbolicLink(File(directory,"unexpected-directory").toPath(),other.parentFile!!.toPath())
            s.close(); s.awaitOperations()
            assertTrue(s.state.value.closed); assertFalse(s.allowed()); assertFalse(directory.exists())
            assertEquals("keep",other.readText())
        } finally { s.close(); cache.deleteRecursively() }
    }
    @Test fun invalidOwnershipOrAncestorLinkCleanupFailureCannotAuthorizeProof() = runBlocking {
        val cache = Files.createTempDirectory("proof-invalid-owner").toFile()
        val other = Files.createTempDirectory("proof-private-other").toFile()
        try {
            val sentinel = File(other,"keep.txt").apply { writeText("keep") }
            val outside = ProofSession(this,other,cache,Camera(),Location(),{true},{true},{1000},{true})
            outside.capture(Permission.Granted); outside.awaitOperations()
            assertTrue(outside.state.value.closed); assertFalse(outside.allowed())
            assertEquals(ProofProblem.CleanupFailed,outside.state.value.problem); assertEquals("keep",sentinel.readText())
            Files.createSymbolicLink(File(cache,"native-proof").toPath(),other.toPath())
            val linked = ProofSession(this,File(cache,"native-proof/session"),cache,Camera(),Location(),{true},{true},{1000},{true})
            linked.confirm(); linked.awaitOperations()
            assertTrue(linked.state.value.closed); assertFalse(linked.state.value.confirmed)
            assertEquals(ProofProblem.CleanupFailed,linked.state.value.problem); assertEquals("keep",sentinel.readText())
        } finally { cleanupProofCache(cache); cache.deleteRecursively(); other.deleteRecursively() }
    }

}
