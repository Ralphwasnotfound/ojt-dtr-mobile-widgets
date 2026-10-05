package ph.edu.bsit.tcc.ojtdtr.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ph.edu.bsit.tcc.ojtdtr.R
import ph.edu.bsit.tcc.ojtdtr.auth.AccountState

@Composable
fun WelcomeScreen(account: AccountState, configured: Boolean, onGoogle: () -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, onSignOut: () -> Unit, onFoundation: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.institution), style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.system_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.companion_label), style = MaterialTheme.typography.bodyLarge)
            Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.development_label),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge)
            }
            AuthenticationPanel(account, configured, onGoogle, onCancel, onRetry, onSignOut)
            Button(onClick = onFoundation) { Text(stringResource(R.string.open_foundation)) }
        }
    }
}
