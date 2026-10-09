package ph.edu.bsit.tcc.ojtdtr.proof

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.UUID
import ph.edu.bsit.tcc.ojtdtr.auth.AuthCoordinator

@Composable
internal fun ProofScreen(authentication: AuthCoordinator, onBack: () -> Unit) {
    val context = LocalContext.current
    if ((context.applicationContext as ph.edu.bsit.tcc.ojtdtr.DtrApplication).proofStorageReady.not()) {
        Column { Text("Private proof cleanup unavailable. Restart the app before trying again."); Button(onClick = onBack) { Text("Back") } }
        return
    }
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val ticket = remember(authentication) { authentication.proofTicket() }
    if (ticket == null) { LaunchedEffect(Unit) { onBack() }; return }
    val camera = remember { NativeCamera(context.applicationContext) }
    val location = remember { NativeLocation(context.applicationContext) }
    val session = remember(ticket) {
        ProofSession(scope, File(context.cacheDir, "native-proof/${UUID.randomUUID()}"), camera, location,
            { authentication.currentProof(ticket) }, { authentication.revalidateProof(ticket) }, SystemClock::elapsedRealtime)
    }
    val state by session.state.collectAsState()
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var cameraPermission by remember { mutableStateOf(Permission.Denied) }
    var locationPermission by remember { mutableStateOf(Permission.Denied) }
    fun granted(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    fun result(names: List<String>): Permission {
        if (names.any(::granted)) return Permission.Granted
        val activity = context as? Activity
        return if (activity != null && names.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
            Permission.SettingsRequired else Permission.Denied
    }
    val cameraRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraPermission = result(listOf(Manifest.permission.CAMERA)); session.permission(cameraPermission)
    }
    val locationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationPermission = result(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        session.permission(locationPermission)
    }
    DisposableEffect(session, lifecycle) {
        val window = (context as? Activity)?.window
        val previouslySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val unregister = authentication.registerProofCancellation(ticket, session::close)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) session.close()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            unregister(); lifecycle.lifecycle.removeObserver(observer); session.close()
            if (!previouslySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(Unit) {
        cameraPermission = if (granted(Manifest.permission.CAMERA)) Permission.Granted else Permission.Denied
        locationPermission = if (granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)) Permission.Granted else Permission.Denied
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device-only selfie and location preview", style = MaterialTheme.typography.titleLarge)
        Text("Nothing is uploaded. Attendance is not recorded. Temporary data is deleted when you leave.")
        if (cameraPermission == Permission.Granted && !state.captured && !state.confirmed && !state.closed) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(240.dp))
            DisposableEffect(camera, lifecycle) {
                camera.bind(previewView, lifecycle, session::allowed, session::cameraUnavailable)
                onDispose { camera.stop() }
            }
            Button(onClick = { session.capture(cameraPermission) }, enabled = !state.busy) { Text("Capture") }
        } else if (state.captured) {
            val bitmap = remember(state.captured, session) {
                session.previewFile()?.let { file ->
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (bounds.outWidth / inSampleSize > 800 || bounds.outHeight / inSampleSize > 800) inSampleSize *= 2
                    }
                    android.graphics.BitmapFactory.decodeFile(file.path, options)?.let { image ->
                        val exif = androidx.exifinterface.media.ExifInterface(file.path)
                        val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
                        val matrix = android.graphics.Matrix()
                        if (orientation in listOf(2, 4, 5, 7)) matrix.postScale(-1f, 1f)
                        val angle = when (orientation) { 3, 4 -> 180f; 5, 6 -> 90f; 7, 8 -> 270f; else -> 0f }
                        matrix.postRotate(angle)
                        android.graphics.Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
                    }
                }
            }
            bitmap?.let { Image(it.asImageBitmap(), "Captured selfie", Modifier.fillMaxWidth().height(240.dp)) }
            Text("Selfie captured in private temporary storage")
        }
        if (cameraPermission != Permission.Granted) Button(onClick = { cameraRequest.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        if (locationPermission != Permission.Granted) Button(onClick = {
            locationRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text("Allow foreground location") }
        Text("A location fix expires after 30 seconds. Acquire again before confirmation if needed.")
        Button(onClick = { session.locate(locationPermission) }, enabled = locationPermission == Permission.Granted && !state.busy) { Text("Acquire location") }
        Text(if (state.accuracy == null) "Location unavailable" else
            "Location acquired: ${state.accuracy} m accuracy · ${if (state.precise == true) "Precise" else "Approximate"} permission")
        if (state.captured) Button(onClick = session::retake, enabled = !state.busy) { Text("Retake") }
        Button(onClick = session::confirm, enabled = state.captured && state.accuracy != null && !state.busy) { Text("Confirm device-only preview") }
        if (state.busy) Text("Working…")
        if (state.confirmed) Text("Device-only preview confirmed. Temporary proof deleted. No attendance recorded.")
        state.problem?.let { Text(when (it) {
            ProofProblem.PermissionDenied -> "Permission denied. You can try again."
            ProofProblem.SettingsRequired -> "Permission is blocked. You can enable it in app settings."
            ProofProblem.CameraUnavailable -> "Front camera unavailable or disconnected. Reopen this screen to retry."
            ProofProblem.CaptureFailed -> "Capture failed. Try again."
            ProofProblem.LocationUnavailable -> "Location unavailable. Check foreground permission and device location services."
            ProofProblem.InvalidLocation -> "Location fix is invalid or too old. Acquire a new fix."
            ProofProblem.Timeout -> "Request timed out. Try again."
            ProofProblem.Authorization -> "Authorization could not be verified. Reopen the companion."
            ProofProblem.CleanupFailed -> "Temporary data cleanup failed. Close this screen and retry the app later."
        }) }
        if (state.problem == ProofProblem.SettingsRequired) Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Text("Open app settings") }
        Button(onClick = session::close) { Text("Back to companion") }
        Button(onClick = authentication::logout) { Text("Sign out and discard proof") }
    }
}
