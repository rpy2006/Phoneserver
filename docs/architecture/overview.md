# PhoneServer Architecture

## Navigation

- [Documentation index](../README.md)
- [Feature overview](../features/overview.md)
- [Storage layout](storage.md)
- [Security overview](../security/overview.md)
- [Testing](../development/testing.md)

This document describes the final, reorganised structure of the PhoneServer
Android app. It is the source of truth for where code lives and which layers
may depend on which.

## 1. Goals

The reorganisation was done incrementally, without changing behaviour or
deleting user data:

- Separate UI, networking, data, security and cross-cutting utilities.
- Group related features into cohesive packages.
- Make dependencies one-directional and easy to trace.
- Keep configuration and secrets out of application code.
- Preserve every existing screen, endpoint and stored format.
- Remove only files verified to be unreachable or duplicated.

The app remains **one Gradle module (`:app`)**. A multi-module split was
deliberately avoided because it would move resources, duplicate `R` classes and
change the manifest pipeline for no functional gain. The package boundaries
below give the same layering while keeping builds and upgrades stable. A future
module extraction can follow these package seams.

## 2. Final layout

```
PhoneServer/
├── build.gradle                 # AGP 8.5.2, applied in :app
├── settings.gradle              # include ':app'
├── gradle.properties
├── README.md
├── ARCHITECTURE.md              # this file
├── SECURITY_AUDIT.md
├── index.html / web/            # published docs site (not in the APK)
└── app/
    ├── build.gradle
    ├── debug.keystore           # debug-only signing key
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── jniLibs/arm64-v8a/libllama_bridge.so
        │   ├── assets/adnet/    # dashboard.html/css/js + public sdk.js
        │   ├── java/com/yadaventerprise/phoneserver/
        │   │   ├── ui/          # presentation (incl. ui.security/ui.sharing)
        │   │   ├── network/     # HTTP, TLS, pairing, WebDAV + remote client
        │   │   ├── ads/         # self-contained ad feature
        │   │   ├── service/     # lifecycle, watchdog, backup/task alarms
        │   │   ├── data/        # persistence (incl. security/webhooks/tasks)
        │   │   ├── security/    # path/SQL/rate/token guards
        │   │   ├── model/       # shared DTOs
        │   │   ├── util/        # helpers
        │   │   └── ai/          # local LLM bridge
        │   └── res/             # Android resources (fixed by the platform)
        └── test/java/com/yadaventerprise/phoneserver/
            ├── data/            # storage, database, token, policy, backup tests
            ├── network/http/    # HTTP security tests
            ├── security/        # guard/limiter tests
            ├── util/            # MIME tests
            └── testing/         # shared Robolectric helpers
```

`res/` cannot be subdivided into arbitrary folders: Android resolves resources
by type (`layout/`, `drawable/`, `values/`, `menu/`, `xml/`). Keeping the
standard layout is what preserves resource references and the generated `R`
class. Configuration files remain in `res/xml/` and are listed in section 6.

## 3. Layers and responsibilities

| Package | Responsibility | Examples |
|---|---|---|
| `ui.*` | Activities, Fragments, RecyclerView adapters and custom views. Renders state and calls into lower layers. | `MainActivity`, `DashboardFragment`, `FilesActivity` |
| `network.http` | Socket HTTP/HTTPS server, request parsing, multipart uploads, pairing, WebDAV and the `/api/v1` facade. | `SimpleHttpServer`, `DataApi`, `MultipartParser`, `PairingApi`, `WebDavHandler` |
| `network.tls` | Self-signed certificate generation/import, encrypted identity storage, SSL contexts and pinned clients. | `TlsCertificateFactory`, `TlsCertificateStore`, `TlsContextFactory`, `TlsClient` |
| `network.pairing` | One-time pairing code storage and QR payload parsing. | `PairingStore`, `PairingCode`, `PairingPayload` |
| `network.remote` | Client for talking to another PhoneServer instance. | `RemoteApiClient` |
| `service` | Foreground service, boot/watchdog receivers and `AlarmManager` backup/task scheduling. | `ServerService`, `BootReceiver`, `ServerWatchdog`, `BackupScheduler`, `ScheduledTaskScheduler` |
| `ads` | Vertical feature slice for the optional ad network (HTTP, delivery, fraud, reports, DB, screen). | `AdHttpServer`, `AdDelivery`, `AdNetworkActivity` |
| `data.storage` | Storage layout, shared preferences-backed settings, file/project/model stores, backups. | `ContentStore`, `ProjectStore`, `BackupManager` |
| `data.database` | SQLite/JSON database access, schema helpers, cursors and records. | `DatabaseManager`, `SystemDatabase`, `Records` |
| `data.servers` | Persistence for saved remote-server profiles and custom endpoints. | `ServerProfileStore`, `ApiEndpointStore` |
| `data.stats` | Traffic and device statistics collection and snapshots. | `ServerStats`, `DeviceStats` |
| `data.tokens` | Hashed API token persistence. | `TokenStore` |
| `data.security` | Access/event logs, IP blocks, intrusion settings, token policies, security scanner/reports. | `AccessLogStore`, `IpBlockStore`, `TokenPolicyStore`, `SecurityScanner` |
| `data.sharing` | Share-link models/stores, token/password hashing, in-memory unlock sessions, URL construction and bounded access log. | `ShareStore`, `SharePassword`, `ShareToken`, `ShareSessionStore`, `ShareUrlBuilder` |
| `data.webhooks` | Webhook configs, signed delivery, retries and bounded delivery history. | `WebhookStore`, `WebhookDispatcher`, `WebhookHistoryStore` |
| `data.tasks` | Repeating task model/store and backup/webhook execution. | `ScheduledTask`, `ScheduledTaskStore`, `ScheduledTaskRunner` |
| `security` | Path containment, SQL safety, rate limiting and constant-time comparison. | `PathGuard`, `SqlGuard`, `RateLimiter`, `TokenUtil` |
| `model` | Plain data objects shared across layers. | `ServerProfile`, `KeyValue`, `RemoteEntry` |
| `util` | Small cross-cutting helpers. | `ByteUtils`, `MimeTypes`, `TimeFormat`, `NetworkUtils` |
| `ai` | Local language-model inference bridge. | `LlmEngine` |

### Package inventory

| Package | Classes |
|---|---|
| `ui.main` (5) | `MainActivity`, `MainPagerAdapter`, `HomeFragment`, `SettingsFragment`, `ChatFragment` |
| `ui.dashboard` (3) | `DashboardFragment`, `SparklineView`, `TrafficChartView` |
| `ui.files` (12) | `FilesActivity`, `FilesAdapter`, `ProjectFilesActivity`, `ProjectFilesAdapter`, `WebsiteFilesActivity`, `RemoteBrowseActivity`, `RemoteEntryAdapter`, `ImageViewerActivity`, `VideoPlayerActivity`, `MusicPlayerActivity`, `TextFileViewerActivity`, `PdfViewerActivity` |
| `ui.projects` (6) | `ProjectsActivity`, `ProjectsAdapter`, `ProjectDetailActivity`, `ProjectBackupsActivity`, `ProjectBackupsAdapter`, `SqlWorkspaceActivity` |
| `ui.servers` (6) | `ServersListActivity`, `ServersAdapter`, `AddServerActivity`, `ServerSettingsActivity`, `AccountSettingsActivity`, `AdvancedSettingsActivity` |
| `ui.api` (6) | `ApiEditorActivity`, `ApiEndpointEditorActivity`, `ApiDocsActivity`, `ApiTokensActivity`, `ApiAdapter`, `ApiTokensAdapter` |
| `ui.security` (15) | `SecurityCenterActivity`, `AccessLogActivity`, `SecurityEventsActivity`, `BlockedIpsActivity`, `BackupScheduleActivity`, `HttpsSetupActivity`, `WebDavActivity`, `WebhookActivity`, `WebhookEditActivity`, `TasksActivity`, `TaskEditActivity`, `AccessLogAdapter`, `SecurityEventAdapter`, `BlockedIpAdapter`, `SecurityFindingAdapter` |
| `ui.qr` (2) | `QrCodeActivity`, `QrFragment` |
| `ui.sharing` (7) | `SharingOverviewActivity`, `ShareListActivity`, `ShareEditActivity`, `SharePathPickerActivity`, `ShareQrActivity`, `ShareAccessHistoryActivity`, `SharingSettingsActivity` |
| `network.http` (7) | `SimpleHttpServer`, `DataApi`, `DataException`, `MultipartParser`, `PairingApi`, `WebDavHandler`, `ShareHandler` |
| `network.tls` (8) | `HttpsConfig`, `HttpsConfigStore`, `NetworkPaths`, `TlsCertificateFactory`, `TlsCertificateStore`, `TlsClient`, `TlsContextFactory`, `TlsIdentity` |
| `network.pairing` (3) | `PairingCode`, `PairingPayload`, `PairingStore` |
| `network.remote` (1) | `RemoteApiClient` |
| `service` (8) | `ServerService`, `BootReceiver`, `BackupScheduler`, `BackupAlarmReceiver`, `ServerWatchdog`, `ServerWatchdogReceiver`, `ScheduledTaskScheduler`, `ScheduledTaskReceiver` |
| `ads` (17) | `AdApi`, `AdConversion`, `AdDb`, `AdDelivery`, `AdExperimentEngine`, `AdFraud`, `AdGeo`, `AdGeoReport`, `AdHttp`, `AdHttpServer`, `AdNetworkActivity`, `AdNetworkManager`, `AdPayouts`, `AdRateLimiter`, `AdReports`, `AdSecurity`, `AdSummaryMailer` |
| `data.storage` (10) | `ContentStore`, `ContentStoreBridge`, `StorageLayout`, `FileStore`, `ProjectStore`, `ModelStore`, `BackupManager`, `BackupArchive`, `BackupSchedule`, `BackupScheduleStore` |
| `data.database` (7) | `DatabaseManager`, `SystemDatabase`, `SystemStore`, `JsonCursor`, `JsonMetaStore`, `JsonStorage`, `Records` |
| `data.servers` (2) | `ServerProfileStore`, `ApiEndpointStore` |
| `data.stats` (3) | `ServerStats`, `StatsSnapshot`, `DeviceStats` |
| `data.tokens` (1) | `TokenStore` |
| `data.security` (16) | `SecurityPaths`, `AccessLogEntry`, `AccessLogStore`, `SecurityEvent`, `SecurityEventStore`, `SecuritySettings`, `SecuritySettingsStore`, `IpBlockStore`, `IntrusionDetector`, `TokenPolicy`, `TokenPolicyStore`, `SecurityConfig`, `SecurityFinding`, `SecurityReport`, `SecurityScanner`, `SecurityReportStore` |
| `data.sharing` (11) | `ShareAccessEvent`, `ShareAccessLogStore`, `ShareLink`, `SharePassword`, `SharePaths`, `ShareSessionStore`, `ShareSettings`, `ShareSettingsStore`, `ShareStore`, `ShareToken`, `ShareUrlBuilder` |
| `data.webhooks` (5) | `WebhookConfig`, `WebhookDispatcher`, `WebhookHistoryEntry`, `WebhookHistoryStore`, `WebhookStore` |
| `data.tasks` (3) | `ScheduledTask`, `ScheduledTaskStore`, `ScheduledTaskRunner` |
| `security` (4) | `PathGuard`, `SqlGuard`, `RateLimiter`, `TokenUtil` |
| `model` (4) | `ApiEndpointConfig`, `KeyValue`, `RemoteEntry`, `ServerProfile` |
| `util` (6) | `ByteUtils`, `FileTypeUtils`, `IconSwitcher`, `MimeTypes`, `NetworkUtils`, `TimeFormat` |
| `ai` (1) | `LlmEngine` |

Each package has a `package-info.java` describing its role.

## 4. Dependency rules

```
ui ──────────────► network ──────► data ──────► security
 │                   │              │              │
 │                   ▼              ▼              ▼
 └──────────────► service         model ◄────── util
                                   ▲
                                   └── ai
```

Rules:

1. `ui` may depend on any lower layer.
2. `network`, `service` and `data` must not import `ui`.
3. `data` must not import `network` or `service`.
4. `security`, `model` and `util` must not import `ui`, `network`, `service` or
   `data` (they are leaf packages).
5. `ads` is a self-contained feature slice. It may use `data.database.Records`,
   `service.ServerService` and `util.NetworkUtils`; its internals stay together
   because several classes share package-private members.
6. Android framework imports (`android.*`, `androidx.*`) are allowed in any
   layer, but only `ui` and `service` should need UI framework classes.
7. `data.security.SecurityConfig` reads `ServerService.isServerRunning()` so
   the scanner reports the real server state. That one service import is the
   documented exception to rule 3; it is read-only and does not start the
   service.

These rules are enforced by review, not by the compiler, because the project is
a single Gradle module.

## 5. Request and data flow

```
Browser / curl / another phone
        │
        ▼
ServerService (foreground service)
        │  owns
        ▼
SimpleHttpServer (network.http, optional SSLServerSocket)
        │  IP block check ──► IpBlockStore
        │  suspicious-path check ──► IntrusionDetector
        │  routes /api/pair ──► PairingApi ──► PairingStore + TokenStore
        │  routes /s/*    ──► ShareHandler ──► data.sharing + ContentStore public root
        │  routes /dav/*   ──► WebDavHandler ──► data.storage.ContentStore
        │  routes /api/v1 ──► DataApi ──► data.storage / data.database
        │       ├─ global + per-token rate limits
        │       ├─ password (admin) or bearer token (role/permissions/expiry)
        │       └─ project scope + permission checks
        │  routes /api/*   ──► custom endpoints (data.servers)
        │  routes files    ──► data.storage.ContentStore
        ▼
security guards (PathGuard, RateLimiter, TokenUtil)
        │
        ├── access log / security events (data.security)
        ├── webhook dispatch (data.webhooks)
        └── auto-block after repeated failures (IpBlockStore)
        │
        ▼
Android storage / SQLite
```

The ad network runs its own server on port `8090` and does not share
authentication or storage with the file server:

```
AdNetworkManager (ads) ──► AdHttpServer ──► AdApi / AdDelivery / AdReports
                                   │
                                   └──► AdDb (encrypted JSON) + AdSecurity (PBKDF2)
```

## 6. Configuration and sensitive data

Configuration stays out of Java code:

| Concern | Location |
|---|---|
| App id, SDK levels, ABI filter, dependencies | `app/build.gradle` |
| Permissions, components, providers | `app/src/main/AndroidManifest.xml` |
| Cleartext / certificate policy | `res/xml/network_security_config.xml` |
| FileProvider paths | `res/xml/file_paths.xml` |
| Backup / data-extraction rules | `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` |
| User settings (password, toggles, data root) | `ContentStore` SharedPreferences |
| Saved server profiles | `ServerProfileStore` SharedPreferences |
| Custom endpoints | `ApiEndpointStore` SharedPreferences |
| API tokens | `TokenStore` (SHA-256 hashes only) + `data.security.TokenPolicyStore` (encrypted JSON sidecar) |
| Security settings / block list / logs / reports | app-private `files/security/*` via `SecurityPaths`; logs are plain JSONL, settings/policies/reports are encrypted JSON |
| Backup schedule | app-private encrypted JSON via `BackupScheduleStore` |
| HTTPS config / TLS identity | app-private encrypted JSON under `files/network/` |
| Webhooks / delivery history | app-private encrypted JSON under `files/webhooks/` |
| Scheduled tasks | app-private encrypted JSON under `files/tasks/` |
| Share links/settings | app-private encrypted JSON under `files/shares/`; access log is bounded plain JSONL |
| Share unlock sessions | in-memory only (`ShareSessionStore`); never persisted |
| Ad network data | encrypted `filesDir/adnet/adnet.json`, geo CSV and creative files |
| Debug signing key | `app/debug.keystore` (debug-only, standard public password) |

No secrets are committed to Java source. See `SECURITY_AUDIT.md` for the
residual risks (plaintext access password in app-private preferences,
`allowBackup`, cleartext HTTP, FileProvider root).

## 6a. Security Center, webhooks and scheduled automation

The Security Center is a read-only view over real persisted state, plus a
scanner that derives findings from the current configuration. It never invents
traffic, vulnerabilities or usage numbers.

- **Access log** (`AccessLogStore`) — bounded JSONL, one rotated file, records
  method, path, status, IP, actor/token id, user agent, byte counts and
  duration. Response writers call `SimpleHttpServer.noteStatus()` so the log
  records the actual status, including errors written directly by `DataApi`.
- **Security events** (`SecurityEventStore`) — bounded JSONL for auth failures,
  suspicious paths, rate limits and blocked requests.
- **IP blocks** (`IpBlockStore`) — persisted allow/block decisions with optional
  expiry. Loopback is always exempt. The HTTP server checks it before routing.
- **Intrusion detection** (`IntrusionDetector`) — in-memory failure counters;
  persisted events/blocks survive process restarts. Thresholds and duration
  live in `SecuritySettings`.
- **Token policies** (`TokenPolicy`, `TokenPolicyStore`) — viewer/editor/admin
  roles, explicit permissions, per-token rate limit and expiry. The SQLite
  `TokenStore` still owns the token hash; the policy sidecar is keyed by token
  id. Admin tokens and the interactive password bypass project scoping.
- **Scanner** (`SecurityScanner`) — checks real password/CORS/rate/backup/token/
  event state and returns concrete findings with severity weights.
- **Webhooks** (`WebhookDispatcher`) — signed POSTs with retry/backoff and bounded history for file, server, security, backup and task events.
- **Scheduled tasks** (`ScheduledTaskRunner`) — repeating project backups or webhook deliveries via `AlarmManager`, with boot rescheduling.
- **Scheduled backups** (`BackupScheduler`) — `AlarmManager` schedules the next
  run from the persisted `BackupSchedule`; `BackupAlarmReceiver` runs the pass
  with `goAsync()`, backs up every project, then rotates old automatic backups.
  The server service and boot receiver reschedule after start/reboot.

The scanner's `SecurityConfig` is the only place that reads across storage,
tokens, security stores and the running-service flag; it does not mutate
anything.

## 7. Old-to-new package map

| Old location | New location |
|---|---|
| root `*Activity`, `*Fragment`, `*Adapter`, `*View` | `ui.<feature>` |
| root `SimpleHttpServer`, `DataApi`, `DataException`, `MultipartParser` | `network.http` |
| root `RemoteApiClient` | `network.remote` |
| root `ServerService`, `BootReceiver` | `service` |
| root `Ad*` | `ads` |
| root `ContentStore`, `ModelStore`, `ProjectStore` | `data.storage` |
| `data.Backup*`, `data.FileStore`, `data.StorageLayout`, `data.ContentStoreBridge` | `data.storage` |
| `data.DatabaseManager`, `data.System*`, `data.Json*`, `data.Records` | `data.database` |
| root `ServerProfileStore`, `ApiEndpointStore` | `data.servers` |
| root `ServerStats`, `StatsSnapshot`, `DeviceStats` | `data.stats` |
| `data.TokenStore` | `data.tokens` |
| `data.PathGuard`, `data.SqlGuard`, `data.RateLimiter`, `data.TokenUtil` | `security` |
| root `ServerProfile`, `ApiEndpointConfig`, `KeyValue`, `RemoteEntry` | `model` |
| root `ByteUtils`, `FileTypeUtils`, `MimeTypes`, `NetworkUtils`, `IconSwitcher` | `util` |
| root `LlmEngine` | `ai` |

## 8. Changes made during the reorganisation

Behaviour-preserving fixes required by the new package boundaries:

- Extracted `ProjectsActivity.formatTime(long)` into `util.TimeFormat` so
  `ui.api` no longer reaches into `ui.projects`.
- Made `ModelStore.LoadResult.ok()` / `failure(String)` public so `ui.main`
  can build results.
- Updated `AndroidManifest.xml` component names and the two custom-view tags in
  `fragment_dashboard.xml` / `item_dashboard_server.xml`.
- Removed verified-unused files:
  - `ApiEndpoint.java` — duplicate of `ApiEndpointConfig`, no references.
  - `data/JsonDatabaseWrapper.java` — no references anywhere.
  - `DashboardActivity.java` + `layout/activity_dashboard.xml` — unreachable
    legacy screen; `DashboardFragment` is the live dashboard.
  - `WebsiteEditorActivity.java` + `layout/activity_website_editor.xml` —
    unreachable legacy editor; `WebsiteFilesActivity` is the live editor.
  - `layout/dialog_add_api.xml`, `layout/activity_ad_network.xml` — unused;
    their screens build UI in code.
- Removed 23 unused imports surfaced by the move.

No stored data format, SharedPreferences key, API route, port, permission or
user-visible screen was changed.

## 9. Build, test and verification

```sh
ANDROID_HOME=/tmp/android-sdk sh gradlew compileDebugJavaWithJavac
ANDROID_HOME=/tmp/android-sdk sh gradlew testDebugUnitTest
ANDROID_HOME=/tmp/android-sdk sh gradlew assembleDebug
```

Last verification after the security phase:

- `compileDebugJavaWithJavac` — **BUILD SUCCESSFUL**.
- `testDebugUnitTest` — **BUILD SUCCESSFUL**, 157 tests, 0 failures, 0 errors, 50 skipped.
  Some Robolectric tests are skipped on this aarch64 host by
  `DataTestSupport.assumeRobolectricRuntimeSupported()`; they run on x86_64.
- `assembleDebug` — see the build log; the APK packages the manifest,
  resources and dex.

## 10. Extension guide

- **New screen:** add the Activity/Fragment under the matching `ui.<feature>`
  package, declare it in `AndroidManifest.xml`, and add its layout under
  `res/layout/`.
- **New HTTP route:** add a handler in `network.http` (legacy routes in
  `SimpleHttpServer`, versioned routes in `DataApi`). Reuse
  `security.RateLimiter` / `PathGuard` for validation.
- **New persisted entity:** put the model in `model` and its store in the
  appropriate `data.*` package. Use `StorageLayout` for paths and `PathGuard`
  for containment.
- **New shared helper:** `util` if it is pure and stateless; `security` if it
  is a guard; otherwise keep it with its feature.
- **New optional feature:** follow the `ads` pattern — one cohesive package,
  its own DB/files, and only narrow imports into shared layers.
