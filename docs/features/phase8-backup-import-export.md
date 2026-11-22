# Phase 8 backup, import/export, and recovery

## Scope implemented

- Encrypted backup packages (`.psbak`) with `backup/manifest.json`, `metadata.json`, `payload.enc`, `integrity.json`.
- AES-GCM payload encryption with PBKDF2-derived key; backup passwords are never stored in plaintext.
- Archive checksum verification before decrypt/restore/validate/preview.
- Manifest/schema/path validation before applying restore or config import.
- Backup preview/validate for stored backups and uploaded archives.
- Restore options: `confirm`, `createRecoveryPoint`, `restoreDatabases`, `restoreFiles`, `restoreProjectConfig`.
- Config export redacts secrets/password hashes.
- Config import preview/apply with strategies `merge`, `create_only`, `update_only`, `replace` (selective replacement for provided categories).
- Import staging, recovery point, atomic file replacement where supported, post-apply verification, and rollback on failure.
- Preview endpoints do not modify production data.

## API endpoints

- `GET /api/v1/projects/{projectId}/backups`
- `POST /api/v1/projects/{projectId}/backups`
- `POST /api/v1/projects/{projectId}/backups/import?confirm=true`
- `POST /api/v1/projects/{projectId}/backups/import-preview`
- `POST /api/v1/projects/{projectId}/backups/import-validate`
- `GET /api/v1/projects/{projectId}/backups/{backupId}`
- `POST /api/v1/projects/{projectId}/backups/{backupId}/preview`
- `POST /api/v1/projects/{projectId}/backups/{backupId}/validate`
- `POST /api/v1/projects/{projectId}/backups/{backupId}/restore`
- `GET /api/v1/projects/{projectId}/backups/{backupId}/download`
- `DELETE /api/v1/projects/{projectId}/backups/{backupId}`
- `POST /api/v1/projects/{projectId}/backups/export-config`
- `POST /api/v1/projects/{projectId}/backups/import-config-preview`
- `POST /api/v1/projects/{projectId}/backups/import-config`

## Config import behavior

- Supported categories currently wired: `appConfig`, `users`, `permissions`, `ad_network`, `scheduled_tasks`, `webhooks`.
- Validation runs before apply; invalid/duplicate/conflicting records are rejected.
- `create_only`: create missing, skip existing.
- `update_only`: update existing, skip missing.
- `merge`: create missing, update existing; merge keeps selected existing fields where appropriate.
- `replace`: selective replacement for provided categories only; non-provided categories are untouched.
- A recovery point is created before apply; failures trigger rollback.

## Validation and restore guarantees (implemented)

- Import validation covers malformed/incomplete config cases, duplicate IDs, invalid refs, unsupported schema versions, invalid field types/values, and sensitive plaintext credential rejection for supported categories.
- Ad Network transactional import/export currently covers `users`, `sites`, `placements`, `campaigns`, `creatives` only; it does not claim transactional import for every AdDb table.
- Backup restore validates checksum/archive/manifest before apply, supports selective category restore flags, and creates recovery points by default.
- Simulated restore failures (injected interruption, insufficient storage, file replacement failure, post-restore verification failure) roll back installed state and record failure/rollback operations. Recovery point restore is available and tested.

## Limits / not claimed

- Cloud sync/providers, real cloud credentials, and live AI/cloud tests are not available/claimed.
- `lintDebug --offline` may be unavailable if lint dependencies are not cached in the environment.
- Broader external-system restore beyond implemented project/archive/config categories is out of scope.
