# PhoneServer Security Audit

Date: 2026-11-14
Scope: `com.yadaventerprise.phoneserver` (app module), including the legacy
`SimpleHttpServer`, the `/api/v1` data API, and the `adnet` advertising surface.
Method: manual source review, targeted grep sweeps, and regression tests.
No code was classified as malicious without supporting evidence.

## Summary

| ID   | Severity | Finding                                                        | Status |
|------|----------|----------------------------------------------------------------|--------|
| F-01 | High     | Unbounded request-body allocation (declared `Content-Length`)  | Fixed  |
| F-02 | High     | Unbounded HTTP header line length                              | Fixed  |
| F-03 | High     | Unbounded number of HTTP headers                               | Fixed  |
| F-04 | High     | No socket read timeout (slowloris)                             | Fixed  |
| F-05 | High     | Unbounded thread-per-connection fan-out                        | Fixed  |
| F-06 | Medium   | Stored XSS / HTML injection in the shared-files page           | Fixed  |
| F-07 | Medium   | JSON injection in the upload response                          | Fixed  |
| F-08 | Medium   | Upload filename not validated (path traversal defense-in-depth) | Fixed |
| F-09 | Medium   | "Allow remote access" setting was never enforced               | Fixed  |
| F-10 | Low      | "Show hidden files" setting was never enforced                 | Fixed  |
| F-11 | Low      | Timing-unsafe password comparison                              | Fixed  |
| R-01 | Medium   | Access password stored in plaintext SharedPreferences          | Noted  |
| R-02 | Low      | `allowBackup="true"` with empty backup rules                   | Noted  |
| R-03 | Low      | `usesCleartextTraffic="true"` (HTTP by design)                 | Noted  |
| R-04 | Low      | `file_paths.xml` exposes `root-path "/"` to FileProvider       | Noted  |
| R-05 | Low      | `/api/<custom>` endpoints are intentionally unauthenticated    | By design |
| R-06 | Low      | Legacy buffered bodies up to 64 MB per request                 | Mitigated |

No backdoors, hidden command execution, dynamic code loading, hardcoded
credentials, or SQL injection were found. The `/api/v1` and `adnet` surfaces
use parameterized SQL, ownership checks, PBKDF2 password hashing, bounded
thread pools, and CSP on the ad dashboard.

## Fixed findings

All fixes are in
`app/src/main/java/com/yadaventerprise/phoneserver/SimpleHttpServer.java`
unless stated otherwise.

### F-01 — Unbounded request-body allocation (High)

Before the fix, `readRequest` did `new byte[contentLength]` with the legacy
non-API path allowing `Integer.MAX_VALUE`. A single unauthenticated request
with `Content-Length: 2147483647` forced a ~2 GB allocation and crashed the
process.

Fix:
- Buffered bodies are capped at `MAX_BUFFERED_API_BODY = 64 MB`
  (`SimpleHttpServer.java:151`), with oversized bodies marked `bodyTooLarge`
  (`:235`) and answered with `413 Payload Too Large` (`:363`).
- `readExactly` (`:246`) now grows a `ByteArrayOutputStream` as bytes actually
  arrive instead of pre-allocating the declared length, so a client that only
  sends headers cannot reserve memory upfront.

### F-02 — Unbounded header line (High)

`readLine` accumulated into an unbounded buffer.

Fix: `MAX_HEADER_LINE = 8192` (`:56`); exceeding it throws
`IOException("header line too long")` (`:177`).

### F-03 — Unbounded number of headers (High)

Fix: `MAX_HEADERS = 100` (`:58`); exceeding it throws
`IOException("too many headers")` (`:205`).

### F-04 — No socket read timeout (High)

Fix: `CLIENT_TIMEOUT_MS = 15_000` (`:60`), applied with
`s.setSoTimeout(CLIENT_TIMEOUT_MS)` (`:310`), bounding slowloris connections.

### F-05 — Unbounded thread fan-out (High)

Fix: `MAX_CLIENTS = 64` (`:62`) with a `Semaphore clientSlots` (`:66`).
`acceptLoop` sheds load with `503 Service Unavailable` when no permit is
available (`:116`), releases on failure (`:133`), and `handleClient` releases
in `finally` (`:325`).

### F-06 — Stored XSS / HTML injection (Medium)

`serveFileListingHtml` concatenated file names into HTML text and `href`
attributes. A file named `<img src=x onerror=...>` executed script in the
browser of anyone viewing the shared-files page.

Fix: added `escapeHtml` (`:685`) and `urlEncodePath` (`:703`); the listing now
escapes the visible name and URL-encodes the link target (`:672-675`).

### F-07 — JSON injection in upload response (Medium)

The upload handler built the JSON response by string concatenation, so a
crafted filename could break out of the JSON string.

Fix: the response is built with `JSONObject` (`:650-656`).

### F-08 — Upload filename not validated (Medium)

The browser-supplied filename was used directly. This was mitigated by
`resolveSafePath`, but a filename such as `../../...` should never reach the
filesystem layer.

Fix: `PathGuard.sanitizeFileName` (`:640`) plus a containment re-check with
`PathGuard.isInside` (`:642`); invalid names return `400`.

### F-09 — "Allow remote access" not enforced (Medium)

`ContentStore.getAllowRemoteAccess` was persisted and shown in Settings but
never consulted, so file operations remained reachable remotely even when the
toggle was off.

Fix: `route` returns `403 Forbidden` for the file API routes (`/api/browse`,
`/files`, `/api/mkdir`, `/api/delete`, `/api/rename`, `/api/upload`) when the
setting is disabled (`:394`). `/` and `/status` remain reachable so the server
can still report its state.

### F-10 — "Show hidden files" not enforced (Low)

`ContentStore.getShowHiddenFiles` / `isHiddenName` were unused, exposing
dotfiles in the web UI.

Fix: hidden entries are filtered in `serveBrowse` (`:522`) and
`serveFileListingHtml` (`:668`).

### F-11 — Timing-unsafe password comparison (Low)

`isAuthorized` used `String.equals` for the password.

Fix: `TokenUtil.constantTimeEquals` for header/query auth (`:335`) and Basic
auth (`:338`).

## Residual risks (not changed)

- **R-01 Plaintext password storage.** `ContentStore` stores the access
  password in `SharedPreferences` (`ContentStore.java:42,123-124`), and
  `ServerProfileStore` stores remote passwords in JSON
  (`ServerProfileStore.java:89`). App-private storage limits exposure, but a
  rooted device or backup extraction can read them. Recommendation: use
  `EncryptedSharedPreferences` or a Keystore-backed key.
- **R-02 Backups.** `android:allowBackup="true"` (`AndroidManifest.xml:21`)
  with empty `data_extraction_rules.xml`. Recommendation: exclude preference
  files or set `allowBackup="false"`.
- **R-03 Cleartext HTTP.** `android:usesCleartextTraffic="true"`
  (`AndroidManifest.xml:25`) is required for the LAN HTTP server; credentials
  and file contents are unencrypted on the wire. Recommendation: document it
  and consider TLS for remote profiles.
- **R-04 FileProvider root.** `file_paths.xml` declares
  `<root-path name="root" path="/" />` (`:3`), exposing the whole filesystem
  to FileProvider grants. Recommendation: narrow to the actual shared
  directories.
- **R-05 Custom API endpoints.** `/api/<name>` is intentionally unauthenticated
  (documented in the app). Keep it in mind when sharing the server address.
- **R-06 64 MB buffered legacy bodies.** The cap plus the 64-connection limit
  bounds memory, and `readExactly` no longer pre-allocates, but a determined
  client can still make the device buffer up to 64 MB per active request.
  Recommendation: stream legacy uploads like the `/api/v1` path, or lower the
  legacy cap.

## Tests

Added under `app/src/test/java/com/yadaventerprise/phoneserver/`:

- `SimpleHttpServerEncodingTest` — pure JVM, runs everywhere:
  HTML escaping and path URL-encoding (guards F-06).
- `SimpleHttpServerSecurityTest` — Robolectric (`sdk = 34`,
  `ConscryptMode.OFF`), covers F-01 (`413` before routing) and F-09 (`403`
  when remote access is off). It calls
  `DataTestSupport.assumeRobolectricRuntimeSupported()` so it skips on hosts
  where Robolectric's native runtime is unavailable (e.g. aarch64 Linux) and
  runs on x86_64 CI.

Verification commands:

```sh
ANDROID_HOME=/tmp/android-sdk sh gradlew compileDebugJavaWithJavac
ANDROID_HOME=/tmp/android-sdk sh gradlew testDebugUnitTest
```

Last results on this host (aarch64): compile `BUILD SUCCESSFUL`;
`SimpleHttpServerEncodingTest` passed; `SimpleHttpServerSecurityTest`
skipped by design (`tests="2" skipped="2" failures="0"`).
