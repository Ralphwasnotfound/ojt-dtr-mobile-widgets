package ph.edu.bsit.tcc.ojtdtr.proof

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Permission { Granted, Denied, SettingsRequired }
internal enum class ProofProblem { PermissionDenied, SettingsRequired, CameraUnavailable, CaptureFailed,
    LocationUnavailable, InvalidLocation, Timeout, Authorization, CleanupFailed }
internal data class Fix(val latitude: Double, val longitude: Double, val accuracy: Float,
    val elapsedMillis: Long, val precise: Boolean) {
    fun valid(now: Long): Boolean = latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 && accuracy.isFinite() && accuracy > 0 &&
        elapsedMillis >= 0 && now >= elapsedMillis && now - elapsedMillis <= 30_000
}
internal data class ProofState(val captured: Boolean = false, val busy: Boolean = false,
    val accuracy: Float? = null, val precise: Boolean? = null, val confirmed: Boolean = false,
    val problem: ProofProblem? = null, val closed: Boolean = false)
internal interface ProofCamera { suspend fun capture(file: File); fun stop() }
internal interface ProofLocation { suspend fun acquire(): Fix; fun stop() }
internal class ProofFailure(val problem: ProofProblem) : Exception()

/** Screen-owned Main-dispatcher state. No SDK, upload, mutation, coordinate UI or durable cache. */
internal class ProofSession(
    parent: CoroutineScope, private val directory: File, private val camera: ProofCamera,
    private val location: ProofLocation, private val current: () -> Boolean,
    private val revalidate: suspend () -> Boolean, private val now: () -> Long,
    private val deadlineMillis: Long = 20_000,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(ProofState())
    val state = mutable.asStateFlow()
    private var photo: File? = null
    private var fix: Fix? = null
    private var revision = 0L
    private var work: Job? = null
    private var closed = false
    fun allowed(): Boolean = !closed && current()
    fun previewFile(): File? = if (allowed()) photo else null
    fun permission(value: Permission) {
        if (!allowed()) { close(); return }
        if (value != Permission.Granted) mutable.value = mutable.value.copy(problem =
            if (value == Permission.SettingsRequired) ProofProblem.SettingsRequired else ProofProblem.PermissionDenied)
        else mutable.value = mutable.value.copy(problem = null)
    }
    fun cameraUnavailable() { if (allowed()) mutable.value = mutable.value.copy(problem = ProofProblem.CameraUnavailable) }
    fun capture(permission: Permission) {
        if (!allowed()) { close(); return }; permission(permission)
        if (permission != Permission.Granted || work?.isActive == true || photo != null) return
        val generation = ++revision
        mutable.value = mutable.value.copy(busy = true, problem = null, confirmed = false)
        work = scope.launch {
            var output: File? = null
            try {
                check(directory.mkdirs() || directory.isDirectory)
                output = File(directory, "${UUID.randomUUID()}.jpg")
                withTimeout(deadlineMillis) { camera.capture(output) }
                if (!allowed() || revision != generation) return@launch
                check(output.isFile && output.length() > 0)
                photo = output; output = null
                mutable.value = mutable.value.copy(captured = true)
            } catch (_: TimeoutCancellationException) { fail(generation, ProofProblem.Timeout) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ProofFailure) { fail(generation, failure.problem) }
            catch (_: Exception) { fail(generation, ProofProblem.CaptureFailed) }
            finally {
                output?.let { delete(it) }
                if (revision == generation && !closed) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
    fun locate(permission: Permission) {
        if (!allowed()) { close(); return }; permission(permission)
        if (permission != Permission.Granted || work?.isActive == true) return
        fix = null
        val generation = ++revision
        mutable.value = mutable.value.copy(busy = true, accuracy = null, precise = null, confirmed = false, problem = null)
        work = scope.launch {
            try {
                val result = withTimeout(deadlineMillis) { location.acquire() }
                if (!allowed() || revision != generation) return@launch
                if (!result.valid(now())) throw ProofFailure(ProofProblem.InvalidLocation)
                fix = result
                mutable.value = mutable.value.copy(accuracy = result.accuracy, precise = result.precise)
            } catch (_: TimeoutCancellationException) { fail(generation, ProofProblem.Timeout) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ProofFailure) { fail(generation, failure.problem) }
            catch (_: Exception) { fail(generation, ProofProblem.LocationUnavailable) }
            finally {
                location.stop()
                if (revision == generation && !closed) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
    fun retake() {
        if (!allowed()) { close(); return }
        revision++; work?.cancel(); camera.stop(); location.stop()
        photo?.let { delete(it) }; photo = null; fix = null
        mutable.value = ProofState(problem = mutable.value.problem?.takeIf { it == ProofProblem.CleanupFailed })
    }
    fun confirm() {
        if (!allowed()) { close(); return }
        if (work?.isActive == true || photo?.isFile != true || fix?.valid(now()) != true) {
            mutable.value = mutable.value.copy(problem = ProofProblem.LocationUnavailable); return
        }
        val generation = ++revision
        mutable.value = mutable.value.copy(busy = true, problem = null)
        work = scope.launch {
            try {
                val valid = withTimeout(deadlineMillis) { revalidate() }
                if (!valid || !allowed()) { close(); return@launch }
                if (revision != generation) return@launch
                if (fix?.valid(now()) != true) { fail(generation, ProofProblem.InvalidLocation); return@launch }
                photo?.let { delete(it) }; photo = null; fix = null
                camera.stop(); location.stop()
                if (mutable.value.problem != ProofProblem.CleanupFailed) mutable.value = ProofState(confirmed = true)
            } catch (_: TimeoutCancellationException) { fail(generation, ProofProblem.Timeout) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(generation, ProofProblem.Authorization) }
            finally { if (!closed && revision == generation) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private fun fail(generation: Long, problem: ProofProblem) {
        if (!allowed()) { close(); return }
        if (revision == generation) mutable.value = mutable.value.copy(problem = problem)
    }
    private fun delete(file: File) {
        try { if (file.exists() && !file.delete()) mutable.value = mutable.value.copy(problem = ProofProblem.CleanupFailed) }
        catch (_: Exception) { mutable.value = mutable.value.copy(problem = ProofProblem.CleanupFailed) }
    }
    fun close() {
        if (closed) return
        closed = true; revision++; work?.cancel(); job.cancel(); photo = null; fix = null
        mutable.value = ProofState(closed = true)
        try { camera.stop() } catch (_: Exception) { }
        try { location.stop() } catch (_: Exception) { }
        try { if (directory.exists() && !directory.deleteRecursively()) mutable.value = mutable.value.copy(problem = ProofProblem.CleanupFailed) }
        catch (_: Exception) { mutable.value = mutable.value.copy(problem = ProofProblem.CleanupFailed) }
    }
}
