# Remote access and multi-device management

## Navigation

- [Documentation index](../README.md)
- [Feature overview](overview.md)
- [Remote access API](remote-api.md)
- [Configuration](../getting-started/configuration.md)
- [Security overview](../security/overview.md)

Remote access lets an approved second PhoneServer device or a trusted client manage
this phone over the network. It is an **opt-in** feature under
`/api/v1/remote/*`. Nothing is exposed until the owner turns the feature on, and
the default settings keep pairing available but file access and server control
off.

## 1. Turn remote access on

Open the app's remote-access settings (or patch `/api/v1/remote/settings` with an
administrator credential) and review each switch:

| Setting | Default | What it does |
|---|---|---|
| `enabled` | `false` | Master switch. While off, every remote endpoint answers `403`, including login and pairing. |
| `requireHttps` | `true` | Non-loopback requests must arrive over the HTTPS listener. Loopback (`127.0.0.1`, `::1`) is exempt for local diagnostics. |
| `allowPairing` | `true` | Permits devices to submit a pairing code and poll its status. |
| `allowRemoteFiles` | `false` | Enables `/remote/files/*` list, download, upload, delete, rename and mkdir. |
| `allowServerControl` | `false` | Enables start/stop/restart of the foreground server. |
| `sessionTtlMinutes` | `720` | Lifetime of a user login token. |
| `deviceSessionTtlDays` | `7` | Lifetime of a paired-device token. |
| `pairingTtlMinutes` | `10` | How long an unapproved pairing code stays valid. |
| `offlineThresholdSeconds` | `180` | Heartbeat age after which a device is shown as offline. |
| `maxFailedLoginAttempts` | `5` | Failures before a remote user account is temporarily locked. |
| `lockoutMinutes` | `15` | Lockout duration after repeated failed logins. |

Changing the master switch or any safety switch takes effect on the next request;
no restart is needed.

## 2. HTTPS and addressing

`requireHttps` is on by default because a bearer token sent over plain HTTP can be
observed by anyone on the path. Use the HTTPS listener (default port `8443`) with
a certificate the client trusts, or terminate TLS at a reverse proxy and forward
to the phone. Plain HTTP is accepted only from loopback or when you deliberately
turn `requireHttps` off.

There is **no static IP assumption**. The phone may be on Wi-Fi, a hotspot, a
LAN with DHCP, or reachable through a tunnel; clients use whatever host and port
reach the HTTPS listener. The pairing QR payload records the scheme, host and
code from the request that created it, but it contains **no permanent credential
and no token**. A device still has to submit the short-lived code, be approved,
and then poll for its own one-time token.

Because the generated self-signed certificate is not trusted by other devices by
default, install a trusted certificate or use a TLS-terminating tunnel for
production use. Never disable HTTPS checking on a public network.

## 3. Add a device with QR/code pairing

1. On the owner's session, create a pairing request with `POST /api/v1/remote/pairing`.
   The response contains a short code, a QR payload, an expiry and a request id.
   The code is hashed at rest; the plaintext appears only in this response.
2. On the new device, submit `POST /api/v1/remote/pairing/request` with the code
   and optional device information (name, model, manufacturer, OS and app
   version). This registers a **pending** request; it does not issue a token.
3. The owner reviews pending requests in `GET /api/v1/remote/pairing` and approves
   with `POST /api/v1/remote/pairing/{id}/approve`, or rejects with
   `POST /api/v1/remote/pairing/{id}/reject`.
4. The device polls `POST /api/v1/remote/pairing/status` with the same code. Only
   after approval does the poll return a bearer token and a device id. The token
   is shown **once**; store it securely on the device.
5. A second status poll returns `status: consumed` and no token. The pairing
   record keeps only the token hash for auditing.

A rejected, expired or deleted request never yields a token. Delete a paired
device with `DELETE /api/v1/remote/devices/{id}` to remove it from the device
list and revoke every session belonging to it.

## 4. Users, roles and permissions

Remote logins use `POST /api/v1/remote/auth/login` with a username and password.
Remote user passwords must be at least 10 characters. Usernames are 3–32
characters using lowercase letters, digits, `.`, `_` and `-`.

| Role | Typical use | Access |
|---|---|---|
| `owner` | The device owner | Everything, including changing or deleting other users; cannot be downgraded or suspended. |
| `administrator` | Full remote administration | Everything, including users, audit, settings and server control. |
| `manager` | Day-to-day operations | Dashboard, device monitoring, file read/write and share operations; cannot manage users. |
| `viewer` | Read-only monitoring | Dashboard, device list and file read. |
| `device` | Assigned automatically to paired devices | Dashboard, device list, own heartbeat and file access. |

Every role starts from a fixed permission set, and a user can be given extra
`grantedPermissions` or `deniedPermissions`. Denials win over grants. The
`GET /api/v1/remote/roles` response lists all role names and permission strings.
The admin access password and an admin API token act as an administrator for
remote routes.

## 5. Monitor devices and metrics

The dashboard (`GET /api/v1/remote/dashboard`) reports device totals, online
counts, pending pairing requests, active sessions, projects, files and the last
audit timestamp. `GET /api/v1/remote/devices` and
`GET /api/v1/remote/devices/{id}` add per-device state:

- `online` is derived from the last heartbeat and `offlineThresholdSeconds`.
- `metricsAvailable` is true only after a device has sent a real heartbeat report
  containing metrics such as uptime, storage, connections or health status.
- `metricsStale` marks a report older than three offline thresholds; stale
  metrics are flagged rather than silently reused as live values.
- `status` is `online`, `offline`, `disconnected` or `revoked`.

No metric is invented when a device is offline: it is simply unavailable. A
device may only send a heartbeat for its own id; an administrator or manager can
send heartbeats for any device. Use `/devices/{id}/disconnect` to mark a device
disconnected without deleting its registration, and `/devices/{id}/heartbeat`
with an empty report to record liveness.

## 6. Audit log, files and sessions

`GET /api/v1/remote/audit?limit=200` requires `remote.audit.read`. It returns the
most recent entries for logins, pairing, approvals, device
changes, user changes, settings, server control, file operations and denied
access. Secrets such as passwords and bearer values are redacted before an entry
is written.

Remote file endpoints are unavailable unless `allowRemoteFiles` is enabled.
Paths are relative to the public files directory and are rejected if they try to
escape it with `..`. Uploads are size limited, names are sanitised, and each
list/download/upload/delete/rename/mkdir operation is audited. Server start, stop
and restart require both `remote.server.control` and `allowServerControl`.

Sessions can be revoked in several ways:

- `POST /api/v1/remote/auth/logout` revokes the current login token.
- `DELETE /api/v1/remote/users/{id}/sessions` revokes every session of a user.
- Changing or disabling a user also revokes that user's sessions.
- Disconnecting or revoking a paired device revokes its device session.
- Reaching the session expiry time invalidates the token automatically.

## 7. Limits

- Pairing codes expire, accept only a small number of attempts, and never carry
  a permanent credential.
- Login attempts are rate limited per client IP and user accounts are locked
  after repeated failures.
- Remote sessions are bearer tokens; anyone holding one has its permissions, so
  store it in app-private/secure storage and never place it in a QR code, URL or
  log message.
- Heartbeat metrics are point-in-time reports, not a guarantee that the remote
  device is healthy; stale values are labelled.
- The phone must be reachable over the network and the HTTPS listener must be
  running. No cloud relay, static IP or DNS name is provided by the app.
- Remote file access follows the host app's storage permissions and public files
  root; it does not grant access outside that root.

For request and response details, see the [remote access API reference](../api/remote-api.md)
and the bundled [OpenAPI document](openapi.json).
