package ph.edu.bsit.tcc.ojtdtr.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.Duration
import ph.edu.bsit.tcc.ojtdtr.attendance.Manila
import ph.edu.bsit.tcc.ojtdtr.auth.AccountState
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
    DisposableEffect(lifecycle, authentication) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) authentication.refreshAttendance()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(account) {
        if (account == AccountState.StudentApproved) {
            authentication.refreshAttendance()
            while (true) {
                val now = Instant.now()
                val nextDay = now.atZone(Manila).toLocalDate().plusDays(1).atStartOfDay(Manila).toInstant()
                delay(Duration.between(now, nextDay).toMillis().coerceAtLeast(1000))
                authentication.refreshAttendance()
            }
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
