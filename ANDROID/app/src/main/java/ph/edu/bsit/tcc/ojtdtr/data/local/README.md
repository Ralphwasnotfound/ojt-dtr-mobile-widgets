# Local authentication data boundary

U7.5B's SDK-compatible storage implementations live in the centralized `auth`
package: `EncryptedAuthStorage`, `SecureSessionManager` and `SecurePkceCache`.
They own encrypted files in private credential-protected `noBackupFilesDir` and
use Android Keystore. No raw tokens, roles or verifiers are stored in settings /
SharedPreferences, external storage or navigation state. No local profile cache
is used as authorization authority.

Non-sensitive preferences can later use DataStore; it is not encryption. Keep
future caches separate from session material and server authorization. Registration
and production profile writes remain outside this native phase. See the package's
`security/README.md` and root README for storage, logout and failure semantics.
