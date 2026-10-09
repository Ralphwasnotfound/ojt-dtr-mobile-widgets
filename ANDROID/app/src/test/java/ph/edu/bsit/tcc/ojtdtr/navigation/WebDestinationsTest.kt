package ph.edu.bsit.tcc.ojtdtr.navigation

import java.net.URI
import org.junit.Assert.*
import org.junit.Test
import ph.edu.bsit.tcc.ojtdtr.auth.AccountState
import ph.edu.bsit.tcc.ojtdtr.auth.Failure

class WebDestinationsTest {
    private val states = listOf(AccountState.RestoringSession, AccountState.SignedOut,
        AccountState.Authenticating, AccountState.LoadingProfile, AccountState.StudentApproved,
        AccountState.AdminApproved, AccountState.Pending, AccountState.Rejected,
        AccountState.RegistrationRequired, AccountState.AccessUnavailable(Failure.ProfileInvalid),
        AccountState.AccessUnavailable(Failure.Session, true))

    @Test fun registrationIsOnlyTheExistingSignupDestinationForProvenMissingProfile() {
        for (state in states) assertEquals(
            if (state == AccountState.RegistrationRequired) "https://bsit-tcc-ojt-dtr.vercel.app/signup" else null,
            WebDestinations.urlFor(state, WebDestination.Registration))
    }

    @Test fun fullSystemIsGatedAndEveryHandoffIsFixedHttpsWithoutSessionMaterial() {
        for (state in states) {
            val allowed = state in listOf(AccountState.StudentApproved, AccountState.AdminApproved, AccountState.Pending)
            assertEquals(if (allowed) "https://bsit-tcc-ojt-dtr.vercel.app/" else null,
                WebDestinations.urlFor(state, WebDestination.FullSystem))
            for (destination in WebDestination.entries) WebDestinations.urlFor(state, destination)?.let {
                val uri = URI(it)
                assertEquals("https", uri.scheme); assertEquals("bsit-tcc-ojt-dtr.vercel.app", uri.host)
                assertNull(uri.userInfo); assertNull(uri.query); assertNull(uri.fragment); assertEquals(-1, uri.port)
                assertTrue(uri.path in listOf("/", "/signup"))
            }
        }
    }
}
