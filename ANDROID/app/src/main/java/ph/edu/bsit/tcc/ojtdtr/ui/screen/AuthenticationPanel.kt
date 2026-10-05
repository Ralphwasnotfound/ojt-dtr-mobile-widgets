package ph.edu.bsit.tcc.ojtdtr.ui.screen

import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ph.edu.bsit.tcc.ojtdtr.R
import ph.edu.bsit.tcc.ojtdtr.auth.AccountState
import ph.edu.bsit.tcc.ojtdtr.auth.Failure

@Composable
fun AuthenticationPanel(state: AccountState, configured: Boolean, onGoogle: () -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, onSignOut: () -> Unit) {
    val message = when (state) {
        AccountState.RestoringSession -> R.string.auth_restoring
        AccountState.SignedOut -> R.string.auth_signed_out
        AccountState.Authenticating -> R.string.auth_authenticating
        AccountState.LoadingProfile -> R.string.auth_loading_profile
        AccountState.StudentApproved -> R.string.auth_student_approved
        AccountState.AdminApproved -> R.string.auth_admin_approved
        AccountState.Pending -> R.string.auth_pending
        AccountState.Rejected -> R.string.auth_rejected
        AccountState.RegistrationRequired -> R.string.auth_registration_required
        is AccountState.AccessUnavailable -> when (state.reason) {
            Failure.Configuration -> R.string.auth_configuration_missing
            Failure.SecureStorage -> R.string.auth_storage_failure
            Failure.Session -> R.string.auth_session_failure
            Failure.ProfileRead -> R.string.auth_profile_failure
            Failure.ProfileInvalid -> R.string.auth_access_unavailable
            Failure.Callback -> R.string.auth_callback_failure
            Failure.OAuth -> R.string.auth_oauth_failure
        }
    }
    Text(stringResource(message))
    when (state) {
        AccountState.SignedOut -> Button(onClick = onGoogle, enabled = configured) { Text(stringResource(R.string.auth_google)) }
        AccountState.RestoringSession, AccountState.LoadingProfile -> CircularProgressIndicator()
        AccountState.Authenticating -> {
            CircularProgressIndicator()
            Button(onClick = onCancel) { Text(stringResource(R.string.auth_cancel)) }
        }
        is AccountState.AccessUnavailable -> {
            if (state.reason == Failure.Configuration) {
                Button(onClick = onGoogle, enabled = false) { Text(stringResource(R.string.auth_google)) }
            } else {
                if (state.retryable) Button(onClick = onRetry) { Text(stringResource(R.string.auth_retry)) }
                Button(onClick = onSignOut) { Text(stringResource(R.string.auth_sign_out)) }
            }
        }
        else -> Button(onClick = onSignOut) { Text(stringResource(R.string.auth_sign_out)) }
    }
}
