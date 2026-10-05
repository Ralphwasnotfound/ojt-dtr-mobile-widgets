package ph.edu.bsit.tcc.ojtdtr.auth

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AuthPolicyTest {
    @Test fun `only supported trusted role and status combinations authorize`() {
        val roles = listOf("student", "admin", "owner", "", null)
        val statuses = listOf("approved", "pending", "rejected", "completed", "archived", "", null)
        for (role in roles) for (status in statuses) {
            val actual = ProfilePolicy.map("user", true, listOf(TrustedProfile("user", role, status)))
            val expected = when {
                role == "student" && status == "approved" -> AccountState.StudentApproved
                role == "admin" && status == "approved" -> AccountState.AdminApproved
                role == "student" && status == "pending" -> AccountState.Pending
                role == "student" && status == "rejected" -> AccountState.Rejected
                else -> AccountState.AccessUnavailable(Failure.ProfileInvalid)
            }
            assertEquals(expected, actual)
        }
    }
    @Test fun `missing profile is registration only with verified Google identity`() {
        assertEquals(AccountState.RegistrationRequired, ProfilePolicy.map("user", true, emptyList()))
        assertTrue(ProfilePolicy.map("user", false, emptyList()) is AccountState.AccessUnavailable)
        assertTrue(ProfilePolicy.map("", true, emptyList()) is AccountState.AccessUnavailable)
        val failure: AccountState = AccountState.AccessUnavailable(Failure.ProfileRead, true)
        assertNotEquals(AccountState.RegistrationRequired, failure)
    }
    @Test fun `wrong account missing fields and duplicate rows fail closed`() {
        for (rows in listOf(listOf(TrustedProfile("other", "student", "approved")),
            listOf(TrustedProfile()), listOf(TrustedProfile("user", "student", "approved"), TrustedProfile()))) {
            assertTrue(ProfilePolicy.map("user", true, rows) is AccountState.AccessUnavailable)
        }
    }
    @Test fun `logout and replacement invalidate old asynchronous results`() {
        val gate = AuthorizationEpoch()
        val request = gate.advance()
        assertTrue(gate.isCurrent(request))
        val logout = gate.advance()
        assertFalse(gate.isCurrent(request))
        val replacement = gate.advance()
        assertFalse(gate.isCurrent(request)); assertFalse(gate.isCurrent(logout)); assertTrue(gate.isCurrent(replacement))
    }
    @Test fun `callback accepts only exact PKCE code or provider cancellation`() {
        val code = CallbackPolicy.parse("${CallbackPolicy.URI_VALUE}?code=valid-code_123") as CallbackPolicy.Result.Code
        assertEquals("valid-code_123", code.value)
        assertEquals(CallbackPolicy.Result.ProviderError, CallbackPolicy.parse("${CallbackPolicy.URI_VALUE}?error=access_denied&error_description=cancelled"))
    }
    @Test fun `callback rejects alternate components fragments ambiguous queries and token delivery`() {
        val invalid = listOf(
            "other://auth/callback?code=valid-code", "ph.edu.bsit.tcc.ojtdtr://other/callback?code=valid-code",
            "ph.edu.bsit.tcc.ojtdtr://auth/other?code=valid-code", "ph.edu.bsit.tcc.ojtdtr://auth/callback/?code=valid-code",
            "ph.edu.bsit.tcc.ojtdtr://auth:123/callback?code=valid-code", "ph.edu.bsit.tcc.ojtdtr://user@auth/callback?code=valid-code",
            "${CallbackPolicy.URI_VALUE}?code=valid-code#access_token=fake",
            "${CallbackPolicy.URI_VALUE}?code=one-valid&code=two-valid", "${CallbackPolicy.URI_VALUE}?code=valid-code&refresh_token=fake",
            "${CallbackPolicy.URI_VALUE}?code=valid-code&error=bad", "${CallbackPolicy.URI_VALUE}?code=%ZZ",
            "${CallbackPolicy.URI_VALUE}?code=", "${CallbackPolicy.URI_VALUE}?access_token=fake", CallbackPolicy.URI_VALUE,
            "${CallbackPolicy.URI_VALUE}?%63ode=one-valid&code=two-valid", "${CallbackPolicy.URI_VALUE}?code=valid-code&state=unexpected",
            "${CallbackPolicy.URI_VALUE}?code=valid%0Acode", "${CallbackPolicy.URI_VALUE}?error=&error_code=bad"
        )
        invalid.forEach { assertEquals(CallbackPolicy.Result.Invalid, CallbackPolicy.parse(it)) }
    }
    @Test fun `OAuth transaction expires and backwards clock cannot extend it`() {
        assertTrue(CallbackPolicy.fresh(1000, 1000))
        assertTrue(CallbackPolicy.fresh(1000, 1000 + CallbackPolicy.TTL_MILLIS - 1))
        assertFalse(CallbackPolicy.fresh(1000, 1000 + CallbackPolicy.TTL_MILLIS))
        assertFalse(CallbackPolicy.fresh(1000, 999))
    }
    @Test fun `only HTTPS project origins and client safe key types are accepted`() {
        val publishable = "sb_publishable_" + "a".repeat(32)
        assertNotNull(ClientConfiguration.parse("https://example.supabase.co", publishable))
        fun jwt(role: String) = "e30." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"role\":\"$role\"}".toByteArray()) + ".signature"
        assertNotNull(ClientConfiguration.parse("https://example.supabase.co/", jwt("anon")))
        for (key in listOf("sb_secret_" + "a".repeat(32), jwt("service_role"), jwt("authenticated"), "", "password")) {
            assertNull(ClientConfiguration.parse("https://example.supabase.co", key))
        }
        for (url in listOf("", "http://example.supabase.co", "https://example.supabase.co/auth/v1", "https://example.supabase.co?key=x",
            "https://user@example.supabase.co", "https://example.supabase.co#x", "https://example.supabase.co:8080")) {
            assertNull(ClientConfiguration.parse(url, publishable))
        }
    }
}
