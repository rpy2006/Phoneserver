# PhoneServer

**Turn any Android phone into a network file server, mini website host, live-traffic monitor, and a small on-device AI chat — all from one APK, with no backend and no internet requirement.**

PhoneServer runs a real HTTP server directly on the device (`java.net.ServerSocket`, no NanoHTTPD, no embedded webview server). Any browser, `curl`, or another phone on the same Wi‑Fi can reach it at `http://<phone-ip>:8080` and browse, upload, download, and organise the phone's shared storage. The same APK can also act as a *client* — connecting to other phones running PhoneServer and browsing them remotely.

---

## Table of Contents

1. [Feature Overview](#1-feature-overview)
2. [Tech Stack & Requirements](#2-tech-stack--requirements)
3. [Project Structure](#3-project-structure)
4. [Architecture Design](#4-architecture-design)
5. [HTTP API Reference](#5-http-api-reference)
6. [Data & Persistence Model](#6-data--persistence-model)
7. [UI Architecture](#7-ui-architecture)
8. [On-Device AI Subsystem](#8-on-device-ai-subsystem)
9. [Security Model](#9-security-model)
10. [Build & Development](#10-build--development)
11. [Design Decisions & Rationale](#11-design-decisions--rationale)
12. [Known Limitations & Technical Debt](#12-known-limitations--technical-debt)
13. [Extension Guide](#13-extension-guide)

---

## 1. Feature Overview

### 1.1 Local HTTP server

| Capability | Endpoint / trigger | Notes |
|---|---|---|
| Hosted website | `GET /` | Serves `files/website/index.html`, plus any CSS/JS/images uploaded alongside it |
| Live status | `GET /status` | Public, no password. Used as the reachability ping |
| File download | `GET /files/<path>` | Password-protected |
| Folder listing (JSON) | `GET /api/browse?path=` | Password-protected |
| Create folder | `POST /api/mkdir` | `{"path":"..","name":".."}` |
| Delete file/folder | `POST /api/delete` | `{"path":".."}`, recursive on folders |
| Rename | `POST /api/rename` | `{"path":"..","newName":".."}` |
| Upload | `POST /api/upload?path=` | `multipart/form-data`, field `file` |
| Live statistics | `GET /api/stats` | Bytes, requests, clients, CPU/RAM, top endpoints, activity feed |
| Custom JSON endpoints | `GET|POST|PUT|DELETE /api/<anything>` | User-defined mock/stub APIs from the in-app editor |

Uploads land in the device's real `Pictures/`, `Movies/`, `Music/`, `Download/` folders and are pushed to `MediaScannerConnection` immediately, so Gallery, Music and Video apps see them without a rescan.

### 1.2 Remote client (phone-to-phone)

- Save unlimited **server profiles** (name, host, port, credentials, note)
- Live online/offline dot per profile via the public `/status` ping
- Browse, upload, download, rename, delete on the remote device
- Open remote files in the built-in viewers, or hand off to any installed app via `FileProvider`
- **QR onboarding**: show your server as a QR code, scan another phone's QR code to prefill a new profile

### 1.3 Live dashboard

Real (never simulated) telemetry: request count, bytes up/down, connected clients with parsed OS/browser, per-endpoint hit counts, RAM %, app-process CPU %, uptime, a 4-second-poll sparkline per metric, and a rolling recent-activity feed. Aggregates the local server plus every saved remote server into one view.

### 1.4 Website & API authoring

- **Website editor** manages the served site as a real file tree — upload multiple files, create folders, rename, delete, open
- **API endpoint editor** lets you define `(method, path) → JSON body` mappings with custom response headers and documented (informational) parameters — a tiny mock-server builder

### 1.5 On-device AI chat

- Imports a `.gguf` text model into private app storage
- Validates the GGUF header before loading (rejects vision projectors / `mmproj` files with a human-readable message)
- Token-streaming chat UI via a native `llama.cpp` bridge
- Partial-import protection (`.part` file + verified rename) and free-space preflight checks

### 1.6 Platform integration

- Foreground service with an ongoing notification showing the live address
- Optional start-on-boot receiver
- Launcher icon **swaps between online/offline artwork** by toggling two `activity-alias` components
- DayNight theme support; a distinct light "Home" theme

---

## 2. Tech Stack & Requirements

| Item | Value |
|---|---|
| Language | Java 11 (no Kotlin in the project) |
| Min SDK | 24 (Android 7.0) |
| Target / Compile SDK | 34 (Android 14) |
| AGP | 8.5.2 |
| Gradle | 9.0.0 (wrapper) |
| Package | `com.yadaventerprise.phoneserver` |
| Version | 1.0 (`versionCode 1`) |
| NDK ABI | `arm64-v8a` only |
| Persistence | `SharedPreferences` (JSON-encoded blobs) + private files — **no database, no DI framework** |
| Serialization | `org.json` (platform built-in) |
| UI | AndroidX + Material Components + View system (no Compose) |

### Dependencies

```
androidx.appcompat:appcompat:1.4.2
androidx.recyclerview:recyclerview:1.2.1
androidx.core:core:1.9.0
androidx.viewpager2:viewpager2:1.1.0
com.google.android.material:material:1.9.0
com.google.zxing:core:3.5.2                        // QR generation
com.journeyapps:zxing-android-embedded:4.3.0       // QR scanning
```

**Native**: `app/src/main/jniLibs/arm64-v8a/libllama_bridge.so` (~47 MB) prebuilt. There is no C/C++ source in the repo — the library is committed as a binary artifact. If it is missing or fails to load, `LlmEngine` degrades gracefully and the Chat tab reports "AI chat isn't available in this build" rather than crashing.

### Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Server socket + remote client |
| `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE` | Address discovery and connectivity checks |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keep the server alive with an ongoing notification |
| `POST_NOTIFICATIONS` (API 33+) | The ongoing service notification |
| `WAKE_LOCK` | `PARTIAL_WAKE_LOCK` while serving |
| `RECEIVE_BOOT_COMPLETED` | Optional auto-start |
| `WRITE_EXTERNAL_STORAGE` (≤28), `READ_EXTERNAL_STORAGE` (≤32) | Legacy storage model |
| `MANAGE_EXTERNAL_STORAGE` (API 30+) | "All files access" so uploads land in real shared folders |

---

## 3. Project Structure

```
PhoneServer/
├── build.gradle                     # AGP 8.5.2 declared here, applied in :app
├── settings.gradle                  # include ':app', FAIL_ON_PROJECT_REPOS
├── gradle.properties                # UTF-8 pinning, 2 GB heap, parallel builds
├── gradle/wrapper/                  # Gradle 9.0.0
└── app/
    ├── build.gradle                 # SDK levels, arm64-v8a filter, deps
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── jniLibs/arm64-v8a/
        │   └── libllama_bridge.so   # ~47 MB prebuilt llama.cpp JNI bridge
        ├── java/com/yadaventerprise/phoneserver/
        │   ├── ── Server core ──────────────────────────────────────
        │   │   SimpleHttpServer.java     671  socket server + router + handlers
        │   │   ServerService.java        134  foreground service, wake lock, icon
        │   │   ServerStats.java          186  live metrics, clients, activity log
        │   │   DeviceStats.java           60  RAM% and app-process CPU%
        │   │   MultipartParser.java       68  single-file multipart extraction
        │   │   ByteUtils.java             18  byte[] indexOf
        │   │   NetworkUtils.java          28  local IPv4 discovery
        │   │   BootReceiver.java          24  BOOT_COMPLETED → start service
        │   │   IconSwitcher.java          33  activity-alias icon swap
        │   │   ── Storage & config ───────────────────────────────────
        │   │   ContentStore.java         228  all paths, prefs, traversal guard
        │   │   ── Shells / shells ─────────────────────────────────────
        │   │   MainActivity.java         103  ViewPager2 + BottomNavigationView
        │   │   MainPagerAdapter.java      42  5-tab FragmentStateAdapter
        │   │   HomeFragment.java         305  local card, overview stats, remotes
        │   │   DashboardFragment.java    539  live monitoring tabs
        │   │   QrFragment.java           380  show/scan QR, recent scans
        │   │   ChatFragment.java         373  AI chat + model import
        │   │   SettingsFragment.java     145  toggles, permission status
        │   │   ── Activities ─────────────────────────────────────────
        │   │   FilesActivity.java        282  local file manager
        │   │   WebsiteFilesActivity.java 279  served-site file tree
        │   │   WebsiteEditorActivity.java 52  legacy single-box HTML editor
        │   │   ApiEditorActivity.java     76  endpoint list
        │   │   ApiEndpointEditorActivity 204  endpoint create/edit form
        │   │   ServersListActivity.java  104  saved remote servers
        │   │   AddServerActivity.java    107  profile create/edit form
        │   │   RemoteBrowseActivity.java 395  remote file manager
        │   │   AccountSettingsActivity   55  username + password
        │   │   ServerSettingsActivity    28  password only (unwired)
        │   │   AdvancedSettingsActivity  77  cache size, clear cache, reset API
        │   │   DashboardActivity.java   588  legacy full-screen dashboard
        │   │   QrCodeActivity.java       74  legacy standalone QR
        │   │   ── Viewers ────────────────────────────────────────────
        │   │   ImageViewerActivity.java   72  BitmapFactory + inSampleSize
        │   │   VideoPlayerActivity.java   49  VideoView + MediaController
        │   │   MusicPlayerActivity.java  136  MediaPlayer + SeekBar
        │   │   TextFileViewerActivity     92  read/edit (2 MB cap)
        │   │   PdfViewerActivity.java    105  platform PdfRenderer, page by page
        │   │   ── Remote client ──────────────────────────────────────
        │   │   RemoteApiClient.java      347  HttpURLConnection client
        │   │   ServerProfileStore.java   101  profiles CRUD in SharedPreferences
        │   │   ServerProfile.java         19  profile model
        │   │   RemoteEntry.java           16  remote browse entry model
        │   │   StatsSnapshot.java         38  unified local-or-remote stats DTO
        │   │   ── Models / stores ───────────────────────────────────
        │   │   ApiEndpointStore.java     140  custom endpoint CRUD
        │   │   ApiEndpointConfig.java     17  endpoint definition model
        │   │   ApiEndpoint.java           11  legacy endpoint pair
        │   │   KeyValue.java              15  header/param row
        │   │   ── Adapters ──────────────────────────────────────────
        │   │   FilesAdapter.java          67  local file rows
        │   │   RemoteEntryAdapter.java    66  remote browse rows
        │   │   ServersAdapter.java        76  server profile rows
        │   │   ApiAdapter.java            64  endpoint rows
        │   │   ── Custom views ─────────────────────────────────────
        │   │   SparklineView.java         88  CPU/RAM trend line
        │   │   TrafficChartView.java      99  up/down MB dual line
        │   │   ── AI engine ─────────────────────────────────────────
        │   │   LlmEngine.java            197  JNI facade, lock-guarded
        │   │   ModelStore.java           357  model import, GGUF validation
        │   │   FileTypeUtils.java         41  extension → MIME/viewer
        └── res/
            ├── layout/         38 XML layouts
            ├── values/         colors, themes, strings
            ├── values-night/   dark-theme colour overrides
            ├── drawable/       ~50 vector icons + launcher artwork
            ├── menu/           bottom_nav_menu.xml (5 tabs)
            └── xml/            network_security_config, file_paths,
                                backup_rules, data_extraction_rules
```

~7,900 lines of Java across 56 classes, plus 38 layouts.

---

## 4. Architecture Design

### 4.1 Architectural style

A **layered, message-passing-free monolith** with three loosely-coupled tiers. There is no DI container, no repository abstraction over the filesystem, and no ORM — the codebase deliberately optimises for *readability over cleverness*, which the core server file states explicitly:

> "One thread per connection. Simple on purpose — this runs on a phone, not a data-center box, and simple code is far easier to keep working than clever code."

```
┌──────────────────────────────────────────────────────────────────────┐
│  PRESENTATION TIER                       (Activities, Fragments,    │
│                                          Adapters, Custom Views)    │
│                                                                      │
│  MainActivity ─ ViewPager2 ─┬─ HomeFragment                          │
│   + BottomNavigationView    ├─ DashboardFragment                     │
│                             ├─ QrFragment                            │
│                             ├─ ChatFragment                          │
│                             └─ SettingsFragment                      │
│  FilesActivity · RemoteBrowseActivity · WebsiteFilesActivity · ...    │
├──────────────────────────────────────────────────────────────────────┤
│  SERVICE / ENGINE TIER                                             │
│                                                                      │
│  ServerService (foreground) ──owns──▶ SimpleHttpServer (sockets)    │
│        │                              └─ MultipartParser             │
│        │                              └─ ApiEndpointStore            │
│        │                              └─ ServerStats ◀── instrumentation
│        └──▶ LlmEngine ──JNI──▶ libllama_bridge.so (llama.cpp)       │
│                              └─ ModelStore                          │
│                                                                      │
│  RemoteApiClient (outbound HTTP client — the inverse direction)     │
├──────────────────────────────────────────────────────────────────────┤
│  STATE / UTILITY TIER                                                │
│                                                                      │
│  ContentStore   — every path, every SharedPreferences key            │
│  ServerProfileStore · ApiEndpointStore — JSON-in-prefs repositories │
│  ServerStats (singleton) · DeviceStats · NetworkUtils · FileTypeUtils│
│  ByteUtils · KeyValue · StatsSnapshot                                │
└──────────────────────────────────────────────────────────────────────┘
```

**Key rule:** the presentation tier never touches `File` paths directly for served content. It always goes through `ContentStore.resolveSafePath(...)` / `resolveSafeWebsitePath(...)`, which is the single traversal-guard chokepoint. Likewise, no activity constructs a socket path or a response — only `SimpleHttpServer` does.

### 4.2 Request lifecycle

```
  Browser / curl / RemoteApiClient
            │
            ▼
   ┌─── acceptLoop()  [Thread "PhoneServer-Accept"] ──────────────────┐
   │  ServerSocket.accept()  ─►  new Thread("PhoneServer-Client")      │
   └───────────────────────────────────────────────────────────────────┘
            │
            ▼
   ┌─── handleClient(Socket) ─────────────────────────────────────────┐
   │  1. readRequest()   byte-safe line reader (ISO-8859-1)            │
   │                    headers lowercased; body read by Content-Length │
   │  2. CountingOutputStream wraps the socket for real byte counting  │
   │  3. route(req, out)                                               │
   │  4. ServerStats.recordRequest(ip, ua, path, reqBytes, respBytes) │
   └───────────────────────────────────────────────────────────────────┘
            │
            ▼
   ┌─── route() ── ordered if/else chain ─────────────────────────────┐
   │                                                                  │
   │  ┌── PUBLIC (no password) ──────────────────────────────────┐    │
   │  │ "/" or "/index.html"      → serveWebsite()              │    │
   │  │ "/status"                 → inline JSON                  │    │
   │  │ other, unmatched          → serveWebsiteAsset() (public) │    │
   │  └──────────────────────────────────────────────────────────┘    │
   │                                                                  │
   │  ┌── PASSWORD GATE ─────────────────────────────────────────┐    │
   │  │ if path ∈ {/api/browse, /files*, /api/mkdir, /api/delete,│    │
   │  │            /api/rename, /api/upload, /api/stats}         │    │
   │  │      → isAuthorized() ?  no → 401 + WWW-Authenticate      │    │
   │  └──────────────────────────────────────────────────────────┘    │
   │                                                                  │
   │  ┌── PROTECTED HANDLERS ────────────────────────────────────┐    │
   │  │ serveBrowse / serveStats / handleMkdir / handleDelete    │    │
   │  │ handleRename / handleUpload / serveFileListingHtml        │    │
   │  │ serveFile                                               │    │
   │  └──────────────────────────────────────────────────────────┘    │
   │                                                                  │
   │  ┌── CUSTOM API (public by design) ─────────────────────────┐    │
   │  │ path startsWith "/api/"  → serveCustomApi()              │    │
   │  │   match(method,path) → 200 + user body + user headers    │    │
   │  │   pathExists only      → 405 Method Not Allowed           │    │
   │  │   otherwise            → 404                             │    │
   │  └──────────────────────────────────────────────────────────┘    │
   │                                                                  │
   │  else → serveWebsiteAsset() ?: 404                              │
   └──────────────────────────────────────────────────────────────────┘
```

### 4.3 The "measured twice" design

This is the most distinctive architectural decision in the codebase. **Every number the dashboard shows is a real measurement.** There are two independent paths that converge on identical UI:

```
  LOCAL                                  REMOTE
  ─────                                  ──────
  Socket request                         RemoteApiClient.getStats()
      ↓                                       ↓
  CountingOutputStream                    HTTP GET /api/stats
  (counts actual bytes written)                ↓
      ↓                                   JSON body
  ServerStats.recordRequest()                  ↓
  (AtomicLong accumulators,             StatsSnapshot DTO
   ConcurrentHashMap per path,                  ↓
   ClientInfo per IP)              ┌───────────┴───────────┐
      ↓                            │                       │
  /api/stats builds JSON      DashboardFragment     DashboardActivity
      │                       (fragment, live)     (legacy, unregistered)
      └──────────────┬────────────────────────────┘
                     ▼
           ServerStats.get()  or  StatsSnapshot
                     ▼
           SparklineView / TrafficChartView
```

`CountingOutputStream` is the key to "no fake numbers" — it is a thin `OutputStream` decorator that increments a counter on every `write`, and it is the *only* way bytes leave a response. There is no estimate, no `File.length()` approximation.

Two classes document deliberately honest limits rather than fabricating data:

- **`DeviceStats.getAppCpuPercent()`** reports *this app process's* CPU (`Process.getElapsedCpuTime()` delta over wall-clock, normalised by core count), **not** device-wide CPU, because Android 8+ blocks system-wide CPU reads without root. The Javadoc says so explicitly.
- **`ServerStats.parseUserAgent()`** is a substring match, described as "very lightweight... just enough to show a friendly OS/browser guess."

### 4.4 Threading model

There is **no** `ExecutorService`, coroutines, `AsyncTask`, or `ViewModel` in the UI layer. The convention is:

```java
new Thread(() -> { /* blocking work */ handler.post(() -> { /* touch views */ }); })
                    ↑ always a named Thread for server/LLM work, for logcat attribution
```

| Component | Thread strategy | Rationale |
|---|---|---|
| Socket accept | 1 dedicated long-lived thread | Single accept loop, trivially correct |
| Per-connection | 1 thread per connection, `try (Socket s = ...)` | Simplicity; closes the socket on exit |
| HTTP client (remote) | `new Thread()` per call | All blocking I/O, documented "must be called from a background thread" |
| LLM token generation | Single-thread `ExecutorService` + `ReentrantLock` | Serialises generation; the lock is a correctness requirement, not a style choice (see §8.2) |
| LLM callbacks | `Handler(Looper.getMainLooper())` | Tokens delivered on the main thread |
| UI polling | `Handler.postDelayed` self-reposting runnable | 2 s (Home), 3 s (QR), 4 s (Dashboard); removed in `onPause` |

**Known cost of this model:** no cancellation on lifecycle change and no bound on concurrent threads. `DashboardActivity.pollRemote` and `ServersListActivity` spawn one thread per server *per poll tick*, and `RemoteBrowseActivity` keeps uploading into a dead destination after a rotation. This is listed in §12.

### 4.5 Storage layout

`ContentStore` is the single source of truth for every path. It degrades gracefully when "all files access" is not granted:

| Purpose | With `MANAGE_EXTERNAL_STORAGE` | Without it (fallback) |
|---|---|---|
| Served website | — (always private) | `filesDir/website/` |
| Shared files root (`/files`, `/api/browse`, uploads) | `Environment.getExternalStorageDirectory()` — i.e. the whole device, same view Gallery sees | `getExternalFilesDir("public")` |
| Downloaded remote files | `Download/PhoneServer/` | `getExternalFilesDir("downloads")` |
| Model files | — (always private) | `filesDir/models/` |
| View cache (for `FileProvider` handoff) | — (always private) | `cacheDir/view_cache/` |

### 4.6 Path-traversal defence

`ContentStore.resolveSafePathUnder(root, relative)` is the one chokepoint:

1. Strip leading `/`
2. Empty → return `root` itself
3. `new File(root, relativePath)`
4. Compare `getCanonicalPath()` of target against `getCanonicalPath()` of root
5. Must be **equal** to root, or start with `root + File.separator` — otherwise `null`

Canonicalisation (not string prefix matching) means `..` segments, symlinks and `//` collapsing are all resolved *before* the check, so `../../etc/passwd` is rejected.

Handlers additionally apply name-level validation before touching the filesystem:
- `handleMkdir` / `handleRename` reject names that are empty, contain `/`, or contain `..`
- `MultipartParser.extractFilename` strips any directory component the browser may have sent, keeping only the last path segment
- `writeResponse` strips `Content-Length` and `Connection` from user-defined headers so a custom endpoint cannot corrupt response framing

**Known gap:** the guard is bypassed when a *filename* (rather than a path) is appended after resolution — see §12.

### 4.7 Auth model

```
  isAuthorized(request)
        │
        ├─ stored password empty?  ──────────────▶ ALWAYS TRUE (open LAN access)
        │
        ├─ header "X-Auth-Password" matches? ────▶ authorised
        ├─ query param "pw" matches? ────────────▶ authorised
        └─ "Authorization: Basic base64(user:pass)"
                 └─ base64-decode, split on first ':'
                      └─ password half matches? ─▶ authorised
                                             else ─▶ 401 + WWW-Authenticate: Basic
```

Three credential channels exist so that (a) browsers get a native Basic-Auth prompt, (b) `curl` can use a header, and (c) the app's own `RemoteApiClient` can use a header while `download()` additionally appends `?pw=` for clients that cannot set headers. Comparison is plaintext `String.equals`.

### 4.8 Response framing

`writeResponse` builds headers by hand and always sets:

```
HTTP/1.1 <code> <reason>
Content-Type: <type>
Content-Length: <exact byte count>
Connection: close
Access-Control-Allow-Origin: *
[user-defined headers, minus content-length/connection]
```

`Connection: close` on every response means one request per TCP connection — the correct trade-off for a phone server, and it removes keep-alive timeout management entirely. `Content-Length` is computed from the *byte array* length, never from a `String.length()`, which matters because `guessMime` returns UTF-8 content and non-ASCII file bytes would otherwise be miscounted.

### 4.9 Icon switching

A launcher icon cannot be swapped at runtime, so the manifest declares **two `activity-alias` components** pointing at the same `MainActivity`, differing only in `android:icon`:

```xml
<activity-alias android:name=".AliasOnline"  android:targetActivity=".MainActivity"
                android:icon="@drawable/ps_online"  android:enabled="false" />
<activity-alias android:name=".AliasOffline" android:targetActivity=".MainActivity"
                android:icon="@drawable/ps_offline" android:enabled="true" />
```

`IconSwitcher.setOnline(context, online)` enables one and disables the other via `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)`. `ServerService` calls it on both start and stop. It is wrapped in a catch-all because it is cosmetic and must never take down the server.

---

## 5. HTTP API Reference

Base URL: `http://<device-ip>:8080` · Port is a compile-time constant `ServerService.PORT = 8080`

### 5.1 Public endpoints

#### `GET /`
Returns `files/website/index.html` as `text/html; charset=utf-8`, or a built-in default page if the file does not exist.

#### `GET /index.html`
Alias for `/`.

#### `GET /<asset>`
Any other file inside `files/website/` — `style.css`, `app.js`, `logo.png`. Resolved through the website-root traversal guard. Public, so that relative references inside a user's `index.html` resolve correctly. Returns 404 if no such file exists.

#### `GET /status`
```json
{"server":"android-phone","status":"online","time":"2026-09-29 10:28:41"}
```
No password required — this is the endpoint `RemoteApiClient.isOnline()` pings with a 2.5 s timeout.

#### `GET|POST|PUT|DELETE /api/<custom-path>`
User-defined endpoints from the API editor. Matching is exact on both path and method. Response is always `application/json` with the user-supplied body plus user-supplied headers.

- Path + method match → `200`
- Path matches, method does not → `405 {"error":"this endpoint doesn't accept that method"}`
- No endpoint at path → `404 {"error":"no endpoint at this path"}`

### 5.2 Password-protected endpoints

Supply credentials via **any** of: `X-Auth-Password: <pw>` header, `?pw=<pw>` query parameter, or HTTP Basic auth.

#### `GET /files`
Minimal HTML index of the shared files root. Links are `href="/files/<name>"`.

#### `GET /files/<path>`
Downloads a single file. Content type from the server's own `guessMime` extension table. 404 if missing or not a regular file.

#### `GET /api/browse?path=<relative-path>`
```json
{
  "path": "Pictures/trip",
  "entries": [
    {"name":"beach.jpg","isDir":false,"size":2048113,"modified":1759000000000},
    {"name":"raw","isDir":true,"size":0,"modified":1759000000000}
  ]
}
```
404 `{"error":"folder not found"}` if the path does not resolve to an existing directory; 400 on an unsafe path.

#### `POST /api/mkdir`
```json
{"path":"Pictures","name":"trip"}
```
`{"status":"ok"}` · 400 `{"error":"invalid folder name"}` (empty, contains `/`, contains `..`) · 400 `{"error":"invalid path"}`

#### `POST /api/delete`
```json
{"path":"Pictures/trip"}
```
Recursive for directories. `{"status":"ok"}` · 404 `{"error":"not found"}`

#### `POST /api/rename`
```json
{"path":"Pictures/a.jpg","newName":"b.jpg"}
```
`{"status":"ok"}` · 400 `{"error":"invalid name"}` · 404 `{"error":"not found"}`

#### `POST /api/upload?path=<target-folder>`
`Content-Type: multipart/form-data; boundary=...`, single part named `file`.

Response: `{"status":"ok","name":"<filename>"}`

Side effects: the file is written, then handed to `MediaScannerConnection.scanFile` so Gallery/Music/Video index it immediately, then recorded in `ServerStats` as an `upload` activity event. 400 if the content type is not multipart, or if no file part is found.

#### `GET /api/stats`
```json
{
  "requestCount": 42,
  "bytesUp": 1048576,
  "bytesDown": 52428800,
  "activeClients": 2,
  "ramUsedPercent": 61,
  "appCpuPercent": 4,
  "uptimeMillis": 3600000,
  "clients": [
    {"ip":"192.168.1.42","os":"Windows","browser":"Chrome","lastSeen":1759000000000}
  ],
  "topEndpoints": [ {"path":"/files/*","count":18} ],
  "recentActivity": [ {"type":"upload","message":"File uploaded: a.pdf (1.2 MB)","time":1759000000000} ]
}
```

Notes on the semantics:
- `activeClients` = clients seen within the last **5 minutes** (`CLIENT_ACTIVE_WINDOW_MILLIS`)
- `/files/*` is **bucketed** in `topEndpoints` so one file can't flood the chart
- `recentActivity` is capped at **30** entries, newest first
- `appCpuPercent` is a *differential* measure — the first call after server start returns 0
- All stats reset when the server starts

#### 401 response
```json
{"error":"unauthorized"}
```
With `WWW-Authenticate: Basic realm="PhoneServer"`, which makes a browser show its native credential prompt.

---

## 6. Data & Persistence Model

No Room, no SQLite, no DataStore. Every structured collection is a JSON array in a `SharedPreferences` string key.

| Prefs file | Key | Contents | Written by |
|---|---|---|---|
| `server_config` | `access_password` | Local server password (plaintext) | `AccountSettingsActivity`, `ServerSettingsActivity` |
| `server_config` | `auth_username` | Display-only username (plaintext) | `AccountSettingsActivity` |
| `server_config` | `start_on_boot` | boolean, default `false` | `SettingsFragment` |
| `server_config` | `allow_remote_access` | boolean, default `true` | `SettingsFragment` — **never read by the server** |
| `server_config` | `show_hidden_files` | boolean, default `false` | `SettingsFragment` — **never read by the server** |
| `server_profiles` | `profiles_json` | `JSONArray` of `{id, name, ip, port, username, password, description}` | `AddServerActivity`, `ServerProfileStore` |
| `api_endpoint_configs` | `endpoints_json` | `JSONArray` of `{path, method, body, headers[], params[]}` | `ApiEndpointEditorActivity`, `ApiEndpointStore` |
| `api_endpoints` | *(legacy)* | Reserved; `ContentStore.getApiPrefs()` exists but is unused | — |

### Model classes

| Class | Shape | Notes |
|---|---|---|
| `ServerProfile` | `id` (UUID), `name`, `ip`, `port=8080`, `username`, `password`, `description`, `baseUrl()` | Keyed by generated UUID |
| `ApiEndpointConfig` | `path` (normalised to `/api/…`), `method` (default `GET`), `headers: List<KeyValue>`, `params: List<KeyValue>`, `body` (default `"{}"`) | **Keyed by the `(path, method)` pair, not an id** |
| `RemoteEntry` | `name`, `isDir`, `size`, `modified` | Parsed from `/api/browse` |
| `StatsSnapshot` | scalars + `List<ClientEntry>` + `List<EndpointEntry>` + `List<ActivityEntry>` | The DTO that lets the dashboard treat local and remote servers identically |
| `KeyValue` | `key`, `value` | Header/param rows in the editor |
| `StatsSnapshot.ActivityEntry` | `type` ∈ `start` \| `client` \| `upload` \| `api` \| `delete`, `message`, `time` | |

**Design consequence of `(path, method)` keying:** editing an endpoint's path creates a *new* entry rather than renaming the old one. `ApiEndpointStore.save(config, originalPath, originalMethod)` works around this by matching on the original coordinates before replacing.

### In-memory state (not persisted)

| State | Holder | Reset trigger |
|---|---|---|
| All traffic counters | `ServerStats.INSTANCE` (singleton, `AtomicLong`/`ConcurrentHashMap`) | `reset()` on server start |
| Active client list | `ServerStats` | Cleared on reset |
| Recent activity | `ServerStats` (bounded `Deque`, cap 30) | Cleared on reset |
| Server uptime | `ServerService.startTimeMillis` (static) | Zeroed on stop |
| DeviceStats CPU baseline | `DeviceStats.lastCpuTimeMillis` | Process restart |
| QR bitmap | `QrFragment.currentQrBitmap` | Fragment lifecycle |
| Chat transcript | `ChatFragment.history` + `onSaveInstanceState` | View destroy, capped at 20,000 chars |

---

## 7. UI Architecture

### 7.1 Navigation

Single-`Activity` shell with a 5-tab bottom navigation bar:

```
MainActivity (Theme.PhoneServer.Home — light)
  ViewPager2  +  FragmentStateAdapter (offscreenPageLimit = 4)
       │
       ├── 0  HomeFragment          local server card, 3 remote cards, quick actions
       ├── 1  DashboardFragment     local + remote live telemetry tabs
       ├── 2  QrFragment            "My QR" / "Scan QR" internal tabs + recent scans
       ├── 3  ChatFragment          AI chat, model import
       └── 4  SettingsFragment      toggles, permission status, advanced
              │
              └──▶ child Activities (all exported="false")
                    FilesActivity · WebsiteFilesActivity · ApiEditorActivity
                    ApiEndpointEditorActivity · ServersListActivity
                    AddServerActivity · AccountSettingsActivity
                    AdvancedSettingsActivity · RemoteBrowseActivity
                    ImageViewerActivity · VideoPlayerActivity
                    MusicPlayerActivity · TextFileViewerActivity
                    PdfViewerActivity
```

`MainActivity` saves `STATE_SELECTED_PAGE` in `onSaveInstanceState`. Its Javadoc records the reason: without it, "every activity recreation (rotation, theme/font change, or the system reclaiming this process while a large model is mapped) started a fresh adapter at page 0" — the 47 MB native model makes process reclaimation a routine event, not an edge case.

A `suppressNavCallback` flag breaks the `ViewPager2 ↔ BottomNavigationView` feedback loop, so programmatic page changes don't re-trigger a scroll animation.

### 7.2 Theming

Two independent palettes, deliberately:

| | `Theme.PhoneServer` (default, dark) | `Theme.PhoneServer.Home` (light) |
|---|---|---|
| Parent | `Theme.MaterialComponents.DayNight.NoActionBar` | `Theme.MaterialComponents.Light.NoActionBar` |
| Background | `#0D0F14` | `#FAFAFA` |
| Accent | `#2979FF` | `#2E7D32` (green) |
| Applied to | everything except `MainActivity` | `MainActivity` only (per-activity override in the manifest) |

`values-night/colors.xml` provides dark overrides. `values/colors.xml` keeps the `home_*` palette separate so the Home redesign did not disturb the dark theme used by file browsers and viewers.

### 7.3 Built-in file viewers

`FileTypeUtils.classify(filename)` maps an extension to one of six `Kind` values, and each Activity dispatches on it:

| Kind | Extensions | Viewer | Technique |
|---|---|---|---|
| `IMAGE` | jpg, jpeg, png, gif, webp, bmp | `ImageViewerActivity` | Two-pass `BitmapFactory`: `inJustDecodeBounds`, then `inSampleSize` — avoids OOM on large photos |
| `VIDEO` | mp4, 3gp, mkv, webm, mov, m4v | `VideoPlayerActivity` | `VideoView` + `MediaController` |
| `AUDIO` | mp3, wav, m4a, aac, ogg, flac | `MusicPlayerActivity` | `MediaPlayer.prepareAsync()` + `SeekBar` + 500 ms progress ticker |
| `TEXT` | txt, md, json, csv, log, xml, html, css, js, java, gradle | `TextFileViewerActivity` | 2 MB cap guard; editable when local |
| `PDF` | pdf | `PdfViewerActivity` | Platform `PdfRenderer`, one `Bitmap` per page, explicit `page.close()` |
| `OTHER` | everything else | — | `FileProvider` URI + `ACTION_VIEW` chooser |

### 7.4 Custom views

`SparklineView` and `TrafficChartView` are hand-rolled `View.onDraw` implementations (`Canvas`/`Path`/`Paint`) — no charting library. `SparklineView` draws a single trend line with a translucent fill; `TrafficChartView` plots upload/download MB as two lines over a 4-band grid with MB/GB axis labels. Both take data via `setValues`/`setData` and call `invalidate()`.

---

## 8. On-device AI Subsystem

### 8.1 Component shape

```
  ChatFragment
      │  registerForActivityResult(OpenDocument)
      ▼
  ModelStore.importModel(context, uri)          ── background thread ──
      │  1. takePersistableUriPermission (done in the Fragment first)
      │  2. free-space preflight  (StatFs vs. source length + 16 MB headroom)
      │  3. copy → models/<name>.gguf.part   (64 KB buffer, fsync)
      │  4. verify copied length == source length
      │  5. GgufInfo.read() header validation
      │  6. rename .part → final (fallback: copy+delete)
      ▼
  ModelStore.loadIntoEngine(context)
      │  re-validates; on a corrupt stored model it deletes and asks for re-import
      ▼
  LlmEngine.loadModel(path)  ──JNI──▶ nativeLoadModel ──▶ llama_context
      ▼
  LlmEngine.generate(prompt, callback)
      │  single-thread executor + generationLock
      │  nativeGenerate(handle, prompt, TokenSink)
      │     └─ TokenSink.onToken() per token
      │           └─ Handler(main).post → deliverToken() → callback.onToken()
      ▼
  ChatFragment.queueToken() → buffer → single flush Runnable → render()
```

### 8.2 Concurrency correctness (this is the subtle part)

`LlmEngine` guards **all** native lifecycle operations with one `ReentrantLock`, and the Javadoc explains exactly why:

> "The native unload frees the `llama_context` and model, so running it while a generation is still decoding from that context reads freed memory and segfaults. `loadModel` used to take only the instance monitor, which `generate()` never took, so a re-import from the AI tab could free the context underneath a running answer."

Consequences, all deliberate and commented in-code:

1. `loadModel()` holds `generationLock` for the **entire** swap (unload + load), not just the load.
2. `generate()` reads `nativeHandle` **inside** the lock and copies it to a local `final` — checking `modelLoaded` up front and using the field later left a use-after-free window.
3. `nativeUnloadModel` is wrapped in `try/catch (Throwable)` because an abort there kills the process with no log line at all.
4. All three callback dispatchers (`deliverToken`/`deliverComplete`/`deliverError`) swallow `Throwable`, because a token stream outlives the view that started it and an exception from a destroyed fragment would otherwise take down the whole process — including the file server.

### 8.3 GGUF validation

`ModelStore.GgufInfo` is a ~120-line `RandomAccessFile` reader that parses **only** the header key/value block — no tensor is touched, so it costs a few KB of reads even on a 600 MB file. It defends against:

- **Non-GGUF magic** → `isGenuineGguf = false`
- **Truncated header** → `BadHeaderException` on implausible lengths (`string > 4096 B`, `kvCount > 4096`, negative counts, array count > 10 M, value nesting depth > 4)
- **Version outside 2–3**
- **Vision projectors.** This is the notable one:

> "The 600 MB file in `Download/Office Kit` is named `smollm2-135m-instruct-q8_0.gguf` but its header says it is an `mmproj` — a CLIP vision projector for a Qwen3-VL multimodal model, with no text decoder. Handing that to the inference engine produced an opaque failure that looked like the app dying, so it is now rejected up front by name."

Detection: `general.type == "mmproj"` or `general.architecture == "clip"` → rejected with a message naming the model and asking for the matching language-model `.gguf`.

### 8.4 UI robustness

`ChatFragment` treats "a view may vanish mid-generation" as the normal case:

- A `viewAlive` flag plus nulled references in `onDestroyView`; **every** view touch re-checks
- `pendingTokens` buffer collects tokens while no view is attached and flushes when the view returns
- Tokens are **coalesced** onto one pending `Runnable` rather than one `Handler.post` per token — per-token `setText` + relayout was stalling long enough for the system to reclaim the process
- `finishGeneration()` drains the buffer *before* appending the trailing newline, since the last tokens may still be buffered
- `history` is capped at 20,000 chars and persisted via `onSaveInstanceState`; trimming triggers a full `setText` rather than an append
- Auto-load and import run on **named** threads (`chat-model-autoload`, `chat-model-import`) so a crash is attributable in logcat — an uncaught throwable on a bare `Thread` goes to the default handler and kills the process, taking the file-server UI with it
- `getActivity()` null checks are re-done inside `postToUi`, because it can go null between the check and `runOnUiThread` on a worker thread

---

## 9. Security Model

### 9.1 Posture

PhoneServer is a **local-network** service. It is not hardened for the public internet, and it does not pretend to be:

- `usesCleartextTraffic="true"` + `network_security_config.xml` with `cleartextTrafficPermitted="true"`
- All traffic is plain HTTP on port 8080
- **Default posture with no password set is unauthenticated LAN-wide read/write access to the device's shared storage**, including recursive delete. The Settings UI says so explicitly: *"No password set - open access on your Wi-Fi"*
- `MANAGE_EXTERNAL_STORAGE` is requested, which is a very high-privilege grant

### 9.2 Controls in place

| Control | Implementation |
|---|---|
| Path traversal | `resolveSafePathUnder` canonical-path containment at a single chokepoint |
| Name injection on mkdir/rename | Rejects empty, `/`, and `..` |
| Multipart filename stripping | `MultipartParser.extractFilename` keeps only the last path segment |
| Response framing integrity | `Content-Length` / `Connection` stripped from user-defined headers |
| Server stats protected | `/api/stats` is inside the password gate — client IPs are not public |
| 401 challenge | `WWW-Authenticate: Basic realm="PhoneServer"` for browser-native prompts |
| Activity exposure | Every Activity is `exported="false"`; only `MainActivity` and the two launcher aliases are `exported="true"` |
| Component toggling | `DONT_KILL_APP` on icon swaps so the UI never dies from a cosmetic operation |
| Server never breaks on cosmetics | `IconSwitcher` and every `SharedPreferences` write are wrapped in catch-alls |
| Destructive-action confirmation | Delete/clear dialogs in `FilesActivity`, `WebsiteFilesActivity`, `ApiEditorActivity`, `AdvancedSettingsActivity` |

### 9.3 Weaknesses

| Issue | Where | Impact |
|---|---|---|
| Plaintext credentials at rest | `access_password` and `auth_username` in `server_config`; per-profile `password` in `server_profiles` | SharedPreferences XML is world-readable to root/backup. No `EncryptedSharedPreferences`, no hashing |
| Password in a URL query | `RemoteApiClient.download()` appends `?pw=` | Leaks into server logs, browser history, proxy logs, and any `Referer` |
| Plaintext compare | `isAuthorized` uses `String.equals` | No constant-time compare; not a practical concern on a LAN, but noted |
| Username never checked | `getUsername()` is stored and displayed but `isAuthorized` only compares passwords | Basic auth accepts any username; a client sending `wronguser:correctpass` succeeds |
| Custom endpoints bypass the gate | `serveCustomApi` is reached *after* the auth block and is public by design | A user-defined `/api/*` endpoint is world-readable on the LAN. Documented in the class Javadoc, but a foot-gun |
| Endpoint shadowing | `ApiEndpointEditorActivity` does not validate against built-in paths | A user endpoint at `/api/upload` can never be reached (built-ins are matched first) — confusing, not exploitable |
| Traversal guard bypass on filenames | `WebsiteFilesActivity.copyIntoWebsiteFolder` and `RemoteBrowseActivity` append an **unsanitised** filename to a path that was validated *before* the append | The clearest path-traversal weakness in the codebase (§12) |
| `file_paths.xml` exposes `/` | `<root-path name="root" path="/" />` | `FileProvider` can hand out a URI for any file the app can read. Only reachable from in-app, non-exported code |

**Recommendations if this were to run beyond a trusted LAN:** TLS termination, `EncryptedSharedPreferences` or at minimum a salted hash, drop the `?pw=` channel, close the filename-traversal gap, and scope `file_paths.xml` to `Download/PhoneServer` and `cacheDir/view_cache` instead of `/`.

---

## 10. Build & Development

### 10.1 Prerequisites

- JDK 17 (required by AGP 8.5.2; source/target compatibility is Java 11)
- Android SDK Platform 34 + Build-Tools
- The Gradle 9.0.0 wrapper downloads itself

### 10.2 Commands

```bash
# Debug build → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Release build (currently minifyEnabled false — no shrinking/obfuscation)
./gradlew assembleRelease

# Install on a connected device
./gradlew installDebug

# Clean rebuild
./gradlew clean assembleDebug

# Lint
./gradlew :app:lintDebug
```

Built and developed with **AndroidIDE** (the `gradle.properties` header reads `#AndroidCS: enforce UTF-8 & locale for Gradle daemon`, and `.acside/` is git-ignored).

### 10.3 Gradle configuration notes

- `android.nonTransitiveRClass=true` — resources referenced as `R.color.*` within the app namespace only
- `org.gradle.jvmargs=-Xmx2048m` with `file.encoding` and `sun.jnu.encoding` pinned to UTF-8 and locale forced to `en-US` — prevents the non-ASCII path and string corruption that the header comment calls out
- `org.gradle.parallel=true`, `org.gradle.daemon.idletimeout=10800000` (3 h) to survive long-lived IDE sessions
- `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` — dependency repos are declared only in `settings.gradle`
- `minifyEnabled false` on release. `proguard-rules.pro` exists but is unused. **If you enable R8, `libllama_bridge.so`'s JNI entry points need keep rules** (`nativeLoadModel`, `nativeUnloadModel`, `nativeGenerate`, and the `TokenSink` interface), or chat breaks in release only.

### 10.4 Testing

There are **no unit or instrumentation tests** in this project. There is also no CI configuration. Verification is manual: start the server, hit it with `curl`, watch the Dashboard numbers change. Adding tests would need a `testImplementation` JUnit dependency and a test source set, neither of which currently exist.

### 10.5 Quick manual smoke test

```bash
# 1. Start the server from the app
# 2. Public — no password needed
curl http://<phone-ip>:8080/status
curl http://<phone-ip>:8080/

# 3. Protected — supply the password
curl -u :mypassword http://<phone-ip>:8080/api/browse?path=Pictures
curl -H "X-Auth-Password: mypassword" http://<phone-ip>:8080/api/stats

# 4. Write operations
curl -X POST -H "X-Auth-Password: mypassword" -H "Content-Type: application/json" \
     -d '{"path":"","name":"TestFolder"}' http://<phone-ip>:8080/api/mkdir

curl -X POST -H "X-Auth-Password: mypassword" \
     -F "file=@/path/to/photo.jpg" "http://<phone-ip>:8080/api/upload?path=TestFolder"

# 5. Verify the counters moved on the Dashboard tab
```

---

## 11. Design Decisions & Rationale

The codebase is unusually explicit about *why* it is built the way it is — most non-obvious decisions carry a Javadoc paragraph explaining the failure they replaced. The most important ones:

### 11.1 Hand-rolled HTTP server instead of NanoHTTPD

**Decision:** 671 lines of `ServerSocket` + `InputStream` + hand-built response headers, zero server dependencies.
**Why:** the server is the product. A dependency would be a supply-chain and size cost for something the app fully controls; the file is self-documenting and easy to repair. The router is a flat `if/else` chain precisely so it is auditable at a glance.

### 11.2 Byte-safe request parsing

**Decision:** `readLine` reads byte-by-byte with an explicit `ISO-8859-1` decode, and the body is read by `Content-Length` via `readExactly`.
**Why:** reading the stream through a `Reader` would corrupt binary upload bodies. The `ISO-8859-1` decode is byte-preserving by definition, so no information is lost in the request line or headers.

### 11.3 Counting output rather than estimating traffic

**Decision:** `CountingOutputStream` wraps the socket's `OutputStream`; `handleClient` feeds the counter to `ServerStats`.
**Why:** "bytes served" is a headline dashboard number. Measuring the actual socket writes is the only way it can be honest. The same discipline appears in `DeviceStats`, which refuses to report a device CPU figure it cannot legitimately obtain.

### 11.4 `Connection: close` on every response

**Decision:** always close, never keep-alive.
**Why:** removes keep-alive timeout handling, socket-pool state, and half-open connection bugs from a thread-per-connection model. On a phone with a handful of LAN clients, the handshake cost is irrelevant.

### 11.5 A single traversal chokepoint

**Decision:** every served path resolves through `ContentStore.resolveSafePath` / `resolveSafeWebsitePath`.
**Why:** one function to audit is worth more than twenty call sites each doing their own substring checks. Canonical-path comparison (not string prefix) closes `..` and symlink escapes in one step.

### 11.6 Graceful degradation over failure

**Decision:** `getPublicFilesDir()` falls back to a private sandbox when `MANAGE_EXTERNAL_STORAGE` is not granted; `LlmEngine` reports unavailability instead of throwing when the `.so` fails to load; `IconSwitcher` swallows everything.
**Why:** an Android app that crashes on a missing optional grant is unusable. Every optional capability degrades to a clearly-labelled reduced mode.

### 11.7 Background work on background threads, always

**Decision:** every blocking call — HTTP, file copy, GGUF parse, model load — runs off the main thread; the *reversed* mistakes are noted as bugs in comments (main-thread file copies in `WebsiteFilesActivity`, main-thread read/write in `WebsiteEditorActivity`).
**Why:** consistent with Android's strict-mode model, and it makes the main-thread violations obvious against the norm.

### 11.8 Descriptive names, and comments that explain history

**Decision:** no abbreviations (`acceptLoop`, `resolveSafePathUnder`, `CountingOutputStream`, `pendingTokens`); comments explain *what broke and why the code is shaped this way*, not what the line does.
**Why:** this is a codebase maintained by reading it. `ContentStore`, `SimpleHttpServer`, `LlmEngine`, `ModelStore`, `ChatFragment` and `DeviceStats` are effectively annotated design documents.

### 11.9 Two activity-aliases for a live status icon

**Decision:** declare two launcher entries and flip their `enabled` state.
**Why:** it is the only supported way to change a launcher icon at runtime on Android. The `enabled` attribute, not the icon, is the switch.

---

## 12. Known Limitations & Technical Debt

Ordered roughly by how much they matter.

### 12.1 Correctness and security

| # | Issue | Where | Impact |
|---|---|---|---|
| 1 | **Filename appended after the traversal check.** `resolveSafePathUnder` validates the base directory, but `WebsiteFilesActivity.copyIntoWebsiteFolder` and `RemoteBrowseActivity`'s download/view paths then append an unsanitised `ContentResolver` `DISPLAY_NAME` or server-supplied `entry.name` via `new File(dir, name)`. A name containing `../` escapes the root. | `WebsiteFilesActivity`, `RemoteBrowseActivity` | Path traversal. Highest-priority fix: re-resolve with `resolveSafePath(ctx, dirPath + "/" + name)` instead of appending to a `File` |
| 2 | `allow_remote_access` and `show_hidden_files` are written by the UI but **never read by the server** | `ContentStore`, `SettingsFragment` | Two settings switches do nothing. `ContentStore.getApiPrefs()` is likewise dead |
| 3 | `getUsername()` is stored and displayed but never verified in `isAuthorized` | `AccountSettingsActivity`, `SimpleHttpServer` | Basic auth accepts any username |
| 4 | Password sent as `?pw=` query param on download | `RemoteApiClient.download` | Credential leaks into logs/history |
| 5 | Plaintext credentials in `SharedPreferences` | `ContentStore`, `ServerProfileStore` | No encryption or hashing at rest |
| 6 | User-defined `/api/*` endpoints are public | `SimpleHttpServer.route` | By design and documented, but a user-defined endpoint is readable by anyone on the LAN |
| 7 | `<root-path path="/" />` in `file_paths.xml` | `res/xml/file_paths.xml` | `FileProvider` can address any app-readable file. Scope it to the downloads + view-cache dirs |
| 8 | No rate limiting, no lockout, no per-IP throttle on the password check | `SimpleHttpServer.isAuthorized` | Brute-forceable on a LAN. Low severity for the threat model, high if port-forwarded |
| 9 | HTTP response headers are not escaped for `\r\n` when user-defined headers are written | `SimpleHttpServer.writeResponse` | A header value containing CRLF would inject a header. Only reachable by the user who authored the endpoint |

### 12.2 Threading and lifecycle

| # | Issue | Where |
|---|---|---|
| 10 | Unbounded thread spawning: one thread per server per poll tick, no in-flight guard. A slow offline server accumulates threads and can deliver out-of-order results that overwrite fresher data | `DashboardActivity.pollRemote`, `ServersListActivity`, `HomeFragment`, `QrFragment` |
| 11 | No cancellation on lifecycle change: a rotation mid-upload still writes the file; polling continues against a destroyed activity; results are posted back with no `isFinishing()`/`isDestroyed()` check | `RemoteBrowseActivity`, all pollers |
| 12 | Main-thread I/O: file copies in `WebsiteFilesActivity`; read/write in `WebsiteEditorActivity`; `TextFileViewerActivity` read (up to 2 MB); `PdfViewerActivity.renderPage`; `AdvancedSettingsActivity.folderSize` | those classes |
| 13 | No `ViewModel` / `SavedStateHandle` anywhere. `DashboardActivity` throws away all chart history on every `onResume`; PDF page, playback position and progress scroll do not survive rotation | all Activities |
| 14 | Player activities don't pause on `onPause`; `MusicPlayerActivity`'s progress runnable is only removed in `onDestroy`; `VideoPlayerActivity` uses `Uri.fromFile` instead of `FileProvider` | `MusicPlayerActivity`, `VideoPlayerActivity` |

### 12.3 Dead and duplicated code

| # | Issue | Detail |
|---|---|---|
| 15 | **`DashboardActivity` (588 lines) is not declared in the manifest** and is never started — fully dead. `DashboardFragment` (539 lines) is the live implementation, and it duplicates most of the activity's logic | Delete the activity, or wire it up |
| 16 | **`QrCodeActivity` and `ServerSettingsActivity` are not declared in the manifest** — dead. Their functionality lives in `QrFragment` and `AccountSettingsActivity` respectively | Delete, or declare |
| 17 | `ServerSettingsActivity` and `AccountSettingsActivity` both write `access_password`, and neither can be reached | Merge |
| 18 | `ContentStore.getApiPrefs()` and the `api_endpoints` prefs file are unused | Remove |
| 19 | `ApiEndpoint` (legacy 2-field model) is unused; `SparklineView.addValue` is never called | Remove |
| 20 | Duplicated code blocks: password-visibility toggle (`AccountSettingsActivity` + `AddServerActivity`); `formatSize` with no GB branch (`FilesAdapter` + `RemoteEntryAdapter`); `openFile` type dispatch + `FileProvider` fallback (`RemoteBrowseActivity` + `WebsiteFilesActivity`); the add-server dialog (`DashboardActivity` re-implements `AddServerActivity` with a hardcoded `8080`) | Extract |
| 21 | Raw Intent keys `"profile_id"` / `"path"` bypass the `EXTRA_*` constants defined in `AddServerActivity`; four unrelated constants all have the value `"path"` | Consolidate |
| 22 | No `DiffUtil`/`ListAdapter` — every adapter holds a live reference to the activity's mutable list and calls `notifyDataSetChanged()` | Migrate to `ListAdapter` |
| 23 | `DashboardActivity` mixes all servers' chart data into one shared series (`chartUploadMb`/`chartDownloadMb`) | Per-server series |
| 24 | `ApiAdapter` renders the entire JSON body into every row with no truncation | Truncate the preview |
| 25 | `AdvancedSettingsActivity.deleteRecursive` has no error checking; its only caller passes an app-internal path today, but it is unguarded if that path ever becomes user-influenced | Add a canonical-path check |

### 12.4 Build and packaging

| # | Issue | Detail |
|---|---|---|
| 26 | **No tests and no CI.** No `src/test` or `src/androidTest`, no JUnit dependency | Verification is manual only |
| 27 | `minifyEnabled false` in release — the APK ships unshrunk and un-obfuscated | Add keep rules for the JNI bridge before enabling R8 |
| 28 | 47 MB prebuilt `.so`, arm64-v8a only. No source, no reproducibility, no x86_64 emulator support | Document the provenance of the binary; add the C++ sources or a submodule if this is to be maintained |
| 29 | The `.so` is **modified but uncommitted** (`git status` shows `M app/src/main/jniLibs/arm64-v8a/libllama_bridge.so`) along with `gradle.properties` | Review and commit deliberately |
| 30 | `minSdk 24` but `WebsiteEditorActivity` uses `java.nio.file.Files`, which is API 26+ | Verify or raise `minSdk` to 26, or use `FileInputStream` |
| 31 | Two commits total: `Initial PhoneServer Android project` → `Update PhoneServer` | Very little history to bisect or understand intent from |

### 12.5 Scalability limits (inherent to the design)

- Whole files are read fully into a `byte[]` before being written (`readFile`, `SimpleHttpServer.handleUpload`, `RemoteApiClient.upload`) — a 2 GB upload will OOM. There is no streaming to disk.
- The entire request body is buffered in memory (`Request.body`) — `Content-Length` is trusted with no cap, so a malicious or buggy client can declare a huge body.
- `HomeFragment.countFilesRecursive` is depth-capped at 4 and runs on every 2 s refresh tick.
- `TrafficChartView` uses raw pixel padding (90 px left, 40 px bottom) rather than dp, so axis labels collide on high-density screens, and its colours are hardcoded hex rather than resolved from `R.color.*` — invisible to dark mode.
- `SparklineView` initialises `max = Float.MIN_VALUE`, which is the smallest *positive* float, not the most negative. It happens to work only because every current input is a non-negative percentage.

---

## 13. Extension Guide

### 13.1 Add a new HTTP endpoint

1. Add a constant to `SimpleHttpServer`, alongside the existing path literals:
   ```java
   private static final String PATH_API_SEARCH = "/api/search";
   ```
2. If it touches files, **add it to the password-gate condition** in `route()`:
   ```java
   if (path.equals("/api/browse") || ... || path.equals(PATH_API_SEARCH)) {
       if (!isAuthorized(req)) { writeUnauthorized(out); return; }
   }
   ```
3. Write the handler, mirroring `handleMkdir`:
   - Resolve any client-supplied path via `ContentStore.resolveSafePath(appContext, relative)`
   - Return `null` → 404, unsafe path → 400
   - Build the response with `writeResponse(out, code, "application/json", body)`
4. Extend the client if the app itself should use it: add a method to `RemoteApiClient` following the `getJson` / `postJson` pattern (8 s connect, 15 s read, `X-Auth-Password` header, `401` → `ApiException("Wrong password")`).
5. Update the route table in §5 of this README.

### 13.2 Add a new tab

1. Create `XFragment extends Fragment` with `onCreateView` / `onViewCreated`.
2. Add a layout in `res/layout/fragment_x.xml` and a `res/menu`-independent icon in `res/drawable/ic_x.xml`.
3. Add `PAGE_X` and a `case` to `MainPagerAdapter`; bump `getItemCount()`.
4. Add the nav item to `res/menu/bottom_nav_menu.xml`.
5. Add the mapping in **both** `MainActivity.pageToNavId` and `navIdToPage`.
6. Bump `setOffscreenPageLimit(4)` if you have more than 5 tabs.

For polling screens, copy the self-reposting `Handler` pattern from `HomeFragment.refreshRunnable` and remove it in `onPause`.

### 13.3 Add a new file viewer

1. Add the extensions to the relevant `case` in `FileTypeUtils.classify`.
2. Create `XViewerActivity` with `public static final String EXTRA_PATH = "path"`.
3. Register it in `AndroidManifest.xml` with `android:exported="false"`.
4. Add a `case` to **both** `openFile` methods (`FilesActivity` and `WebsiteFilesActivity`), and to `RemoteBrowseActivity`'s equivalent.

### 13.4 Add a persisted collection

1. Create a `<Name>Store` with a private `SharedPreferences` file name and a `KEY_LIST = "..._json"`.
2. `getAll` — parse defensively, return an empty list on any parse failure (never throw).
3. `getById` / `find` — linear scan; these collections are small by design.
4. `save` — assign a `UUID.randomUUID()` id if absent, else replace by id.
5. `writeAll` — serialise the whole list back, `apply()` (not `commit()`), wrap in try/catch.

Copy `ServerProfileStore` for a keyed collection, or `ApiEndpointStore` for one keyed by composite coordinates.

### 13.5 Before you enable R8

`libllama_bridge.so` resolves its entry points by exact JNI name. Without keep rules, release builds will load the library fine and then fail on the first model load:

```proguard
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.yadaventerprise.phoneserver.LlmEngine$TokenSink { *; }
-keep class com.yadaventerprise.phoneserver.LlmEngine { *; }
```

---

## Appendix: Quick reference

| Thing | Value |
|---|---|
| Package | `com.yadaventerprise.phoneserver` |
| Default port | `8080` (`ServerService.PORT`) |
| Server class | `SimpleHttpServer` — thread-per-connection, `Connection: close` |
| Service class | `ServerService` — foreground, `dataSync`, wake lock, sticky |
| Traversal chokepoint | `ContentStore.resolveSafePath` / `resolveSafeWebsitePath` |
| Stats singleton | `ServerStats.get()` — reset on server start |
| Credential channels | `X-Auth-Password` header · `?pw=` query · HTTP Basic |
| Auth gate covers | `/api/browse`, `/files*`, `/api/mkdir`, `/api/delete`, `/api/rename`, `/api/upload`, `/api/stats` |
| Public endpoints | `/`, `/index.html`, `/status`, website assets, custom `/api/*` |
| Tabs | Home · Dashboard · QR · Chat · Settings |
| Native library | `libllama_bridge.so`, arm64-v8a, ~47 MB, prebuilt binary |
| Default model name | `smollm2-135m-instruct-q8_0.gguf` (must be a *text* model, not an `mmproj`) |
| Prefs files | `server_config`, `server_profiles`, `api_endpoint_configs` |
| Activity count | 15 declared · 3 undeclared and dead |
| Java LOC | ~7,900 across 56 classes |
| Tests | None |
