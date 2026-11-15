# PhoneServer HTTP API

Base URLs:

- HTTP: `http://<device-ip>:8080`
- HTTPS: `https://<device-ip>:8443` when enabled (port configurable)

HTTPS runs alongside HTTP. The server does not automatically redirect HTTP to
HTTPS, and the optional ad network has its own server on `:8090`.

## Authentication

| Route group | Accepted credentials |
|---|---|
| Public website / `/status` | None |
| File API / WebDAV | `X-Auth-Password`, `?pw=`, HTTP Basic, or `Authorization: Bearer <token>` |
| `/api/v1` data/storage | Access password or bearer token with the required permission/scope |
| `/api/pair` | Pairing code in the JSON body |
| Custom `/api/<name>` | Public by design |

Bearer tokens are stored only as SHA-256 hashes. File and WebDAV routes require
`files.read` or `files.write`; `/api/v1` enforces roles, permissions, project
scope, expiry and per-token rate limits.

## Public endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Hosted `files/website/index.html` or built-in page |
| `GET` | `/status` | Reachability JSON |
| `GET` | `/<asset>` | Other files under `files/website/` |
| `GET/POST/PUT/DELETE` | `/api/<custom-name>` | User-defined endpoints from the API editor |

## Files and statistics

| Method | Path | Description |
|---|---|---|
| `GET` | `/files` | HTML index of shared files |
| `GET` | `/files/<path>` | Download a shared file |
| `GET` | `/api/browse?path=` | JSON folder listing |
| `POST` | `/api/mkdir` | Create a folder |
| `POST` | `/api/delete` | Delete a file/folder recursively |
| `POST` | `/api/rename` | Rename a file/folder |
| `POST` | `/api/upload?path=` | Multipart upload, field `file` |
| `GET` | `/api/stats` | Live server statistics |

## Secure pairing

`POST /api/pair`

```json
{"code":"ABCD-EFGH","deviceName":"Pixel"}
```

Response:

```json
{
  "token": "pst_...",
  "tokenId": "...",
  "role": "editor",
  "expiresAt": 1760000000000,
  "serverName": "PhoneServer",
  "baseUrl": "https://192.168.1.20:8443",
  "fingerprint": "AA:BB:..."
}
```

Pairing codes are one-time, hashed at rest, limited to eight attempts, expire
after ten minutes, and `/api/pair` is limited to 12 requests per minute per IP.
The issued editor token expires after 30 days and has a 240 request/minute
policy.

## WebDAV

WebDAV is available at `/dav/` when **Allow remote file access** is enabled.
It is rooted at the shared files folder.

Supported methods: `OPTIONS`, `PROPFIND` (Depth 0/1), `GET`, `HEAD`, `PUT`,
`DELETE`, `MKCOL`, `MOVE`, `COPY`. `PUT` is streamed and capped by the global
upload limit. `LOCK`, `UNLOCK` and `PROPPATCH` return `405`.

## `/api/v1` data and storage API

The versioned API provides projects, SQLite databases, files, backups, tokens,
statistics and operations. It uses bearer tokens or the access password and
enforces project ownership, permissions and token policy.

| Area | Example endpoints |
|---|---|
| Projects | `GET/POST /api/v1/projects`, `GET/PATCH/DELETE /api/v1/projects/{id}` |
| Databases | `GET/POST /api/v1/projects/{id}/databases`, `POST .../query`, `POST .../execute` |
| Files | `GET/POST /api/v1/projects/{id}/files`, `GET .../files/{fileId}/content` |
| Backups | `GET/POST /api/v1/projects/{id}/backups`, restore/import/download |
| Tokens | `GET/POST /api/v1/tokens`, `PATCH/DELETE /api/v1/tokens/{id}` |
| Stats | `GET /api/v1/stats`, `GET /api/v1/operations` |

Errors use a JSON object with an `error` string and, for the versioned API, an
HTTP status matching the failure.

## Ad network API

The ad network is a separate origin on `:8090` with its own database, accounts
and bearer sessions. See [ad-network.md](ad-network.md).
