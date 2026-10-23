# Architecture

This document describes the local SQL + file storage platform that was added
on top of PhoneServer. It complements the high-level overview in the project
[README](README.md) and the request-level reference in [API.md](API.md).

The platform is deliberately small: a thin `data/` storage layer, a single
HTTP facade (`DataApi`), and a set of Android screens that drive the same
layer. Nothing depends on a cloud service, an account, or an external database
server.

## Component map

```
                       ┌────────────────────────────┐
   browser / app  ───► │  SimpleHttpServer (port 8080)│
                       │   • parses requests          │
                       │   • dispatches /api/v1       │──┐
                       │   • streams large uploads    │  │
                       └────────────────────────────┘  │
                                                        ▼
                       ┌──────────────────────────────────────────────┐
                       │  DataApi  (HTTP facade for /api/v1)           │
                       │   • auth (password or bearer token)           │
                       │   • per-project authorisation                 │
                       │   • rate limiting, CORS, JSON framing         │
                       └──────────────────────────────────────────────┘
                                        │
        ┌──────────────┬───────────────┼───────────────┬──────────────┐
        ▼              ▼               ▼               ▼              ▼
  ProjectStore   DatabaseManager    FileStore     BackupManager   TokenStore
        │              │               │               │              │
        └──────────────┴───────┬───────┴───────────────┴──────────────┘
                               ▼
                  ┌───────────────────────────┐        ┌────────────────────┐
                  │  SystemDatabase (system.db)│        │  Filesystem (root) │
                  │  metadata only             │        │  databases/, files/ │
                  └───────────────────────────┘        │  backups/, tmp/     │
                                                        └────────────────────┘
```

Supporting classes: `StorageLayout`, `PathGuard`, `SqlGuard`, `MimeTypes`,
`TokenUtil`, `RateLimiter`, `Records`, `DataException`, `BackupArchive`,
`ContentStoreBridge`.

## Layers

1. **Transport — `SimpleHttpServer`.** The existing single-file HTTP server.
   It was extended to (a) recognise `/api/v1` paths, (b) leave large raw
   (non-multipart) upload bodies on the socket so `DataApi` can stream them,
   (c) cap buffered (multipart / small) bodies, and (d) hand the request to
   `DataApi.handle(...)`. It also adds the CORS response header using the
   configured origins.

2. **API facade — `DataApi`.** A stateless dispatcher that turns HTTP requests
   into storage-layer calls and back into JSON. It owns authentication,
   per-project authorisation checks, rate limiting, CORS preflight, JSON
   parsing/rendering, and streaming of downloads and exports. It never touches
   SQLite directly — it always goes through the store classes.

3. **Stores — the `data/` package.** Each store owns one concern and is the
   only place that reads/writes the corresponding tables/files:

   | Store | Responsibility |
   |---|---|
   | `SystemDatabase` | Declares/creates `system.db` and its schema; provides the singleton helper |
   | `ProjectStore` | Project lifecycle, per-project/global statistics, operation log |
   | `DatabaseManager` | Per-project SQLite databases: create/list/delete, schema inspection, guarded query execution, transactions, export/import |
   | `FileStore` | Streamed uploads, folders, download metadata, rename/move/delete, usage, integrity checks |
   | `BackupManager` | Create/validate/download/restore/import project backups, retention |
   | `TokenStore` | Issue/verify/revoke/list API tokens (hash-only persistence) |

4. **Persistence.** `system.db` holds *metadata only*. Database files and stored
   file bytes live on the filesystem under a single configurable data root
   (see [storage.md](storage.md)). User-supplied names are never used as paths.

## Request lifecycle for `/api/v1`

1. `SimpleHttpServer` parses the request line, headers and (depending on the
   route) either buffers or defers the body.
2. If the path is under `/api/v1` it calls `DataApi.handle(context, req, out)`.
3. `DataApi` rejects explicitly-too-large buffered bodies with `413`.
4. `OPTIONS` is answered as a CORS preflight **before** authentication.
5. `GET /api/v1/health` is answered publicly.
6. Otherwise the per-IP rate limit is enforced (`429` when exceeded), then the
   caller is authenticated.
7. The first path segment selects a handler (`system`, `tokens`, `projects`);
   project-scoped handlers verify the caller may touch that project.
8. The handler calls the relevant store and renders JSON (or streams bytes).
   `DataException` carries the intended HTTP status.

## Design choices

- **Metadata in SQL, content on disk.** Large media is never stored as SQL
  blobs; only a generated storage key is. This keeps the metadata database
  small and makes range downloads trivial.
- **Generated ids and keys.** Project ids, database ids, file ids and file
  storage keys are all generated (UUID-derived / random). The *id* is the
  directory or file name on disk, so path traversal is structurally impossible
  rather than merely filtered.
- **Parameterised SQL + a static guard.** Ordinary data operations never build
  SQL from user strings; the SQL workspace additionally runs every statement
  through `SqlGuard` (single-statement only, forbidden keywords blocked,
  read-only enforcement) and applies row limits and a time budget.
- **Defence in depth for paths.** `PathGuard` normalises, validates and then
  re-checks the canonicalised result is inside the expected root, even though
  the storage layout already avoids user-controlled path segments.
- **One connection style.** Project databases are opened with foreign keys,
  WAL and a busy timeout. `system.db` uses WAL and cascading foreign keys so
  deleting a project removes its metadata rows atomically while on-disk data is
  removed first (so a failure never silently loses data).
- **Safe error messages.** `DataException` messages are written to be returned
  to clients; SQLite errors are passed through `safeMessage(...)`, which
  replaces absolute paths with `<path>`.

## Threading

`SimpleHttpServer` handles each connection on its own thread. Stores are
stateless apart from the `SystemDatabase` singleton and the cached
`RateLimiter`; SQLite serialises access internally (WAL + busy timeout). The UI
performs storage work off the main thread and observes the same layer the API
uses, so there is no second source of truth.

## Ad network (optional)

The ad network is a second, independently started HTTP service. It does not add routes to `SimpleHttpServer`; it is owned by `AdNetworkManager` and started from `ServerService` only when the owner enables it.

```
ServerService (port 8080)
   └── AdNetworkManager.startIfEnabled()
          ├── AdHttpServer (port 8090, 4–24 workers, Connection: close)
          │      ├── AdApi       authenticated /api/*
          │      ├── AdDelivery  /sdk.js /serve /imp /c /media
          │      └── static dashboard assets
          ├── AdDb (adnet.db) + AdDelivery.creativesDir()
          └── AdNet-Sweeper (sessions, fraud review, retention, end dates)
```

Design consequences:

- **Fault and load isolation.** A slow or abusive ad request cannot occupy the file server's accept loop or its unbounded thread-per-connection handlers; the ad server uses a bounded pool and its own queue.
- **Separate persistence.** `adnet.db` has no foreign keys into `system.db`. Project backups and the `/files` API never see ad data.
- **Separate auth.** The file server's password/`X-Auth-Password`/Basic auth is not consulted on port 8090. Ad accounts use PBKDF2 hashes and bearer sessions.
- **Public by necessity.** `/sdk.js`, `/serve`, `/imp`, `/c` and `/media/*` cannot require an interactive login because they run on third-party pages; they are protected by placement keys, domain matching, signed single-use tokens, bot filtering and rate limits instead.
- **Strict dashboard CSP.** The dashboard assets are served with `default-src 'self'`, so all script/style must be external files; there are no inline handlers.

Full details, including schema, selection, billing and privacy, are in [ad-network.md](ad-network.md).
