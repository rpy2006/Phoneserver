# Remote access API (`/api/v1/remote`)

## Navigation

- [Documentation index](../README.md)
- [Data API](data-api.md)
- [HTTP API](http-api.md)
- [Remote access guide](../features/remote-access.md)
- [OpenAPI document](openapi.json)
- [Security overview](../security/overview.md)

This is the developer reference for the Phase 7 remote access surface. All paths
are relative to `/api/v1`; the full prefix is `/api/v1/remote`. Requests and
responses are JSON with `application/json; charset=utf-8` except binary file
downloads and uploads.

## 1. Authentication and transport

| Credential | Where it is used |
|---|---|
| `Authorization: Bearer <token>` | Login token or paired-device token. Remote sessions are resolved before normal API tokens. |
| `X-Auth-Password: <access password>` | Admin password; admin API tokens work the same way. |
| None | `/remote/auth/login`, `/remote/pairing/request` and `/remote/pairing/status` are dispatched before authentication. |

`RemoteSettings.enabled` defaults to `false`; a disabled feature returns `403`
for every remote route, including the public ones. With `requireHttps=true`
(the default), a request from a non-loopback address must have `secure=true` on
the server request or it is rejected with `403`. Loopback is always allowed.
`allowRemoteFiles` and `allowServerControl` both default to `false`.

Bearer tokens are returned once by login or by a successful pairing-status poll.
Only a SHA-256 hash is stored in `filesDir/remote/sessions.json`. A device token
is bound to its device id: it may report its own heartbeat but not another
device's.

Remote user passwords must be at least 10 characters. Usernames must be 3–32
characters from `[a-z0-9._-]`. Repeated failures lock the user account and then
the client IP.

## 2. Public endpoints

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/remote/auth/login` | `{"username":"viewer1","password":"at-least-10"}` | `200` `{token, expiresAt, user, warning}`; `401` bad password; `403` locked/disabled/insecure |
| `POST` | `/remote/pairing/request` | `{"code":"ABCD1234","name":"Tablet","deviceInfo":{...}}` | `200` `{requestId, status, expiresAt, pollAfterSeconds}`; `401` invalid/expired code; `403` pairing disabled |
| `POST` | `/remote/pairing/status` | `{"code":"ABCD1234"}` | `200` with `status` and, **only after approval**, `{token, deviceId, expiresAt}`; second poll has no token; `401` invalid code |

## 3. Authenticated endpoints

### Dashboard and sessions

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/remote/dashboard` | `remote.dashboard` | Server state, device/user/session counts, pending pairing, storage totals, last audit time and feature flags. |
| `POST` | `/remote/auth/logout` | any valid session | Revokes the current remote session. Password callers receive `403`. |

### Pairing administration

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/remote/pairing` | `remote.pairing.approve` | List pairing requests with public fields only. |
| `POST` | `/remote/pairing` | `remote.pairing.approve` | Create a code and QR payload. Body: `{"name":"Tablet"}`. Returns `201`. |
| `POST` | `/remote/pairing/{id}/approve` | `remote.pairing.approve` | Register a device and mark the request approved. |
| `POST` | `/remote/pairing/{id}/reject` | `remote.pairing.approve` | Reject a pending request. |
| `DELETE` | `/remote/pairing/{id}` | `remote.pairing.approve` | Delete/reject a request. |

### Devices

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/remote/devices` | `remote.devices.read` | List devices with `online`, `status`, `metricsAvailable` and `metricsStale`. |
| `GET` | `/remote/devices/{id}` | `remote.devices.read` | Read one device. |
| `PATCH` (or `PUT`) | `/remote/devices/{id}` | `remote.devices.write` | Rename with `{"name":"..."}`. |
| `DELETE` | `/remote/devices/{id}` | `remote.devices.write` | Revoke the device and its sessions. |
| `POST` | `/remote/devices/{id}/heartbeat` | `remote.devices.read` | Apply a metrics report. A device token may only use its own id. |
| `POST` | `/remote/devices/{id}/disconnect` | `remote.devices.write` | Mark disconnected and revoke the device session. |

A heartbeat report may contain `appVersion`, `model`, `manufacturer`,
`osVersion`, `uptimeMillis`, `storageTotalBytes`, `storageFreeBytes`,
`activeConnections` and `healthStatus`. `metricsAvailable` requires a real
report; `metricsStale` becomes true after three offline thresholds. Values are
never synthesised for a device that has not reported.

### Users and roles

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/remote/users` | `remote.users.read` | List users without password hashes. |
| `POST` | `/remote/users` | `remote.users.write` | Create a user (`201`). Body: `username`, `password`, `displayName`, `role`, `grantedPermissions`, `deniedPermissions`. |
| `GET` | `/remote/users/{id}` | `remote.users.read` | Read a user. |
| `PATCH` (or `PUT`) | `/remote/users/{id}` | `remote.users.write` | Update name, role, status, password or permission overrides; revokes that user's sessions. |
| `DELETE` | `/remote/users/{id}` | `remote.users.write` | Delete a user and revoke sessions. The owner account is protected. |
| `DELETE` | `/remote/users/{id}/sessions` | `remote.users.write` | Revoke all sessions of that user. |
| `GET` | `/remote/roles` | `remote.roles.read` | Role names, per-role permissions and all permission strings. |

### Audit and settings

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/remote/audit?limit=200` | `remote.audit.read` | Recent sanitised audit entries; `limit` is clamped to 20–500 (default 200). |
| `GET` | `/remote/settings` | `remote.settings.read` | Current remote settings. |
| `PATCH` (or `PUT`) | `/remote/settings` | `remote.settings.write` | Apply remote settings and audit the change. |

### Server control and files

| Method | Path | Permission | Description |
|---|---|---|---|
| `POST` | `/remote/server/start` | `remote.server.control` | Start the foreground server; also requires `allowServerControl`. |
| `POST` | `/remote/server/stop` | `remote.server.control` | Stop the foreground server; also requires `allowServerControl`. |
| `POST` | `/remote/server/restart` | `remote.server.control` | Restart the foreground server; also requires `allowServerControl`. |
| `GET` | `/remote/files/list?path=` | `remote.files.read` | List a directory under the public files root. |
| `GET` | `/remote/files/download?path=&inline=1` | `remote.files.read` | Stream a file; `inline=1` only applies to inline-safe MIME types. |
| `POST` | `/remote/files/upload?path=&name=&overwrite=` | `remote.files.write` | Write the raw request body to a file; `201` with `bytesWritten`. |
| `POST` | `/remote/files/delete` | `remote.files.write` | Body `{"path":"..."}`; the storage root itself cannot be deleted. |
| `POST` | `/remote/files/rename` | `remote.files.write` | Body `{"path":"old.txt","newName":"new.txt"}`. |
| `POST` | `/remote/files/mkdir` | `remote.files.write` | Body `{"path":"parent","name":"folder"}`. |

Every file route returns `403` when `allowRemoteFiles` is false. Paths are
resolved under the public files directory; absolute paths and `..` escapes are
rejected with `400 "unsafe path"`.

## 4. RBAC permission names

| Permission string | Grants |
|---|---|
| `remote.dashboard` | Dashboard summary |
| `remote.devices.read` | Device list/detail and heartbeats |
| `remote.devices.write` | Rename, disconnect, revoke devices |
| `remote.pairing.approve` | Create, list, approve and reject pairing requests |
| `remote.users.read` | User list/detail |
| `remote.users.write` | Create, update, delete users and revoke user sessions |
| `remote.roles.read` | Role/permission catalogue |
| `remote.roles.write` | Reserved for role management |
| `remote.files.read` | File listing and download |
| `remote.files.write` | Upload, delete, rename and mkdir |
| `remote.shares.write` | Reserved for remote share operations |
| `remote.audit.read` | Remote audit log |
| `remote.settings.read` | Read remote settings |
| `remote.settings.write` | Change remote settings |
| `remote.server.control` | Start/stop/restart the server |

| Role | Permissions |
|---|---|
| `owner`, `administrator` | All permissions above |
| `manager` | dashboard, devices read, files read/write, shares write |
| `viewer` | dashboard, devices read, files read |
| `device` | dashboard, devices read, files read/write |

A user's effective permissions are role permissions plus `grantedPermissions`
minus `deniedPermissions`; a session can apply further grants and denials. The
access password and admin tokens bypass this matrix.

## 5. Pairing lifecycle

1. **Create:** `POST /remote/pairing` returns `code` and `qrPayload`
   (`phoneserver://pair?scheme=…&host=…&code=…`). The QR payload has no token or
   long-lived credential and expires with the request.
2. **Submit:** `POST /remote/pairing/request` matches the code by hash, records
   the device name/info and returns `status: pending`.
3. **Approve/reject:** the owner reviews and calls `/pairing/{id}/approve` or
   `/pairing/{id}/reject`. Approval registers a device record.
4. **Poll:** the device calls `/pairing/status`. An approved request creates a
   device session, consumes the request and returns the token **once** with
   `status: consumed`. Any later poll returns no token.
5. **Use:** the token authenticates `/remote/devices/{ownId}/heartbeat` and any
   other permission granted to the `device` role. Revoke the device to revoke
   its session.

## 6. File operations

`path` is slash-separated and relative to `ContentStore.getPublicFilesDir`.
Uploads send the file bytes as the raw body with query parameters `path`
(directory), `name` (filename) and `overwrite=true` to allow replacing an
existing file. Download sends `Content-Disposition: attachment` unless
`inline=1` and the MIME type is safe for inline display. `delete`, `rename` and
`mkdir` take JSON bodies. Names are sanitised, hidden files follow the host
setting, and uploads are capped by the server's upload limit. All operations are
written to the audit log.

## 7. Errors

| Status | Meaning |
|---|---|
| `400` | Invalid JSON, invalid username/password/pagination value, unsafe path or invalid file name. |
| `401` | Wrong remote password, invalid/expired pairing code, or unauthenticated non-remote `/api/v1` route. |
| `403` | Remote access disabled, HTTPS required, pairing/file/server control disabled, missing permission, locked account, cross-device heartbeat, or protected owner change. |
| `404` | Unknown endpoint, user, device, file or directory. |
| `405` | Method not allowed on a known route. |
| `409` | Username/device conflict, incomplete approval or consumed pairing request. |
| `413` | Upload exceeds the configured limit. |
| `429` | Per-IP or per-token rate limit exceeded. |
| `500` | Unexpected server error. |

Errors use `{"error":"<message>","status":<code>}`.

## 8. Examples

```bash
curl -X POST https://phone.example:8443/api/v1/remote/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"viewer1","password":"long-password-123"}'

curl -H 'Authorization: Bearer REMOTE_TOKEN' \
  https://phone.example:8443/api/v1/remote/dashboard

curl -X POST -H 'X-Auth-Password: ADMIN_PASSWORD' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Tablet"}' \
  https://phone.example:8443/api/v1/remote/pairing

curl -X POST -H 'Authorization: Bearer DEVICE_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"uptimeMillis":1234,"healthStatus":"ok"}' \
  https://phone.example:8443/api/v1/remote/devices/DEVICE_ID/heartbeat
```
