# PhoneServer Architecture

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
        │   │   ├── ui/          # presentation
        │   │   ├── network/     # HTTP + remote client
        │   │   ├── ads/         # self-contained ad feature
        │   │   ├── service/     # Android lifecycle
        │   │   ├── data/        # persistence
        │   │   ├── security/    # guards
        │   │   ├── model/       # shared DTOs
        │   │   ├── util/        # helpers
        │   │   └── ai/          # local LLM bridge
        │   └── res/             # Android resources (fixed by the platform)
        └── test/java/com/yadaventerprise/phoneserver/
            ├── data/            # storage/database/token tests
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
| `network.http` | Socket HTTP server, request parsing, multipart uploads and the `/api/v1` facade. | `SimpleHttpServer`, `DataApi`, `MultipartParser` |
| `network.remote` | Client for talking to another PhoneServer instance. | `RemoteApiClient` |
| `service` | Foreground service and boot receiver that own the server lifecycle. | `ServerService`, `BootReceiver` |
| `ads` | Vertical feature slice for the optional ad network (HTTP, delivery, fraud, reports, DB, screen). | `AdHttpServer`, `AdDelivery`, `AdNetworkActivity` |
| `data.storage` | Storage layout, shared preferences-backed settings, file/project/model stores, backups. | `ContentStore`, `ProjectStore`, `BackupManager` |
| `data.database` | SQLite/JSON database access, schema helpers, cursors and records. | `DatabaseManager`, `SystemDatabase`, `Records` |
| `data.servers` | Persistence for saved remote-server profiles and custom endpoints. | `ServerProfileStore`, `ApiEndpointStore` |
| `data.stats` | Traffic and device statistics collection and snapshots. | `ServerStats`, `DeviceStats` |
| `data.tokens` | Hashed API token persistence. | `TokenStore` |
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
| `ui.qr` (2) | `QrCodeActivity`, `QrFragment` |
| `network.http` (4) | `SimpleHttpServer`, `DataApi`, `DataException`, `MultipartParser` |
| `network.remote` (1) | `RemoteApiClient` |
| `service` (2) | `ServerService`, `BootReceiver` |
| `ads` (11) | `AdApi`, `AdDb`, `AdDelivery`, `AdFraud`, `AdHttp`, `AdHttpServer`, `AdNetworkActivity`, `AdNetworkManager`, `AdRateLimiter`, `AdReports`, `AdSecurity` |
| `data.storage` (8) | `ContentStore`, `ContentStoreBridge`, `StorageLayout`, `FileStore`, `ProjectStore`, `ModelStore`, `BackupManager`, `BackupArchive` |
| `data.database` (7) | `DatabaseManager`, `SystemDatabase`, `SystemStore`, `JsonCursor`, `JsonMetaStore`, `JsonStorage`, `Records` |
| `data.servers` (2) | `ServerProfileStore`, `ApiEndpointStore` |
| `data.stats` (3) | `ServerStats`, `StatsSnapshot`, `DeviceStats` |
| `data.tokens` (1) | `TokenStore` |
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
SimpleHttpServer (network.http)
        │  routes /api/v1 ──► DataApi ──► data.storage / data.database
        │  routes /api/*   ──► custom endpoints (data.servers)
        │  routes files    ──► data.storage.ContentStore
        ▼
security guards (PathGuard, RateLimiter, TokenUtil)
        │
        ▼
Android storage / SQLite
```

The ad network runs its own server on port `8090` and does not share
authentication or storage with the file server:

```
AdNetworkManager (ads) ──► AdHttpServer ──► AdApi / AdDelivery / AdReports
                                   │
                                   └──► AdDb (SQLite) + AdSecurity (PBKDF2)
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
| API tokens | `TokenStore` (SHA-256 hashes only) |
| Ad network data | `adnet.db` + app-private files |
| Debug signing key | `app/debug.keystore` (debug-only, standard public password) |

No secrets are committed to Java source. See `SECURITY_AUDIT.md` for the
residual risks (plaintext access password in app-private preferences,
`allowBackup`, cleartext HTTP, FileProvider root).

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

Last verification after the reorganisation:

- `compileDebugJavaWithJavac` — **BUILD SUCCESSFUL**.
- `testDebugUnitTest` — **BUILD SUCCESSFUL**, 92 tests, 0 failures.
  49 Robolectric tests are skipped on this aarch64 host by
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
