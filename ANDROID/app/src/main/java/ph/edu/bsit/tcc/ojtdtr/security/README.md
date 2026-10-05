# Native authentication security

U7.5B uses Android platform AES-256-GCM and an Android Keystore-generated,
non-exportable key. Each write gets a fresh platform-generated nonce. A versioned
header and per-file/package associated data prevent undetected modification and
ciphertext swapping. AtomicFile provides crash-safe replacement. Encrypted SDK
sessions and PKCE transactions live only under credential-protected private
`Context.noBackupFilesDir/native-auth`. SharedPreferences/settings are not used.
Ciphertext corruption or key loss deletes the damaged material and fails closed;
reauthentication is required. No plaintext fallback exists.

Backup remains disabled, with all legacy and modern cloud/device-transfer domains
explicitly excluded. Keystore hardware protection varies by device: neither
StrongBox nor hardware backing is assumed on an emulator. A compromised/rooted
process can read live credentials; at-rest encryption does not prevent that.
Real Samsung/One UI browser dispatch, process death and transfer behavior still
need validation. No production or OEM changes are performed here.

Auth and PostgREST are the only installed Supabase modules. Auth PKCE is explicit;
the reviewed SDK's settings persistence is replaced through both SessionManager
and CodeVerifierCache. LogLevel.NONE is explicit because the SDK can log entire
sessions at DEBUG. No HTTP logging interceptor or application token/URI logging
exists. Errors exposed to UI use fixed messages, never exception text. Tokens
are absent from routes, saved UI state and callback URLs. The test-only HTTP mock
uses synthetic material and makes no production requests.

HTTPS-only configuration and `usesCleartextTraffic=false` are retained. There is
no insecure trust manager, TLS bypass, custom CA acceptance or pinning. INTERNET
is now present for Auth/PostgREST. MainActivity's exported callback is exact
scheme/host/path, with additional strict URI and active transaction validation.
Custom schemes can be claimed by another installed app; PKCE limits intercepted
code use, but verified HTTPS App Links may be considered in a separately approved
phase with domain ownership. No App Links/domain ownership is configured here.

OAuth expires after five minutes and requires explicit cancellation if the user
closes the browser without returning a provider error. Claimed exchanges cannot
be replayed after process death. Logout clears local access and persistence even
if the remote LOCAL revocation request fails or times out; already-issued access
tokens can remain valid until server expiry. The independent browser session is
untouched. No UI state bypasses server authorization or existing RLS.

The build accepts only publishable/anon key types and rejects server-only input
before embedding it. Runtime checks repeat the boundary. Actual client values and
an externally confirmed redirect allowlist are still required for live OAuth.
This phase does not execute profile writes or privileged/registration RPCs.

References reviewed:
- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/privacy-and-security/security-config
- https://supabase.com/docs/reference/kotlin/auth-signinwithoauth
- https://github.com/supabase-community/supabase-kt/tree/3.2.6/Auth
