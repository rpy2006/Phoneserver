# PhoneServer Storage Layout

PhoneServer uses three storage families:

1. **SQLite** — `system.db` for projects, databases, files, backups, tokens and
   operations; one SQLite file per project database; `adnet.db` for the ad
   network.
2. **App-private JSON** — encrypted or bounded JSON sidecars for settings,
   security, TLS, sharing, webhooks and scheduled tasks.
3. **Shared media** — uploads written to the device's real `Pictures/`,
   `Movies/`, `Music/` and `Download/` folders and scanned into MediaStore.

## App-private files

All paths are under `Context.getFilesDir()`.

| Path | Contents | Protection |
|---|---|---|
| `network/https-config.json` | HTTPS enabled/port/hostname/public URL | Encrypted JSON |
| `network/tls-identity.json` | Private key, certificate, subject, fingerprint | Encrypted JSON + `SecurityPaths.restrict` |
| `webhooks/webhooks.json` | Webhook URL, events, optional HMAC secret | Encrypted JSON + restrict |
| `webhooks/webhook-history.json` | Last 200 delivery attempts | Encrypted JSON + restrict |
| `tasks/scheduled-tasks.json` | Repeating backup/webhook tasks and last result | Encrypted JSON + restrict |
| `shares/links.json` | Share links, token hashes, password hashes, limits, counters | Encrypted JSON + restrict |
| `shares/settings.json` | Default expiry, download limit, permission and password policy | Encrypted JSON + restrict |
| `shares/access-log.jsonl` | Bounded share access/audit log (2 MB rotation) | App-private; no secrets |
| `security/access-log.jsonl` | Bounded request audit log | App-private |
| `security/security-events.jsonl` | Auth/suspicious/rate-limit events | App-private |
| `security/*` | IP blocks, settings, token policies, reports | Encrypted JSON where applicable |

## SQLite

`system.db` is created through `SystemDatabase` and stores project metadata,
database metadata, file records, backups, API tokens and the operation log.
Project database contents live in per-project SQLite files managed by
`DatabaseManager`. Backups are validated ZIP archives containing a manifest,
database files, stored files and `metadata.json`.

## Preferences

`ContentStore` keeps user settings in `SharedPreferences`, including the access
password, start-on-boot, remote access, hidden files, CORS origins and the
server `desired_running` flag. The password is still plaintext in app-private
storage; see [SECURITY_AUDIT.md](SECURITY_AUDIT.md) R-01.

## Retention

- Access log, security events, share access log and webhook history are bounded.
- Share links keep their download counters until the link is deleted; rotating
  a link secret resets its download counter and invalidates the previous URL.
- Share access history is capped at a 2 MB active JSONL file plus one rotated
  file; the app history screen shows at most the latest 2,000 matching events.
- Backups rotate per project according to `BackupSchedule.retainPerProject`.
- Scheduled tasks keep only their latest result, not a full run history.
