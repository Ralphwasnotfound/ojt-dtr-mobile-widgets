package ph.edu.bsit.tcc.ojtdtr.proof

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NativeCamera(private val context: Context) : ProofCamera {
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var preview: Preview? = null
    private var revision = 0L
    fun bind(view: PreviewView, owner: LifecycleOwner, allowed: () -> Boolean, unavailable: () -> Unit) {
        stop()
        val generation = revision
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (revision != generation || !allowed()) return@addListener
            try {
                val active = future.get()
                if (!active.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) { unavailable(); return@addListener }
                val live = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val image = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                active.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, live, image)
                provider = active; preview = live; capture = image
            } catch (_: Exception) { stop(); unavailable() }
        }, ContextCompat.getMainExecutor(context))
    }
    override suspend fun capture(file: File): Unit = suspendCancellableCoroutine { continuation ->
        val image = capture
        if (image == null) { continuation.resumeWithException(ProofFailure(ProofProblem.CameraUnavailable)); return@suspendCancellableCoroutine }
        val generation = revision
        continuation.invokeOnCancellation { file.delete() }
        try {
            image.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (generation != revision || !continuation.isActive) {
                            file.delete()
                            if (continuation.isActive) continuation.resumeWithException(ProofFailure(ProofProblem.CaptureFailed))
                        } else continuation.resume(Unit)
                    }
                    override fun onError(error: ImageCaptureException) {
                        file.delete()
                        if (continuation.isActive) continuation.resumeWithException(ProofFailure(ProofProblem.CaptureFailed))
                    }
                })
        } catch (_: Exception) {
            file.delete()
            if (continuation.isActive) continuation.resumeWithException(ProofFailure(ProofProblem.CaptureFailed))
        }
    }
    override fun stop() {
        revision++
        val owned = listOfNotNull(preview, capture).toTypedArray()
        provider?.unbind(*owned) // Never unbind another screen's camera use cases.
        provider = null; preview = null; capture = null
    }
}
internal class NativeLocation(private val context: Context) : ProofLocation {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var cancel: (() -> Unit)? = null
    override suspend fun acquire(): Fix = suspendCancellableCoroutine { continuation ->
        stop()
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!precise && !coarse) {
            continuation.resumeWithException(ProofFailure(ProofProblem.PermissionDenied)); return@suspendCancellableCoroutine
        }
        val providers = try { listOfNotNull(if (precise) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
            .filter { manager.isProviderEnabled(it) } } catch (_: Exception) { emptyList() }
        if (providers.isEmpty()) {
            continuation.resumeWithException(ProofFailure(ProofProblem.LocationUnavailable)); return@suspendCancellableCoroutine
        }
        val callback = object : LocationListener {
            override fun onLocationChanged(value: Location) {
                if (!continuation.isActive) return
                val fix = Fix(value.latitude, value.longitude, if (value.hasAccuracy()) value.accuracy else Float.NaN,
                    value.elapsedRealtimeNanos / 1_000_000, precise)
                if (!fix.valid(SystemClock.elapsedRealtime())) {
                    continuation.resumeWithException(ProofFailure(ProofProblem.InvalidLocation))
                } else continuation.resume(fix)
                remove()
            }
            @Deprecated("Legacy platform callback") override fun onProviderDisabled(provider: String) {
                if (providers.none { manager.isProviderEnabled(it) } && continuation.isActive) {
                    continuation.resumeWithException(ProofFailure(ProofProblem.LocationUnavailable)); remove()
                }
            }
            @Deprecated("Legacy platform callback") override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Legacy platform callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callback
        cancel = { continuation.cancel() }
        continuation.invokeOnCancellation { remove() }
        try {
            providers.forEach { manager.requestLocationUpdates(it, 1000L, 0f, callback, Looper.getMainLooper()) }
        } catch (_: SecurityException) {
            remove(); if (continuation.isActive) continuation.resumeWithException(ProofFailure(ProofProblem.PermissionDenied))
        } catch (_: Exception) {
            remove(); if (continuation.isActive) continuation.resumeWithException(ProofFailure(ProofProblem.LocationUnavailable))
        }
    }
    private fun remove() {
        try { listener?.let { manager.removeUpdates(it) } } catch (_: SecurityException) { }
        listener = null; cancel = null
    }
    override fun stop() { val pending = cancel; remove(); pending?.invoke() }
}
