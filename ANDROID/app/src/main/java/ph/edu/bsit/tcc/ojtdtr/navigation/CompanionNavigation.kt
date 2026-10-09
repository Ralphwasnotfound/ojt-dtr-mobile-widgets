package ph.edu.bsit.tcc.ojtdtr.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import ph.edu.bsit.tcc.ojtdtr.auth.AuthCoordinator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ph.edu.bsit.tcc.ojtdtr.ui.screen.FoundationScreen
import ph.edu.bsit.tcc.ojtdtr.ui.screen.CompanionScreen

private enum class Destination(val route: String) {
    Welcome("welcome"),
    Foundation("foundation"),
}

@Composable
fun CompanionNavigation(authentication: AuthCoordinator, onGoogle: (String) -> Unit, onWeb: (String) -> Unit) {
    val account by authentication.state.collectAsState()
    val identity by authentication.studentIdentity.collectAsState()
    val attendance by authentication.attendance.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    DisposableEffect(lifecycle, authentication, context) {
        val owner = authentication.registerAttendanceLifecycle()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> authentication.attendanceForeground(owner, true)
                Lifecycle.Event.ON_PAUSE -> authentication.attendanceForeground(owner, false)
                else -> Unit
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { authentication.attendanceClockChanged(owner) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(observer)
        authentication.attendanceForeground(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            authentication.disposeAttendanceLifecycle(owner)
        }
    }
    val controller = rememberNavController()
    NavHost(navController = controller, startDestination = Destination.Welcome.route) {
        composable(Destination.Welcome.route) {
            CompanionScreen(account = account, identity = identity, configured = authentication.configured,
                onGoogle = { authentication.signIn(onGoogle) },
                onCancel = authentication::cancelAuthentication, onRetry = authentication::retry,
                onSignOut = authentication::logout, onRefresh = authentication::refreshProfile,
                attendance = attendance, onAttendanceRefresh = authentication::refreshAttendance,
                onWeb = { destination ->
                    WebDestinations.urlFor(authentication.state.value, destination)?.let(onWeb)
                }, onFoundation = {
                controller.navigate(Destination.Foundation.route) { launchSingleTop = true }
            })
        }
        composable(Destination.Foundation.route) {
            FoundationScreen(onBack = { controller.popBackStack() })
        }
    }
}
