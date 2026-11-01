# Security model

This document is the security review for the local SQL + file storage platform.
It states what is protected, how, and — importantly — what is **not** protected
so that deployments are not over-trusted.

## Threat model

The intended deployment is a phone on a trusted local network, reachable by
other devices on that network (or by the phone itself). There is no cloud
component and no multi-tenant hardening. The security goals are therefore:

1. No unauthenticated administrative surface.
2. No cross-project data access with a project-scoped credential.
3. No filesystem escape via crafted names, folder paths or archives.
4. No SQL escape via crafted statements or parameters.
5. No secret leakage to clients through error messages or responses.
6. Bounded resource use (request size, rows, time, rate).

## Authentication

`/api/v1` is authenticated by **either**:

- **Access password** (interactive / admin). Supplied as
  `X-Auth-Password: <pw>`, `?pw=<pw>`, or HTTP Basic auth. This grants admin
  rights across all projects. Comparison uses a constant-time equality check
  (`TokenUtil.constantTimeEquals`).
- **Bearer API token** (`Authorization: Bearer pst_…`). Tokens are created from
  the app (or the `/tokens` endpoint by an admin), can be **scoped to a single
  project** and/or marked **read-only**, and are stored **only as SHA-256
  hashes**. The plaintext is shown exactly once at creation.
- If **no password is configured**, anonymous admin access is refused: a token
  is required. This avoids the "empty password = open admin panel" mistake.

`GET /api/v1/health` is the only unauthenticated endpoint. `OPTIONS` (CORS
preflight) is answered before auth so browsers can negotiate.

Only an **admin** (password) credential can call `/api/v1/system` and
`/api/v1/tokens`. A project-scoped admin request may not create projects.

## Authorisation & project isolation

`requireProjectAccess` rejects a project-scoped token from touching any other
project. Combined with the storage layout — every project is a directory named
by its generated id, and a token stores only that id — this makes cross-project
access structurally impossible, not just filtered by the API layer. A
read-only token (`canWrite=false`) is refused on every mutating route via
`requireWrite`, including the SQL workspace's write statements.

## Path safety

`PathGuard` is used everywhere user input could influence a path:

- `normalizeRelative` rejects `..`, `.`, empty segments, and separators inside
  segments.
- `isValidSegment` / `sanitizeFileName` strip or replace reserved characters and
  never return an empty name.
- `safeResolve` / `isInside` canonicalise both the root and the candidate and
  verify the result is inside the root.

Because ids/keys are generated and used as the actual on-disk names, these
checks are the second line of defence, not the first.

## SQL safety

`SqlGuard.inspect` runs before any statement reaches SQLite:

- Exactly **one** statement per request (a top-level `;` with more than one
  non-empty statement is rejected). Splitting is quote/comment aware.
- Forbidden constructs are blocked: `ATTACH`, `DETACH`, `load_extension`,
  `writable_schema`, and several dangerous `PRAGMA`s (`temp_store_directory`,
  `data_store_directory`, `lock_status`, `database_list`).
- Explicit transaction control (`BEGIN`/`COMMIT`/`ROLLBACK`/`SAVEPOINT`) is
  rejected — use the transaction endpoint instead.
- Only `SELECT`/`WITH`/`EXPLAIN`/`PRAGMA` (read) and
  `INSERT`/`UPDATE`/`DELETE`/`REPLACE`/`CREATE`/`DROP`/`ALTER` (write) are
  allowed; write verbs are refused on a read-only credential.

The workspace is **parameterised**: `params` are bound, never interpolated.
Identification names used internally (`PRAGMA table_info`, `VACUUM INTO`, row
counting) are quoted with `"` and doubled. Scope/statement analysis is done per
database file, and the layer only ever opens a file that is inside the
project's own `databases/` directory.

This is a **local-first data store, not a hardened multi-tenant SQL service.**
There is no per-statement sandbox beyond the guard and limits above.

## Uploads & downloads

- Upload limits are enforced against both the declared length and the actual
  streamed byte count (`413` on violation).
- Multipart bodies are buffered and capped; large raw uploads are streamed off
  the socket so memory is not exhausted.
- Downloads and exports stream from disk and support `Range`; `416` is returned
  for unsatisfiable ranges.
- Uploaded content is served as an **attachment** unless the MIME type is on a
  small allow-list of inline-safe types (`MimeTypes.isInlineSafe`), so user
  content cannot execute scripts in the server's origin. Responses also carry
  `X-Content-Type-Options: nosniff` and `Cache-Control: private, max-age=0`.

## Backups

- An archive is a zip with a `manifest.properties`; validation requires the
  expected `format`/`version` and rejects any entry that is absolute, contains
  a backslash, or is not a safe relative path.
- Extraction is root-checked (`PathGuard`) and capped (4 GiB) to resist archive
  bombs.
- Restore/import replace data only after a full validated extraction into a
  staging directory; the previous copy is preserved until the swap succeeds.
- Backups contain the project's **databases, stored files and safe-to-export
  `project-config.json`**. They never contain the access password, API tokens,
  or other secrets.

## Resource limits

- Per-IP rate limit (`ContentStore.getApiRateLimitPerMinute`, default 240/min)
  returns `429` with `Retry-After: 60`.
- Buffered request bodies are capped (413 above the cap).
- Query row limits and a time budget bound scans; exceeding the budget returns
  `408`.
- Row counts, database counts, file counts and backup counts are all bounded.

## Error handling

Client-visible messages come from `DataException` and are written to be safe.
SQLite error text is passed through `safeMessage`, which replaces absolute
paths with `<path>`. Unexpected exceptions return a generic
`500 internal server error`; details are only logged on-device.

## CORS

CORS origins come from `ContentStore.getAllowedOrigins` (default `*`). An empty
value disables CORS headers entirely (same-origin only). Credentials are
**never** advertised via `Access-Control-Allow-Credentials`, so browsers will
not attach ambient credentials cross-origin.

## Explicit non-goals / production requirements

- **No TLS in the app.** HTTP is plaintext. For any access beyond a trusted LAN
  you must put the server behind an HTTPS reverse proxy (or a VPN/SSH tunnel)
  and terminate TLS there. Anything on the same network can otherwise read
  credentials and data.
- **The access password and tokens are bearer secrets.** Anyone who obtains
  them has the associated scope. Tokens can be revoked from the app/API.
- **No audit-grade logging or login throttling beyond the per-IP rate limit.**
- **No encryption at rest.** Files and databases are stored unencrypted in the
  app-private data root. Device-level encryption is the only protection.
- **No protection against a malicious local app** on a rooted/compromised
  device.

Do not treat this as production security for sensitive or multi-user data.

## Ad network security

The optional ad network has a separate threat model and auth system from `/api/v1`:

- **Separate origin and data.** Port `8090`, `adnet.db`, creative files and `adnet_prefs`; no shared sessions, tokens or access password.
- **Accounts.** PBKDF2-HMAC-SHA256 (60,000 iterations, 16-byte salt), 10–128 character passwords with letters and digits, 12-hour bearer sessions stored only as SHA-256 hashes, revocation on logout/password change/disable.
- **First admin.** `/api/setup` requires a one-time code shown on the phone and is refused once any user exists; setup attempts are rate-limited.
- **Login abuse.** Per IP+email and per-IP windows; unknown emails run a dummy PBKDF2 hash so timing cannot reveal account existence.
- **Authorisation.** Admin-only routes are checked server-side. Publishers can only manage their sites/placements; advertisers can only manage their campaigns/creatives. Foreign ids return 404.
- **Delivery.** `/serve` requires a valid `pl_` key, an approved site, an active placement and an `Origin`/`Referer` host matching the registered domain (subdomains allowed). `/imp` and `/c` require HMAC-signed, single-use, expiring tokens; clicks require a prior valid impression. Bots get `204`; no fill gets `204`.
- **Invalid traffic.** Bot UA filtering, per-IP serve/beacon/click limits, visitor/day caps, minimum click delay, repeat-click caps and site flagging. Invalid events are recorded but never billed.
- **Input handling.** Strict email/domain/URL/number validation, image content sniffing (PNG/JPEG/GIF/WebP only, no SVG), exact banner dimensions, bound SQL parameters, fixed report grouping expressions.
- **Response safety.** Header values strip CR/LF; API responses are `no-store`; every response has `X-Content-Type-Options: nosniff`; creative media is served with a `default-src 'none'; sandbox` CSP; the dashboard has a strict self-only CSP plus `X-Frame-Options: DENY`.
- **Privacy.** No cookies and no raw-IP visitor storage. The visitor id is a daily-rotating HMAC of IP+UA used only for frequency capping/fraud, and `DNT: 1`/`Sec-GPC: 1` suppresses it. No third-party requests or cloud services are involved.

Residual risks: plaintext HTTP on the LAN, no TLS in the app, no email verification/password reset/CAPTCHA, heuristic fraud detection, and no guarantee against determined invalid traffic. Treat this as a small private ad server, not an ad exchange. See [ad-network.md](ad-network.md) §11.
