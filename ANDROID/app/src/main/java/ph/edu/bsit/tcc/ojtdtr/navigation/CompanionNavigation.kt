package ph.edu.bsit.tcc.ojtdtr.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val controller = rememberNavController()
    NavHost(navController = controller, startDestination = Destination.Welcome.route) {
        composable(Destination.Welcome.route) {
            CompanionScreen(account = account, identity = identity, configured = authentication.configured,
                onGoogle = { authentication.signIn(onGoogle) },
                onCancel = authentication::cancelAuthentication, onRetry = authentication::retry,
                onSignOut = authentication::logout, onRefresh = authentication::refreshProfile,
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
