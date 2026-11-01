# Storage layout

All persistent platform data lives under a single configurable **data root**.
Nothing is ever written into build directories, the app's private files root is
only used as a fallback, and file contents are kept out of SQL.

## Where the data root lives

`ContentStore.getDataRoot(context)` resolves the root in this order:

1. The path configured in **Advanced Settings** (`server_config` →
   `data_root`), if it exists and is writable.
2. The app-private external directory `…/Android/data/<applicationId>/files/phoneserver-data`.
3. The app-private internal directory `files/phoneserver-data` (fallback when
   external storage is unavailable).

Points 2 and 3 are app-private: not world-readable, survive app updates, and
are removed when the app is uninstalled. Point 1 lets an advanced deployment
place data on shared storage.

The root is resolved once per operation via `ContentStore.getStorageLayout()`.

## Directory structure

```
<root>/
  system/
    system.db                 # metadata only (projects, files, tokens, backups, log)
  tmp/                        # in-progress uploads / backup scratch (.part files)
  projects/
    <project-id>/
      databases/<name>.db     # one SQLite file per logical database (plus -wal/-shm)
      files/<storage-key>     # raw uploaded bytes, named by a generated key
      backups/<backup-id>.zip # project backup archives
      project-config.json     # safe-to-export project configuration (no secrets)
```

- `<project-id>`, `<storage-key>` and `<backup-id>` are generated, ASCII-safe
  tokens matching `[A-Za-z0-9_-]{1,64}`. They are used verbatim as path
  segments, so a malicious *name* can never influence a path.
- `<name>` is a validated database name (`[A-Za-z0-9_][A-Za-z0-9_.-]*`, 1–64
  chars, `system`/`sqlite` reserved). `DatabaseManager` also re-checks the
  resolved file is inside the project's `databases/` directory.

## `system.db` schema (version 1)

Created by `SystemDatabase.onCreate`. Versioning is handled by
`onUpgrade` with additive, per-version steps.

| Table | Purpose | Key columns |
|---|---|---|
| `projects` | Project records | `id` PK, `name` UNIQUE, `description`, `file_quota_bytes`, `max_upload_bytes`, timestamps |
| `project_databases` | Registered databases | `id` PK, `project_id` FK → projects (cascade), `name`, `file_name`, `size_bytes` |
| `files` | Stored-file metadata | `id` PK, `project_id` FK (cascade), `original_name`, `storage_key` UNIQUE, `folder_path`, `mime_type`, `size_bytes`, `checksum`, `description`, `tags` |
| `folders` | Logical folders | `id` PK, `project_id` FK (cascade), `path` (unique per project) |
| `api_tokens` | API credentials | `id` PK, `name`, `token_hash` UNIQUE, `project_id` (nullable), `can_write`, `revoked`, `last_used_at` |
| `backups` | Backup archives | `id` PK, `project_id` FK (cascade), `file_name`, `size_bytes`, `includes_files`, `valid`, `note` |
| `operations` | Rolling operation log | `id` autoincrement, `project_id`, `action`, `detail`, `created_at` (trimmed to the newest 500 rows) |

`onConfigure` enables `PRAGMA foreign_keys`, and the helper is created with
`setWriteAheadLoggingEnabled(true)`.

## Project databases

Each logical database is an independent SQLite file under
`projects/<project-id>/databases/`. `DatabaseManager.openFile` enables foreign
keys, WAL and `PRAGMA busy_timeout = 5000` for writable opens. New files are
created with `PRAGMA auto_vacuum = INCREMENTAL`.

`DatabaseManager.list` reconciles the metadata table with what is actually on
disk and **adopts** any `*.db` file it finds without metadata (for example
after a manual copy), so nothing becomes invisible.

When a database is deleted its sidecar files (`-wal`, `-shm`, `-journal`) are
removed too, so re-creating a database of the same name cannot inherit stale
state.

## Uploads and temporaries

Uploads are streamed into `tmp/<prefix>-<id>.part` and renamed into
`files/<storage-key>` only after the bytes are fully written and the SHA-256
checksum computed. On any failure the temp file is deleted. Restores extract
into `tmp/restore-<id>/` and swap directories into place only after a complete,
validated extraction (the previous copy is kept until the swap succeeds).

## Quotas and limits

| Limit | Value | Where |
|---|---|---|
| Default max upload per file | 512 MiB | `ContentStore.DEFAULT_MAX_UPLOAD_BYTES` |
| Per-project upload limit | project `maxUploadBytes` (0 → default) | `FileStore.upload` |
| Per-project storage quota | project `fileQuotaBytes` (0 → unlimited) | `FileStore.upload` |
| Max files per project | 20 000 | `FileStore.MAX_FILES_PER_PROJECT` |
| Max databases per project | 50 | `DatabaseManager.MAX_DATABASES_PER_PROJECT` |
| Max backups per project | 25 (oldest pruned) | `BackupManager.MAX_BACKUPS_PER_PROJECT` |
| Query row limit | 100 default, 1000 max | `DatabaseManager` |
| Query time budget | 5 s default, 30 s max | `DatabaseManager` |
| Restore size cap | 4 GiB | `BackupArchive.DEFAULT_MAX_TOTAL_BYTES` |

Both the *declared* length and the *streamed* byte count are checked, so a
client cannot bypass the upload limit by lying about `Content-Length`.

## Ad network data

The optional ad network keeps its data outside the project data root and outside `system.db`:

```
app private storage/
  databases/
    adnet.db                 # users, sessions, sites, placements, campaigns,
                             # creatives, events, stats_daily, fraud_log,
                             # billing, audit, settings (+ -wal/-shm)
  files/
    adnet/creatives/
      <24-hex>.<png|jpg|gif|webp>
  shared_prefs/
    adnet_prefs.xml          # enabled flag + one-time setup code
```

| Table | Purpose |
|---|---|
| `users` | email, PBKDF2 hash, role, status, prepaid balance (micros) |
| `sessions` | SHA-256 token hashes, user id, expiry |
| `sites` | owner, domain, moderation status, fraud flag/reason |
| `placements` | site, format/size, `pl_` key, status |
| `campaigns` | advertiser, pricing/bid/budgets, schedule, targeting JSON, review status |
| `creatives` | campaign, type/text/URL, image file/mime/dimensions, review status |
| `events` | one row per impression/click, validity/reason, cost and publisher share |
| `stats_daily` | daily aggregates per campaign/creative/placement/site |
| `fraud_log` | invalid-traffic counts by day and reason |
| `billing` | top-ups, adjustments, payouts and resulting balances |
| `audit` | admin actions (setup, user create/delete, approvals, billing) |
| `settings` | platform configuration and the HMAC secret |

Retention is managed by the ad-network sweeper: expired sessions are deleted every minute, fraud review runs every 10 minutes, and events older than `retention_days` (default **30**) plus fraud-log days older than 90 are removed hourly. Orphaned creative images are deleted after one hour. Ad data is **not** part of `/api/v1` project backups; back up `adnet.db` and `adnet/creatives/` separately.

See [ad-network.md](ad-network.md) for delivery-count semantics and the full API.
