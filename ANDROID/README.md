# BSIT-TCC OJT DTR — Android Companion

U7.5B implements the native authentication foundation within the U7.4 Kotlin /
Compose architecture. Permanent application ID and namespace:
`ph.edu.bsit.tcc.ojtdtr`. No attendance/activity writes, registration RPCs,
dashboards, widgets or workers are implemented. U7.5C has not started.

## Toolchain and dependencies

| Component | Pinned version |
| --- | --- |
| JDK / Gradle / AGP | 17 / 8.13 / 8.13.2 |
| Kotlin, Compose compiler, serialization plugin | 2.2.21 |
| Compose BOM / UI-runtime / Material 3 | 2025.10.01 / 1.9.4 / 1.4.0 |
| Core KTX / Activity Compose / Navigation Compose | 1.17.0 / 1.11.0 / 2.9.5 |
| Glance / WorkManager (dependencies only) | 1.1.1 / 2.10.1 |
| Supabase BOM, Auth, PostgREST | 3.2.6 |
| Ktor Android engine / test-only Mock engine | 3.3.1 |
| AndroidX Browser | 1.9.0 |
| JUnit / AndroidX test runner / test JUnit extension | 4.13.2 / 1.7.0 / 1.3.0 |
| Build Tools / minSdk / compileSdk / targetSdk | 36.0.0 / 26 / 36 / 36 |

Versions live in `gradle/libs.versions.toml`; the checksum-pinned Gradle Wrapper
is used. No global Gradle or Android Studio is needed. The U7.4 toolchain is
unchanged. Supabase supplies the compatible serialization runtime transitively.

## Android-only client configuration

Supply `ANDROID_SUPABASE_URL` and `ANDROID_SUPABASE_CLIENT_KEY` as environment
variables, or as those exact property names in ignored `supabase.local.properties`
at this Android project root. Environment values take precedence. The URL must
be an HTTPS project origin; the key must be a Supabase publishable key or legacy
JWT with `role=anon`. These client identifiers are embedded in the APK and are
not server authorization secrets. Never put tokens or privileged credentials in
this file. No actual project values have been supplied or copied from the web.

The build rejects malformed/server-only keys before generating BuildConfig.
Runtime validation rejects missing/invalid configuration with a clear closed UI;
no Supabase client or authentication attempt is created in that case. Never use
service-role keys, Google client secrets, database passwords or private keys.

An authorized operator must confirm/add this exact URI in the existing Supabase
Auth redirect allowlist before live Google OAuth testing:

```
ph.edu.bsit.tcc.ojtdtr://auth/callback
```

No hosted configuration is changed by the Android build or tests. Browser OAuth
uses the existing Supabase Google provider; a separate Android Google client ID
or embedded Google secret is not introduced.

## Authentication design

`DtrApplication` owns one `AuthCoordinator`, with one Supabase client installing
only Auth and PostgREST. PKCE is explicit. SDK settings persistence and debug
logging are disabled/replaced. Native sessions stay independent of web/browser
sessions. A Custom Tab is opened only after the SDK's verifier has been persisted
encrypted and matched to its S256 challenge; `prompt=select_account` is preserved.
No WebView is used.

`SecureSessionManager` implements the SDK persistence interface using Android
Keystore AES-256-GCM and private `noBackupFilesDir` files. The whole SDK session
snapshot, including tokens and expiry metadata, is encrypted. `SecurePkceCache`
implements the verifier interface and stores one encrypted transaction with a
random identifier, creation time, challenge, verifier and consumption flag.
Transactions expire after five minutes, cannot be claimed twice, and are removed
on success, cancellation, invalid callback or failure. Interrupted exchanges are
not resumed; unclaimed browser transactions can survive process death.

MainActivity validates both cold and warm callbacks via the central coordinator.
The manifest narrows dispatch to the exact scheme/host/path; application policy
also rejects ports/user-info, fragments, duplicate/unexpected query parameters,
token delivery, and callbacks without an active transaction. An OAuth callback
alone never grants access. Unsolicited or duplicate callbacks cannot replace an
existing account. Callback data is removed from the Activity intent after capture.

Restoration reads the encrypted native session, refreshes it when near expiry,
validates its user with Supabase Auth and rereads **only its own** `public.profiles`
row, selecting `id,role,status` with `id=eq.<authenticated user id>`. No cached role
or Google user metadata authorizes the account. Failed queries stay closed and
retryable; only a successful empty query for a verified Google identity leads to
RegistrationRequired. Unsupported/malformed combinations fail closed.

| Trusted profile | Native state |
| --- | --- |
| student / approved | StudentApproved |
| admin / approved | AdminApproved |
| student / pending | Pending |
| student / rejected | Rejected |
| missing + verified Google identity | RegistrationRequired |
| unknown role/status, mismatched ID, unsupported admin status | AccessUnavailable |
| user/session failure or profile query failure | AccessUnavailable, retryable |

The application owns cancellable, serialized refresh operations rather than SDK
background auto-refresh. While the process lives, it checks expiry every minute;
refresh and retry also revalidate authorization. No worker is scheduled. Restart
always revalidates against the server. Epoch guards prevent old profile results
from authorizing after logout or session replacement. Logout immediately clears
UI authority, fences/deletes session persistence, cancels pending requests and
PKCE, attempts a bounded native LOCAL sign-out, and clears the SDK session even
if remote revocation fails. It does not log out the browser/web session.

Registration remains the existing web flow's responsibility. The native UI only
explains RegistrationRequired; it does not write profiles or call registration /
roster / reservation / approval RPCs. Future terminal OJT states can be added to
ProfilePolicy after approval; unknown states currently stay closed.

## Build and verification

Set JDK 17 `JAVA_HOME` and SDK `ANDROID_HOME`; ignored `local.properties` may hold
`sdk.dir`. Use the Wrapper:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Connected tests require only the existing `DTR_Test` emulator. They use synthetic
sessions and Ktor MockEngine, real Android Keystore, and isolated private test
directories. They do not contact the hosted project. The test-only mock engine is
not included in the application APK. Specify emulator serials for manual checks:

```bash
adb -s emulator-5554 emu avd name
adb -s emulator-5554 shell getprop sys.boot_completed
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -W -n ph.edu.bsit.tcc.ojtdtr/.MainActivity
```

Welcome → Foundation/About → on-screen Back/system Back remains the U7.4 graph.
Authentication uses a temporary panel on Welcome; no tokens or URLs enter routes
or saved UI state. Backup opt-out and legacy/modern extraction exclusions are
preserved. Cleartext traffic remains disabled. MainActivity is the only
app-defined exported component; it now handles the narrow OAuth callback as well
as launcher entry. Library permission-guarded services retain their U7.4 policy.

Read `security/README.md` under the application package for limitations and
`docs/U7.5B-verification.md` for the final verification record. The workspace is
not a usable Git repository; none was initialized, committed or pushed.
