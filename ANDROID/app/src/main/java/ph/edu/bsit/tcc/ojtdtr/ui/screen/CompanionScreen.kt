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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ph.edu.bsit.tcc.ojtdtr.attendance.AttendanceState
import ph.edu.bsit.tcc.ojtdtr.R
import ph.edu.bsit.tcc.ojtdtr.auth.AccountState
import ph.edu.bsit.tcc.ojtdtr.auth.StudentIdentity
import ph.edu.bsit.tcc.ojtdtr.navigation.WebDestination

/** Approved content has no navigable route; the current trusted account state selects it. */
@Composable
fun CompanionScreen(account: AccountState, identity: StudentIdentity?, configured: Boolean,
    onGoogle: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onSignOut: () -> Unit,
    attendance: AttendanceState = AttendanceState.AccountChanged, onAttendanceRefresh: () -> Unit = {},
    onRefresh: () -> Unit, onWeb: (WebDestination) -> Unit, onFoundation: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.institution), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.system_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.companion_label))
            when (account) {
                AccountState.StudentApproved -> {
                    Text(stringResource(R.string.student_companion), style = MaterialTheme.typography.headlineSmall)
                    identity?.fullName?.let { Text(it) }
                    identity?.studentId?.let { Text(stringResource(R.string.student_identity, it)) }
                    if (identity?.fullName == null && identity?.studentId == null)
                        Text(stringResource(R.string.identity_unavailable))
                    Text(stringResource(R.string.today_dtr), style = MaterialTheme.typography.titleMedium)
                    AttendanceSection(attendance, onAttendanceRefresh)
                }
                AccountState.AdminApproved -> {
                    Text(stringResource(R.string.admin_companion), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.admin_web_only))
                }
                AccountState.Pending -> Text(stringResource(R.string.pending_explanation))
                AccountState.Rejected -> Text(stringResource(R.string.rejected_explanation))
                AccountState.RegistrationRequired -> {
                    Text(stringResource(R.string.registration_explanation))
                    Button(onClick = { onWeb(WebDestination.Registration) }) {
                        Text(stringResource(R.string.open_registration))
                    }
                    Text(stringResource(R.string.browser_independent))
                }
                else -> Unit
            }
            AuthenticationPanel(account, configured, onGoogle, onCancel, onRetry, onSignOut)
            if (account in listOf(AccountState.StudentApproved, AccountState.AdminApproved, AccountState.Pending)) {
                Button(onClick = { onWeb(WebDestination.FullSystem) }) { Text(stringResource(R.string.open_full_system)) }
                Text(stringResource(R.string.browser_independent))
            }
            if (account in listOf(AccountState.StudentApproved, AccountState.AdminApproved,
                    AccountState.Pending, AccountState.RegistrationRequired)) {
                Button(onClick = onRefresh) { Text(stringResource(R.string.refresh_account)) }
            }
            Button(onClick = onFoundation) { Text(stringResource(R.string.companion_about)) }
        }
    }
}
