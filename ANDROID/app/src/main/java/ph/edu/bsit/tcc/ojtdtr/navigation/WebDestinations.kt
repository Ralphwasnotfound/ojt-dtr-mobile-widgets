package ph.edu.bsit.tcc.ojtdtr.navigation

import ph.edu.bsit.tcc.ojtdtr.auth.AccountState

enum class WebDestination { FullSystem, Registration }

/** Fixed destinations from U7.5A and the existing Vue router. No session/identity input. */
object WebDestinations {
    private const val ORIGIN = "https://bsit-tcc-ojt-dtr.vercel.app"

    fun urlFor(account: AccountState, destination: WebDestination): String? = when (destination) {
        WebDestination.Registration -> if (account == AccountState.RegistrationRequired) "$ORIGIN/signup" else null
        WebDestination.FullSystem -> if (account in listOf(AccountState.StudentApproved,
            AccountState.AdminApproved, AccountState.Pending)) "$ORIGIN/" else null
    }
}
