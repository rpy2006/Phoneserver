# Data & storage API (`/api/v1`)

The local SQL + file storage platform is exposed as a versioned JSON API. This
document is the complete reference. It is self-contained; the in-app
**ApiDocsActivity** screen shows a short summary.

- **Base URL:** `http://<device-ip>:8080/api/v1`
- **Content type:** requests and responses are `application/json; charset=utf-8`
  unless a binary resource is streamed.
- **Errors:** `{"error":"<message>","status":<code>}` with a meaningful HTTP
  status code.
- **Port:** fixed at `8080` (`ServerService.PORT`).

---

## 1. Authentication

Every endpoint under `/api/v1` requires authentication **except**
`GET /health` (public liveness probe) and `OPTIONS` (CORS preflight).

Two credential kinds are accepted:

### 1.1 Access password (admin)

Grants admin rights to all projects. Supply it in any one of these ways:

| Method | Example |
|---|---|
| Header | `X-Auth-Password: my-secret` |
| Query parameter | `?pw=my-secret` |
| HTTP Basic | `Authorization: Basic base64(user:my-secret)` (only the password part is checked) |

If **no password is configured** on the device, anonymous admin access is
refused — you must use a token.

### 1.2 API token (scoped)

```http
Authorization: Bearer pst_3f9c… 
```

Tokens are created in the app (or via `POST /tokens` by an admin). They can be:

- **global** (access every project) or **project-scoped** (one project only);
- **read-write** or **read-only**.

Only the SHA-256 hash of a token is stored; the plaintext (`pst_` + 64 hex
characters) is returned **once** at creation and cannot be shown again.
Revoking a token takes effect immediately.

A project-scoped admin request cannot create projects. A read-only credential
is refused on every mutating route (including SQL write statements).

### 1.3 Failure responses

| Status | Meaning |
|---|---|
| `401 Unauthorized` | Missing/invalid credential. Adds `WWW-Authenticate: Basic realm="PhoneServer"` |
| `403 Forbidden` | Valid credential but not allowed (wrong project, read-only, non-admin) |
| `429 Too Many Requests` | Per-IP rate limit exceeded (`Retry-After: 60`) |

### 1.4 Rate limiting

Requests are limited per client IP using the configured
`api_rate_limit_per_minute` (default **240**/min; minimum 10). Exceeding it
returns `429`.

### 1.5 CORS

The allowed origins come from the app's `allowed_origins` setting (default
`*`). If set to a comma-separated list, only matching `Origin` headers receive
`Access-Control-Allow-Origin`. An empty value disables CORS headers entirely.

Preflight (`OPTIONS`) returns `204` with:

```
Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type, X-Auth-Password
Access-Control-Max-Age: 600
```

Credentials are never advertised via `Access-Control-Allow-Credentials`.

---

## 2. Endpoints

### 2.1 Health (public)

#### `GET /health`

```json
{"status":"online","apiVersion":"v1","server":"phoneserver","time":"2026-02-04T01:55:12Z"}
```

### 2.2 Index (authenticated)

#### `GET /` (i.e. `/api/v1`)

Returns the server name/version and the list of endpoint patterns.

### 2.3 System (admin only)

#### `GET /system`

```json
{
  "projects": {"count": 2},
  "databases": {"count": 3, "bytes": 94208},
  "files": {"count": 12, "bytes": 5242880},
  "storage": {"usedBytes": 5337088, "freeBytes": 10737418240, "totalBytes": 32000000000},
  "uptimeMillis": 3600000,
  "apiVersion": "v1"
}
```

### 2.4 Tokens (admin only)

| Method & path | Body | Success |
|---|---|---|
| `GET /tokens` | — | `200 {"tokens":[…]}` |
| `POST /tokens` | `{"name":"ci","projectId":"<id-or-null>","canWrite":true}` | `201` (see below) |
| `DELETE /tokens/{id}` | — | `200 {"status":"ok"}` |

Token list item:

```json
{"id":"a1b2…","name":"ci","projectId":null,"canWrite":true,
 "createdAt":1759000000000,"lastUsedAt":0,"revoked":false}
```

Creation response (the only time the plaintext token is returned):

```json
{"id":"a1b2…","name":"ci","projectId":null,"canWrite":true,
 "createdAt":1759000000000,"token":"pst_…",
 "warning":"store this token now; it cannot be shown again"}
```

`name` is required (≤ 60 chars). `projectId` — when present and non-null — must
exist and scopes the token.

### 2.5 Projects

Project object:

```json
{
  "id":"9c1f…","name":"Notes","description":"",
  "fileQuotaBytes":0,"maxUploadBytes":0,
  "createdAt":1759000000000,"updatedAt":1759000000000,
  "usage":{
    "databaseCount":1,"fileCount":3,"folderCount":1,
    "fileBytes":2048,"databaseBytes":16384,"backupBytes":0
  }
}
```

(`fileQuotaBytes` `0` = unlimited; `maxUploadBytes` `0` = use the global
default of 512 MiB.)

| Method & path | Body / query | Success |
|---|---|---|
| `GET /projects` | — | `200 {"projects":[…]}`, filtered to projects the credential may see |
| `POST /projects` | `{"name":"Notes","description":"","fileQuotaBytes":0,"maxUploadBytes":0}` | `201` project object |
| `GET /projects/{id}` | — | `200` project object |
| `PATCH /projects/{id}` (or `PUT`) | any subset of the fields | `200` project object |
| `DELETE /projects/{id}` | `?confirm=<exact project name>` | `200 {"status":"ok"}` |
| `GET /projects/{id}/stats` | — | `200` stats object |
| `GET /projects/{id}/usage` | — | `200` usage object |

- `name` is required, trimmed, ≤ 60 chars, no control characters; names are
  unique (`409` on duplicate).
- Deleting requires the exact project name as confirmation (`400` otherwise).
- `stats`: `{projectId,databaseCount,fileCount,folderCount,fileBytes,databaseBytes,backupBytes}`.
- `usage`: `{projectId,fileCount,fileBytes,quotaBytes,maxUploadBytes,freeBytes,totalBytes}`.

### 2.6 Databases

Database object:

```json
{"id":"77aa…","name":"app","fileName":"app.db","sizeBytes":16384,
 "createdAt":1759000000000,"updatedAt":1759000000000}
```

| Method & path | Body / query | Success |
|---|---|---|
| `GET /projects/{id}/databases` | — | `200 {"databases":[…]} ` |
| `POST /projects/{id}/databases` | `{"name":"app"}` | `201` database object |
| `GET /projects/{id}/databases/{db}` | — | `200` database object + `tables:[…]` |
| `DELETE /projects/{id}/databases/{db}` | — | `200 {"status":"ok"}` |
| `GET /projects/{id}/databases/{db}/tables` | — | `200 {"tables":[…]} ` |
| `GET /projects/{id}/databases/{db}/tables/{table}` | — | `200` table object |
| `POST /projects/{id}/databases/{db}/query` | see 2.6.1 | `200` query result |
| `POST /projects/{id}/databases/{db}/transaction` | see 2.6.2 | `200` transaction result |
| `GET /projects/{id}/databases/{db}/export` | — | `200` SQLite file (download) |
| `POST /projects/{id}/databases/{db}/import` | raw SQLite body, `?confirm=true` | `200 {"status":"ok"}` |

- `{db}` may be the database **id or name** (name match is case-insensitive).
- Database names: `[A-Za-z0-9_][A-Za-z0-9_.-]*`, 1–64 chars; `system` and
  `sqlite` are reserved. Max 50 databases per project.
- Table summary: `{"name":…,"type":"table|view","rowCount":…}`.
- Table detail adds `columnCount`, `columns` and `indexes`:

```json
{
  "name":"notes","type":"table","rowCount":2,"columnCount":2,
  "columns":[
    {"name":"id","type":"INTEGER","notNull":false,"primaryKey":true,"default":null},
    {"name":"body","type":"TEXT","notNull":true,"primaryKey":false,"default":null}
  ],
  "indexes":[{"name":"idx_notes_body","unique":false,"columns":["body"]}]
}
```

#### 2.6.1 `POST …/{db}/query`

```json
{"sql":"SELECT * FROM notes WHERE id = ?","params":[1],"limit":100,"offset":0,"timeBudgetMs":5000}
```

- `sql` (required): a **single** statement. `SELECT`/`WITH`/`EXPLAIN`/`PRAGMA`
  are read-only; `INSERT`/`UPDATE`/`DELETE`/`REPLACE`/`CREATE`/`DROP`/`ALTER`
  are writes and need a read-write credential.
- `params` (optional): bound values, referenced with `?` (or `:name`/`@name`/
  `$name`). Never interpolate.
- `limit` (default 100, max 1000), `offset` (default 0),
  `timeBudgetMs` (default 5000, max 30000; exceeded → `408`).

Result (read):

```json
{"isSelect":true,"keyword":"select","rowsAffected":0,"truncated":false,
 "durationMs":1,"columns":["id","body"],"rows":[[1,"hello"]],"rowCount":1}
```

Result (write):

```json
{"isSelect":false,"keyword":"insert","rowsAffected":1,"truncated":false,"durationMs":0}
```

Binary SQL values are returned as `base64:<data>`. A result that hits the row
limit sets `"truncated": true`.

#### 2.6.2 `POST …/{db}/transaction`

```json
{"statements":[
  {"sql":"INSERT INTO notes(body) VALUES(?)","params":["a"]},
  {"sql":"UPDATE notes SET body=? WHERE id=?","params":["b",1]}
]}
```

Plain strings are also accepted as elements. All statements run in one
transaction; on any error the transaction is rolled back. Response is a query
result with the summed `rowsAffected`. Explicit
`BEGIN`/`COMMIT`/`ROLLBACK`/`SAVEPOINT` are rejected — this endpoint is the
transaction mechanism.

#### 2.6.3 Export / import

- `export` streams a consistent copy of the database
  (`Content-Type: application/vnd.sqlite3`), using `VACUUM INTO` where
  supported.
- `import` replaces the database. It requires `?confirm=true` and a raw
  SQLite body. The uploaded bytes are verified with `PRAGMA integrity_check`
  and the **previous file is preserved** under `backups/` as
  `<name>.db.pre-import-<timestamp>` before the swap. Invalid/empty files are
  rejected (`400`).

### 2.7 Files & folders

File object:

```json
{"id":"ff01…","projectId":"9c1f…","name":"photo.jpg","folder":"albums",
 "mimeType":"image/jpeg","sizeBytes":2048113,"checksum":"<sha256 hex>",
 "description":"","tags":"","createdAt":1759000000000,"updatedAt":1759000000000,
 "downloadPath":"/api/v1/projects/9c1f…/files/ff01…/download"}
```

| Method & path | Body / query | Success |
|---|---|---|
| `GET /projects/{id}/files` | optional `?folder=albums` | `200 {"files":[…]}` |
| `POST` / `PUT /projects/{id}/files` | see 2.7.1 | `201` file object |
| `GET /projects/{id}/files/{fileId}` | — | `200` file object |
| `PATCH` / `PUT /projects/{id}/files/{fileId}` | `{"name":"new.jpg","folder":"archive"}` | `200` file object |
| `DELETE /projects/{id}/files/{fileId}` | — | `200 {"status":"ok"}` |
| `GET /projects/{id}/files/{fileId}/download` | optional `?inline=1` | file bytes |
| `GET /projects/{id}/folders` | — | `200 {"folders":[{"id":…,"path":…,"createdAt":…}]}` |
| `POST /projects/{id}/folders` | `{"path":"albums/2026"}` | `201 {"status":"ok"}` |
| `DELETE /projects/{id}/folders` | `?path=albums/2026` | `200 {"status":"ok"}` |

Without `?folder=`, `GET …/files` lists all files in the project (across
folders). Folders must be empty (no files and no subfolders) to delete (`409`
otherwise).

#### 2.7.1 Upload

Two forms are supported.

**Raw body (recommended; streamed, for large files):**

```bash
curl -X POST "http://<ip>:8080/api/v1/projects/<pid>/files?folder=albums&name=photo.jpg&description=beach&tags=holiday" \
     -H "Authorization: Bearer pst_…" \
     -H "Content-Type: image/jpeg" \
     --data-binary @photo.jpg
```

`Content-Length` is **required** (otherwise `411`); the body is streamed off the
socket. The MIME type comes from the request `Content-Type`.

**multipart/form-data** (buffered, capped):

```bash
curl -X POST "http://<ip>:8080/api/v1/projects/<pid>/files" \
     -H "Authorization: Bearer pst_…" \
     -F "file=@photo.jpg"
```

Query parameters `folder`, `name`, `description`, `tags` are optional. The
stored file name is sanitised; the original name is kept in metadata. A SHA-256
checksum is computed while streaming.

Limits: per-file upload limit (project override or 512 MiB default) and
per-project storage quota both return `413`; the file-count limit returns `409`.
Both the declared length and the streamed byte count are checked.

#### 2.7.2 Download

- Streamed from disk with `Content-Length`, `Accept-Ranges: bytes`, and support
  for `Range: bytes=…` (`206 Partial Content`; `416` if unsatisfiable).
- Served as `attachment` by default. Add `?inline=1` (or `?inline=true`) to
  request inline rendering; this is honoured only when the MIME type is on the
  small inline-safe allow-list (images, plain text, audio, mp4/webm, PDF).

### 2.8 Backups

Backup object:

```json
{"id":"bc9f…","projectId":"9c1f…","fileName":"Notes-backup-20260204-015512.zip",
 "sizeBytes":524288,"includesFiles":true,"valid":true,"note":"",
 "createdAt":1759000000000,
 "downloadPath":"/api/v1/projects/9c1f…/backups/bc9f…/download"}
```

| Method & path | Body / query | Success |
|---|---|---|
| `GET /projects/{id}/backups` | — | `200 {"backups":[…]}` |
| `POST /projects/{id}/backups` | `{"includeFiles":true,"note":""}` | `201` backup object |
| `POST /projects/{id}/backups/import` | raw zip body, `?confirm=true` | `200 {"status":"ok"}` |
| `GET /projects/{id}/backups/{backupId}` | — | `200` backup object |
| `DELETE /projects/{id}/backups/{backupId}` | — | `200 {"status":"ok"}` |
| `POST /projects/{id}/backups/{backupId}/restore` | `{"confirm":true}` | `200 {"status":"ok"}` |
| `POST /projects/{id}/backups/{backupId}/validate` | — | `200 {"valid":true,…manifest}` |
| `GET /projects/{id}/backups/{backupId}/download` | — | `200` zip download |

- `includeFiles` defaults to `true`.
- A project keeps at most **25** backups; the oldest are pruned automatically.
- `restore` and `import` require `confirm` and replace the project's databases
  (and files when the archive includes them) only after a fully validated
  extraction. Invalid archives return `422`.

---

## 3. Status codes

| Code | When |
|---|---|
| `200 OK` | Successful read/update |
| `201 Created` | Resource created (project, database, file, backup, token) |
| `204 No Content` | CORS preflight |
| `400 Bad Request` | Invalid input, invalid JSON, missing confirmation |
| `401 Unauthorized` | Missing/invalid credential |
| `403 Forbidden` | Scope/read-only/non-admin violation, or SQL guard rejection |
| `404 Not Found` | Unknown project/database/file/backup/token/endpoint |
| `405 Method Not Allowed` | Wrong method for a known path |
| `408 Request Timeout` | Query exceeded its time budget |
| `409 Conflict` | Duplicate name, folder not empty, count limit |
| `410 Gone` | File metadata exists but content is missing on disk |
| `411 Length Required` | Streamed upload without `Content-Length` |
| `413 Payload Too Large` | Buffered body cap, upload limit, or quota exceeded |
| `422 Unprocessable Entity` | Backup archive failed validation |
| `429 Too Many Requests` | Rate limit exceeded |
| `500 Internal Server Error` | Unexpected server error |

---

## 4. Worked examples

### 4.1 Create a project, database and table, then insert and query

```bash
BASE=http://192.168.1.50:8080/api/v1
TOKEN=pst_xxxxxxxx

# 1. Create a project
PID=$(curl -s -X POST "$BASE/projects" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Notes"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# 2. Create a database
curl -s -X POST "$BASE/projects/$PID/databases" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"app"}'

# 3. Create a table (DDL is a write statement)
curl -s -X POST "$BASE/projects/$PID/databases/app/query" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sql":"CREATE TABLE notes(id INTEGER PRIMARY KEY, body TEXT)"}'

# 4. Insert with bound parameters
curl -s -X POST "$BASE/projects/$PID/databases/app/query" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sql":"INSERT INTO notes(body) VALUES(?)","params":["hello world"]}'

# 5. Read it back
curl -s -X POST "$BASE/projects/$PID/databases/app/query" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sql":"SELECT * FROM notes WHERE body = ?","params":["hello world"]}'
```

### 4.2 A website using the API (JavaScript `fetch`)

```html
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Notes</title></head>
<body>
<h1>Notes</h1>
<ul id="list"></ul>
<script>
  // A read-only, project-scoped token embedded in the page.
  const TOKEN = "pst_xxxxxxxx";
  const BASE  = "http://192.168.1.50:8080/api/v1";
  const PID   = "9c1f…";

  async function query(sql, params = []) {
    const res = await fetch(`${BASE}/projects/${PID}/databases/app/query`, {
      method: "POST",
      headers: { "Authorization": `Bearer ${TOKEN}`, "Content-Type": "application/json" },
      body: JSON.stringify({ sql, params })
    });
    if (!res.ok) throw new Error((await res.json()).error);
    return res.json();
  }

  (async () => {
    const { rows } = await query("SELECT id, body FROM notes ORDER BY id DESC LIMIT 20");
    const list = document.getElementById("list");
    for (const [id, body] of rows) {
      const li = document.createElement("li");
      li.textContent = body;
      list.appendChild(li);
    }
  })();
</script>
</body>
</html>
```

The device's CORS setting must allow the page's origin (default `*` allows
all). Embed only a **read-only** token in browser-delivered code — a
read-write token in a web page can be read by anyone who can view the page.

### 4.3 An app using the API (Kotlin/Java, HTTP)

```kotlin
val base = "http://192.168.1.50:8080/api/v1"
val token = "pst_xxxxxxxx"

// Upload a file (raw body keeps memory flat for large files)
val file = File("/sdcard/DCIM/camera/IMG_0001.jpg")
val conn = (URL("$base/projects/$pid/files?folder=albums&name=IMG_0001.jpg")
        .openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    doOutput = true
    setRequestProperty("Authorization", "Bearer $token")
    setRequestProperty("Content-Type", "image/jpeg")
    setFixedLengthStreamingMode(file.length())   // sends Content-Length, streams
}
file.inputStream().use { input -> conn.outputStream.use { it.write(input.readBytes()) } }
println(conn.responseCode)   // 201
```

### 4.4 Backups

```bash
# Create a backup (including files)
curl -s -X POST "$BASE/projects/$PID/backups" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"includeFiles":true,"note":"before migration"}'

# Download it
curl -s -H "Authorization: Bearer $TOKEN" \
  -o notes-backup.zip "$BASE/projects/$PID/backups/<backupId>/download"

# Validate and restore (restore replaces databases/files!)
curl -s -X POST "$BASE/projects/$PID/backups/<backupId>/validate" -H "Authorization: Bearer $TOKEN"
curl -s -X POST "$BASE/projects/$PID/backups/<backupId>/restore" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"confirm":true}'

# Import a backup produced on another device
curl -s -X POST "$BASE/projects/$PID/backups/import?confirm=true" \
  -H "Authorization: Bearer $TOKEN" --data-binary @notes-backup.zip
```

---

## 5. Notes on the SQL workspace

- One statement per request. Use the transaction endpoint for multi-step work.
- Values must be passed via `params`; string building is not needed and the
  guard blocks dangerous constructs (`ATTACH`, `DETACH`, `load_extension`,
  `writable_schema`, selected `PRAGMA`s, explicit transactions).
- Row results are paginated (`limit`/`offset`) and time-bounded.
- This is a local-first store, **not** a hardened multi-tenant SQL service. See
  [security.md](security.md) for the full threat model and production
  requirements (notably: put it behind HTTPS/VPN for any non-LAN access).

---

## 6. Ad Network API (port 8090)

The optional ad network is a **separate origin** with its own API and auth. It is documented in full in [ad-network.md](ad-network.md); this section is the endpoint index.

Base URL: `http://<phone-ip>:8090`. Management endpoints use `Authorization: Bearer <session-token>` from `/api/auth/login` (12-hour sessions). Public delivery has no account auth and is constrained by placement keys, site domains, signed tokens and rate limits.

### 6.1 Public delivery

| Method | Path | Behaviour |
|---|---|---|
| `GET` | `/health` | `{"status":"ok"}` |
| `GET` | `/sdk.js` | Embed script, `Cache-Control: public, max-age=3600` |
| `GET` | `/serve?p=<key>&u=<page>&l=<lang>` | JSON ad or `204` on no fill; requires `Origin`/`Referer` |
| `POST` | `/imp?t=<token>` | Viewable impression beacon, always `204` |
| `GET` | `/c?t=<token>` | Counts click then `302` to `click_url` |
| `GET` | `/media/<creativeId>` | Approved creative image |
| `OPTIONS` | public paths | CORS preflight |

### 6.2 Auth and accounts

| Method | Path | Role |
|---|---|---|
| `GET` | `/api/setup/status` | public |
| `POST` | `/api/setup` | public, phone setup code required |
| `POST` | `/api/auth/login` | public |
| `POST` | `/api/auth/signup` | public only when enabled |
| `GET` | `/api/auth/me` | any |
| `POST` | `/api/auth/logout` | any |
| `POST` | `/api/auth/password` | any |
| `GET` | `/api/meta` | any |

### 6.3 Management

| Area | Endpoints |
|---|---|
| Users (admin) | `GET|POST /api/users`, `PATCH|DELETE /api/users/:id`, `POST /api/users/:id/credit`, `POST /api/users/:id/payout` |
| Sites / placements | `GET|POST /api/sites`, `PATCH|DELETE /api/sites/:id`, `GET|POST /api/placements`, `PATCH|DELETE /api/placements/:id`, `GET /api/placements/:id/tag` |
| Campaigns | `GET|POST /api/campaigns`, `GET|PATCH /api/campaigns/:id`, `POST /api/campaigns/:id/{submit,approve,reject,pause,resume}` |
| Creatives | `GET|POST /api/creatives`, `PATCH|DELETE /api/creatives/:id`, `POST /api/creatives/:id/{image,approve,reject}` |
| Reports | `GET /api/reports/{summary,breakdown,export}`, `GET /api/billing` |
| Admin | `GET /api/admin/{overview,fraud,settings}`, `PUT /api/admin/settings` |

Dates use `YYYY-MM-DD` UTC. Reports accept `from`, `to`, `campaign_id`, `site_id`; `/breakdown` and `/export` also accept `by` (`campaign`, `site`, `placement`, `creative`, `day`). The default range is 29 days back from `to`; the maximum is 366 days.

### 6.4 Relationship to `/api/v1`

Nothing is shared: different port, database, files, auth and rate limiter. `/api/v1` tokens and the access password do not work on port 8090, and ad sessions do not work on port 8080. Ad-network data is not included in `/api/v1` project backups.
