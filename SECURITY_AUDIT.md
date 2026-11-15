# PhoneServer Security Audit

Date: 2026-11-15
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
credentials, or SQL injection were found. The `/api/v1` surface uses
parameterized SQL; the `adnet` surface uses an encrypted JSON store with atomic
writes and transactional rollback. Both enforce ownership checks, PBKDF2
password hashing, bounded thread pools and CSP on the ad dashboard.

The Phase 4 review added conversion tracking, experiments, payouts, offline
geography and SMTP summaries:

- **Conversion attribution** requires a valid click token; server-to-server
  postbacks are HMAC-SHA256 signed with a key derived per advertiser account,
  transaction ids are deduplicated, and only admins can approve/reject a
  conversion. Rejected conversions never affect billing.
- **Offline geography** stores only a two-letter country on events/aggregates,
  never the source address. Uploads are admin-only, capped at 12 MiB and
  replaced atomically.
- **Payout statements** are role-scoped; publishers can read/export only their
  own rows.
- **SMTP** uses a minimal client with STARTTLS or implicit TLS, a write-only
  password stored in the encrypted JSON document, CR/LF-stripped headers and
  no credential logging. Delivery failures are recorded without passwords.
- **Dashboard** additions use the same strict CSP: no inline scripts, styles or
  event handlers.

## Fixed findings

All fixes are in
`app/src/main/java/com/yadaventerprise/phoneserver/network/http/SimpleHttpServer.java`
unless stated otherwise.

### F-01 — Unbounded request-body allocation (High)

Before the fix, `readRequest` did `new byte[contentLength]` with the legacy
non-API path allowing `Integer.MAX_VALUE`. A single unauthenticated request
with `Content-Length: 2147483647` forced a ~2 GB allocation and crashed the
process.

Fix:
- Buffered bodies are capped at `MAX_BUFFERED_API_BODY = 64 MB`
  (`SimpleHttpServer.java:155`), with oversized bodies marked `bodyTooLarge`
  (`:239`) and answered with `413 Payload Too Large` (`:367`).
- `readExactly` (`:250`) now grows a `ByteArrayOutputStream` as bytes actually
  arrive instead of pre-allocating the declared length, so a client that only
  sends headers cannot reserve memory upfront.

### F-02 — Unbounded header line (High)

`readLine` accumulated into an unbounded buffer.

Fix: `MAX_HEADER_LINE = 8192` (`:60`); exceeding it throws
`IOException("header line too long")` (`:181`).

### F-03 — Unbounded number of headers (High)

Fix: `MAX_HEADERS = 100` (`:62`); exceeding it throws
`IOException("too many headers")` (`:209`).

### F-04 — No socket read timeout (High)

Fix: `CLIENT_TIMEOUT_MS = 15_000` (`:64`), applied with
`s.setSoTimeout(CLIENT_TIMEOUT_MS)` (`:314`), bounding slowloris connections.

### F-05 — Unbounded thread fan-out (High)

Fix: `MAX_CLIENTS = 64` (`:66`) with a `Semaphore clientSlots` (`:70`).
`acceptLoop` sheds load with `503 Service Unavailable` when no permit is
available (`:120`), releases on failure (`:137`), and `handleClient` releases
in `finally` (`:329`).

### F-06 — Stored XSS / HTML injection (Medium)

`serveFileListingHtml` concatenated file names into HTML text and `href`
attributes. A file named `<img src=x onerror=...>` executed script in the
browser of anyone viewing the shared-files page.

Fix: added `escapeHtml` (`:689`) and `urlEncodePath` (`:707`); the listing now
escapes the visible name and URL-encodes the link target (`:676-679`).

### F-07 — JSON injection in upload response (Medium)

The upload handler built the JSON response by string concatenation, so a
crafted filename could break out of the JSON string.

Fix: the response is built with `JSONObject` (`:655-660`).

### F-08 — Upload filename not validated (Medium)

The browser-supplied filename was used directly. This was mitigated by
`resolveSafePath`, but a filename such as `../../...` should never reach the
filesystem layer.

Fix: `PathGuard.sanitizeFileName` (`:644`) plus a containment re-check with
`PathGuard.isInside` (`:646`); invalid names return `400`.

### F-09 — "Allow remote access" not enforced (Medium)

`ContentStore.getAllowRemoteAccess` was persisted and shown in Settings but
never consulted, so file operations remained reachable remotely even when the
toggle was off.

Fix: `route` returns `403 Forbidden` for the file API routes (`/api/browse`,
`/files`, `/api/mkdir`, `/api/delete`, `/api/rename`, `/api/upload`) when the
setting is disabled (`:398`). `/` and `/status` remain reachable so the server
can still report its state.

### F-10 — "Show hidden files" not enforced (Low)

`ContentStore.getShowHiddenFiles` / `isHiddenName` were unused, exposing
dotfiles in the web UI.

Fix: hidden entries are filtered in `serveBrowse` (`:526`) and
`serveFileListingHtml` (`:672`).

### F-11 — Timing-unsafe password comparison (Low)

`isAuthorized` used `String.equals` for the password.

Fix: `TokenUtil.constantTimeEquals` for header/query auth (`:339`) and Basic
auth (`:342`).

## Phase 1 security hardening

The Security Center adds defence-in-depth on top of the fixes above. These are
not new findings; they are controls that make attacks visible and repeatable
failures self-limiting.

| Control | Implementation |
|---|---|
| Request audit | `AccessLogStore` records method, path, real response status, IP, actor/token id, user agent, byte counts and duration in bounded app-private JSONL |
| Intrusion events | `SecurityEventStore` records auth failures, suspicious paths, rate-limit hits and blocked requests |
| Suspicious-path rejection | `IntrusionDetector.isSuspiciousPath` rejects traversal, null bytes, `.env`, `.git`, `/etc/passwd`, wp-admin/phpMyAdmin and common probe payloads before routing |
| Automatic blocking | `IntrusionDetector` counts failures; `IpBlockStore` blocks after the configured threshold (default 5 auth failures in 10 min for 15 min, or 3 suspicious paths) |
| Manual blocking | Access-log/event screens can permanently block an IP; loopback is always exempt |
| Token RBAC | `TokenPolicy`/`TokenPolicyStore` add viewer/editor/admin roles, explicit permissions, project scope, expiry and per-token rate limits to the existing hashed bearer tokens |
| Config scanner | `SecurityScanner` derives findings only from the real configuration and recent events; no invented vulnerabilities or statistics |
| Scheduled backups | `AlarmManager` + `BackupScheduler` back up every project, rotate per project, reschedule on boot/server start, and support manual runs |
| Private storage | Logs, settings, blocks, policies, reports and schedule live under app-private `files/security/`; no HTTP route exposes them |

Residual risks R-01–R-06 below still apply. In particular, the legacy
`isAuthorized` path is open when no password is configured, while `/api/v1`
continues to require either the password or a token.

## Phase 2 security hardening

| Control | Implementation |
|---|---|
| Optional HTTPS | `TlsContextFactory` + `SSLServerSocket` on a separate port; generated self-signed RSA-2048 certificate or PKCS#12 import |
| Private key protection | `TlsCertificateStore` keeps key/cert in encrypted app-private JSON under `files/network/`; `SecurityPaths.restrict` limits access |
| Certificate pinning | Pairing QR carries a SHA-256 fingerprint; `TlsClient` accepts only the pinned certificate, even for IP/hostname mismatches |
| Secure pairing | `PairingStore` hashes one-time codes, limits attempts to 8, expires after 10 minutes, rate-limits `/api/pair` to 12/min, and issues scoped editor tokens (30 days, 240 req/min) |
| WebDAV authorization | Remote-access toggle checked before routing; bearer tokens require `files.read`/`files.write`; paths resolved through `ContentStore.resolveSafePath` |
| Webhook signing | Optional HMAC-SHA256 signature over the exact JSON body in `X-PhoneServer-Signature`; secret stored in encrypted webhook config |
| Webhook retry/history | Three attempts with backoff; bounded history; failures do not block server responses |
| Scheduled task isolation | Tasks only run project backups or configured webhooks; results are persisted and emitted as security-visible events |
| Watchdog intent | Restart alarms only fire when `desired_running` is true, so a user stop is not overridden |

### Phase 2 residual risks

- **R-07 HTTP remains open alongside HTTPS.** Enabling TLS does not close port
  `8080`. Recommendation: block `8080` at the router/firewall when exposing the
  device beyond a trusted LAN.
- **R-08 Self-signed certificate trust is fingerprint-based.** Users who skip
  QR pairing must manually verify the fingerprint; a changed certificate
  requires re-pairing.
- **R-09 Webhook destinations are user-controlled.** Webhooks can POST to any
  HTTP/HTTPS URL, including LAN addresses; this is intentional for local
  automation but should be treated as an outbound-request capability.
- **R-10 `phoneserver.local` has no mDNS responder.** The name is present in the
  certificate SAN and HTTPS settings, but clients need the LAN IP or a DNS
  record they control.

## Phase 3 sharing hardening

| Control | Implementation |
|---|---|
| Unpredictable link secrets | `ShareToken` generates `pss_` + 256 bits of randomness; `ShareStore` persists only the SHA-256 hash |
| Password storage | `SharePassword` uses PBKDF2-HMAC-SHA256, 120,000 iterations and a 16-byte random salt, with PBKDF2-HMAC-SHA1 fallback for imported/legacy hashes; verification is constant-time |
| No secret disclosure | Raw tokens are returned only from create/rotate; the UI warns that they cannot be recovered; URLs are not stored or logged |
| Server-side authorization | Every share request re-checks revocation, expiry, permission, password session and remaining downloads |
| Download limits | `ShareStore.consumeDownload` atomically increments and persists the counter under the store lock before streaming |
| Password sessions | `ShareSessionStore` keeps random 32-byte session ids in memory only; cookie is `HttpOnly`, `SameSite=Lax`, 30-minute TTL, `Secure` on HTTPS; sessions are revoked on rotation, password change, revoke and delete |
| Brute-force limits | 240 share requests/min/IP and 20 password attempts/min/IP+link; password failures are audited |
| Path containment | Share roots are non-empty relative paths under the public files directory, canonicalised with `PathGuard`; hidden segments and escaped symlinks are rejected |
| Response hardening | `Cache-Control: no-store`, restrictive CSP, `nosniff`, `Referrer-Policy: no-referrer`; HTML/SVG are never inline-previewed |
| Transport honesty | HTTP share pages and the app display an explicit “not encrypted” warning; served pages no longer claim “secure” when TLS is off |
| Bounded audit | `ShareAccessLogStore` rotates at 2 MB and records link id/name, event, status, IP, relative path and user-agent only; never tokens or passwords |

### Phase 3 residual risks

- **R-11 Share links can still use plain HTTP.** A generated self-signed
  certificate cannot be trusted by browsers, so local share links deliberately
  use HTTP instead of producing `ERR_CERT_AUTHORITY_INVALID`. HTTPS share links
  are used only with a configured public origin or a CA-signed certificate.
  The app and served pages warn explicitly. Recommendation: use a public HTTPS
  origin or a CA-signed certificate outside a trusted LAN and block port `8080`
  at the firewall.
- **R-12 Inline previews are still retrievable.** A view-only share prevents the
  browser's Download action and does not consume the download counter, but any
  inline-previewable bytes can be saved by the client. Do not share confidential
  media with untrusted recipients.
- **R-13 Share audit data is sensitive.** The access log records client IP,
  requested paths and user-agent strings in app-private storage. It is not
  encrypted and is included in app backups. Recommendation: keep
  `allowBackup="false"` for production or exclude `files/shares/`.
## Residual risks (not changed)

- **R-01 Plaintext password storage.** `ContentStore` stores the access
  password in `SharedPreferences` (`data/storage/ContentStore.java:38,119-120`), and
  `ServerProfileStore` stores remote passwords in JSON
  (`data/servers/ServerProfileStore.java:88`). App-private storage limits exposure, but a
  rooted device or backup extraction can read them. Recommendation: use
  `EncryptedSharedPreferences` or a Keystore-backed key.
- **R-02 Backups.** `android:allowBackup="true"` (`AndroidManifest.xml:21`)
  with empty `data_extraction_rules.xml`. Recommendation: exclude preference
  files or set `allowBackup="false"`.
- **R-03 Cleartext HTTP.** `android:usesCleartextTraffic="true"`
  (`AndroidManifest.xml:25`) is still required for the LAN HTTP server and the
  ad network; credentials and file contents are unencrypted on port `8080`.
  HTTPS is now available on `8443` for paired/remote clients, but it is
  optional and does not replace HTTP. Recommendation: use HTTPS and block the
  HTTP port when exposing the device beyond a trusted LAN.
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

Added under `app/src/test/java/com/yadaventerprise/phoneserver/` (HTTP tests in
`network/http/`, shared helpers in `testing/`):

- `SimpleHttpServerEncodingTest` — pure JVM, runs everywhere:
  HTML escaping and path URL-encoding (guards F-06).
- `SimpleHttpServerSecurityTest` — Robolectric (`sdk = 34`,
  `ConscryptMode.OFF`), covers F-01 (`413` before routing) and F-09 (`403`
  when remote access is off). It calls
  `DataTestSupport.assumeRobolectricRuntimeSupported()` so it skips on hosts
  where Robolectric's native runtime is unavailable (e.g. aarch64 Linux) and
  runs on x86_64 CI.
- `TokenPolicyTest` — pure JVM: role presets, write detection, expiry and
  invalid-role fallback.
- `IntrusionDetectorTest` — pure JVM: probe paths, legitimate paths, null
  bytes and overlong paths.
- `BackupScheduleTest` — pure JVM: first/next/overdue run calculation and
  interval clamping.
- `SecurityScannerTest` — pure JVM: critical missing password, wildcard CORS,
  disabled rate limit, clean score/grade and finding lookup.
- `TlsCertificateFactoryTest` — pure JVM: certificate generation, SAN/subject,
  fingerprint formatting and self-signature verification.
- `ShareTokenTest`, `SharePasswordTest`, `ShareLinkTest`, `ShareStoreTest`,
  `ShareAccessLogStoreTest` — token shape/hashing, PBKDF2 verification,
  expiry/limit state, store lifecycle, session invalidation and access-log filtering (14 tests).
- `WebhookConfigTest`, `ScheduledTaskTest` — JSON round-trip and event/interval
  validation.
- `WebhookDispatcherTest` — Robolectric: signed JSON delivery to a loopback
  HTTP server, header verification and bounded history recording.

Verification commands:

```sh
ANDROID_HOME=/tmp/android-sdk sh gradlew compileDebugJavaWithJavac
ANDROID_HOME=/tmp/android-sdk sh gradlew testDebugUnitTest
ANDROID_HOME=/tmp/android-sdk sh gradlew assembleDebug
```

Last results on this host (aarch64): compile and `assembleDebug`
`BUILD SUCCESSFUL`; full unit suite `144 tests, 0 failures` (49 Robolectric
tests skipped by design on this host; 95 passed, including all 14 sharing tests and 7 ad-network storage tests). `SimpleHttpServerEncodingTest` passed;
`SimpleHttpServerSecurityTest` skipped (`tests="2" skipped="2" failures="0"`).
