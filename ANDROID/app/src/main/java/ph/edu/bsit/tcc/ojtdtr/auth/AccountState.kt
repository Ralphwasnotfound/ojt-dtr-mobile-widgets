package ph.edu.bsit.tcc.ojtdtr.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

// No tokens, browser URLs, or cached authorization are exposed to presentation/navigation.
sealed interface AccountState {
    data object RestoringSession : AccountState
    data object SignedOut : AccountState
    data object Authenticating : AccountState
    data object LoadingProfile : AccountState
    data object StudentApproved : AccountState
    data object AdminApproved : AccountState
    data object Pending : AccountState
    data object Rejected : AccountState
    data object RegistrationRequired : AccountState
    data class AccessUnavailable(val reason: Failure, val retryable: Boolean = false) : AccountState
}

enum class Failure { Configuration, SecureStorage, Session, ProfileRead, ProfileInvalid, Callback, OAuth }

@Serializable
data class TrustedProfile(val id: String? = null, val role: String? = null, val status: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("student_id") val studentId: String? = null)

// Presentation only: server profile labels are never authorization inputs or persisted widget state.
data class StudentIdentity(val fullName: String?, val studentId: String?)

object ProfilePolicy {
    // This function receives a SUCCESSFUL query only. A query exception never means missing profile.
    fun map(userId: String, googleIdentity: Boolean, rows: List<TrustedProfile>): AccountState {
        if (userId.isBlank() || rows.size > 1) return unavailable()
        val row = rows.singleOrNull() ?: return if (googleIdentity) AccountState.RegistrationRequired else unavailable()
        if (row.id != userId) return unavailable()
        return when (row.role) {
            "student" -> when (row.status) {
                "approved" -> AccountState.StudentApproved
                "pending" -> AccountState.Pending
                "rejected" -> AccountState.Rejected
                else -> unavailable() // Future lifecycle states require an explicitly approved mapping.
            }
            "admin" -> if (row.status == "approved") AccountState.AdminApproved else unavailable()
            else -> unavailable()
        }
    }
    private fun unavailable() = AccountState.AccessUnavailable(Failure.ProfileInvalid)
}

/** Each operation owns an epoch. Logout/session replacement invalidates every older result. */
class AuthorizationEpoch {
    private var value = 0L
    @Synchronized fun advance(): Long = ++value
    @Synchronized fun isCurrent(candidate: Long): Boolean = candidate == value
}
