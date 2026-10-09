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
        val session = ProofSession(this, File(root,"session"), camera, location, { true }, { true }, { 1000 })
        try { block(session, camera, location, root) } finally { session.close(); root.deleteRecursively() }
    }
    @Test fun cameraGrantedCapturesAndRetakeDeletesPrivateFile() = runBlocking {
        check { s, _, _, root ->
            s.capture(Permission.Granted); yield(); assertTrue(s.state.value.captured)
            assertEquals(1, root.walkTopDown().filter { it.isFile }.count())
            s.retake(); assertFalse(s.state.value.captured); assertEquals(0,root.walkTopDown().filter { it.isFile }.count())
        }
    }
    @Test fun cameraDeniedDoesNotCapture() = runBlocking {
        check { s, _, _, root -> s.capture(Permission.Denied); yield(); assertEquals(ProofProblem.PermissionDenied,s.state.value.problem); assertEquals(0,root.walkTopDown().filter { it.isFile }.count()) }
    }
    @Test fun permanentDenialHasSettingsState() = runBlocking {
        check { s, _, _, _ -> s.capture(Permission.SettingsRequired); assertEquals(ProofProblem.SettingsRequired,s.state.value.problem) }
    }
    @Test fun unavailableCameraFailsClosed() = runBlocking {
        check { s, c, _, _ -> c.action = { throw ProofFailure(ProofProblem.CameraUnavailable) }; s.capture(Permission.Granted); yield(); assertFalse(s.state.value.captured); assertEquals(ProofProblem.CameraUnavailable,s.state.value.problem) }
    }
    @Test fun partialCaptureFailureDeletesOutput() = runBlocking {
        check { s, c, _, root -> c.action = { it.writeBytes(byteArrayOf(1)); throw java.io.IOException() }; s.capture(Permission.Granted); yield(); assertEquals(ProofProblem.CaptureFailed,s.state.value.problem); assertEquals(0,root.walkTopDown().filter { it.isFile }.count()) }
    }
    @Test fun confirmRevalidatesAndDeletesAllProofWithoutUpload() = runBlocking {
        check { s, _, _, root -> s.capture(Permission.Granted); yield(); s.locate(Permission.Granted); yield(); s.confirm(); yield(); assertTrue(s.state.value.confirmed); assertNull(s.state.value.accuracy); assertEquals(0,root.walkTopDown().filter { it.isFile }.count()) }
    }
    @Test fun preciseLocationPublishesAccuracyOnly() = runBlocking {
        check { s, _, _, _ -> s.locate(Permission.Granted); yield(); assertEquals(8f,s.state.value.accuracy); assertEquals(true,s.state.value.precise) }
    }
    @Test fun approximateLocationIsAcceptedAndLabelled() = runBlocking {
        check { s, _, l, _ -> l.action = { fix(false) }; s.locate(Permission.Granted); yield(); assertEquals(false,s.state.value.precise) }
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
        check { s, _, l, _ -> l.action = { fix().copy(accuracy=Float.NaN) }; s.locate(Permission.Granted); yield(); assertNull(s.state.value.accuracy); assertEquals(ProofProblem.InvalidLocation,s.state.value.problem) }
    }
    @Test fun providerDisabledIsUnavailable() = runBlocking {
        check { s, _, l, _ -> l.action = { throw ProofFailure(ProofProblem.LocationUnavailable) }; s.locate(Permission.Granted); yield(); assertEquals(ProofProblem.LocationUnavailable,s.state.value.problem) }
    }
    @Test fun locationPermissionDeniedDoesNotRequest() = runBlocking {
        check { s, _, l, _ -> l.action = { error("must not request") }; s.locate(Permission.Denied); yield(); assertEquals(ProofProblem.PermissionDenied,s.state.value.problem) }
    }
    @Test fun locationTimeoutStopsAcquisition() = runBlocking {
        val root=Files.createTempDirectory("proof-timeout").toFile(); val c=Camera(); val l=Location()
        l.action={ awaitCancellation() }
        val s=ProofSession(this,root,c,l,{true},{true},{1000},1)
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
            val s=ProofSession(this,root,c,l,{current},{true},{1000})
            try {
                s.capture(Permission.Granted); entered.await(); current=false; s.close(); release.complete(Unit); finished.await(); yield()
                assertTrue(reason,s.state.value.closed); assertFalse(s.state.value.captured)
                assertEquals(0,root.walkTopDown().filter { it.isFile }.count())
            } finally { release.complete(Unit); s.close(); root.deleteRecursively() }
        }
    }
    @Test fun revokedDuringLocationCannotPublishLateCoordinates() = runBlocking {
        var current=true; val root=Files.createTempDirectory("proof-location-race").toFile(); val c=Camera(); val l=Location()
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val done=CompletableDeferred<Unit>()
        l.action={ entered.complete(Unit); withContext(NonCancellable) { release.await(); done.complete(Unit); fix() } }
        val s=ProofSession(this,root,c,l,{current},{true},{1000})
        try { s.locate(Permission.Granted); entered.await(); current=false; s.close(); release.complete(Unit); done.await(); yield(); assertNull(s.state.value.accuracy); assertTrue(s.state.value.closed) }
        finally { release.complete(Unit); s.close(); root.deleteRecursively() }
    }
    @Test fun unauthorizedAccountsCannotStartHardware() = runBlocking {
        for (account in listOf("pending","rejected","admin","signed out")) {
            val root=Files.createTempDirectory("proof-denied").toFile(); val c=Camera(); val l=Location()
            c.action={error("unauthorized capture")}; l.action={error("unauthorized location")}
            val s=ProofSession(this,root,c,l,{false},{false},{1000})
            s.capture(Permission.Granted); s.locate(Permission.Granted); yield(); assertTrue(account,s.state.value.closed); root.deleteRecursively()
        }
    }
    @Test fun confirmationRejectionClearsSelfie() = runBlocking {
        val root=Files.createTempDirectory("proof-confirm-denied").toFile(); val s=ProofSession(this,root,Camera(),Location(),{true},{false},{1000})
        try { s.capture(Permission.Granted); yield(); s.locate(Permission.Granted); yield(); s.confirm(); yield(); assertTrue(s.state.value.closed); assertEquals(0,root.walkTopDown().filter{it.isFile}.count()) }
        finally { s.close(); root.deleteRecursively() }
    }
    @Test fun duplicateCaptureIsCoalesced() = runBlocking {
        check { s, c, _, _ ->
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var calls=0
            c.action={ calls++; entered.complete(Unit); release.await(); it.writeBytes(byteArrayOf(1)) }
            try { s.capture(Permission.Granted); entered.await(); s.capture(Permission.Granted); release.complete(Unit); yield(); assertEquals(1,calls) }
            finally { release.complete(Unit) }
        }
    }
    @Test fun uiStateContainsNoCoordinatesImagesOrCredentials() {
        assertEquals(setOf("captured","busy","accuracy","precise","confirmed","problem","closed"),
            ProofState::class.java.declaredFields.filterNot{java.lang.reflect.Modifier.isStatic(it.modifiers)}.map{it.name}.toSet())
    }
}
