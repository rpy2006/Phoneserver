# Configuration

PhoneServer settings are stored app-private. Most are edited in the app's settings/security screens; API endpoint records are stored separately under the `api_endpoints` preferences file.

## Navigation

- [Documentation index](../README.md)
- [Installation](installation.md)
- [Feature overview](../features/overview.md)
- [Security overview](../security/overview.md)
- [Storage layout](../architecture/storage.md)

## Server access

| Setting | Effect | Source key |
|---|---|---|
| Access password | Required for file API and protected admin/data routes unless a bearer token is used | `access_password` |
| Username | Stored display/admin username | `auth_username` |
| Start on boot | Restores the desired running state after device boot | `start_on_boot`, `desired_running` |
| Allow remote access | Permits connections from non-loopback interfaces | `allow_remote_access` |
| Show hidden files | Controls hidden-file visibility in browse UI/API | `show_hidden_files` |

If no password is configured, anonymous admin API access is refused. Protected file routes accept `X-Auth-Password`, `?pw=`, HTTP Basic, or `Authorization: Bearer <token>`. `/api/v1` requires a bearer token except `GET /health` and CORS preflight.

See [HTTP API](../api/http-api.md) and [Data API](../api/data-api.md).

## Ports

| Service | Default port | Notes |
|---|---|---|
| Main HTTP server | `8080` | Fixed in `ServerService.PORT` |
| HTTPS listener | `8443` | Configured in `HttpsConfig`; runs alongside HTTP |
| Ad Network Manager | `8090` | Opt-in, separate server and data store |

No automatic HTTP-to-HTTPS redirect is performed. The ad server does not provide TLS; it should only be exposed on a trusted network or behind a TLS-terminating proxy.

## Data root

The data platform defaults to an app-private data root. A custom root can be selected in the app; it is persisted as a string path under `data_root`. Shared-media uploads still go to the device's real `Pictures/`, `Movies/`, `Music/`, and `Download/` directories and are scanned into MediaStore.

See [Storage layout](../architecture/storage.md).

## CORS and rate limiting

Allowed origins are stored as a comma-separated string under `allowed_origins`. API rate limiting is stored as requests per minute under `api_rate_limit_per_minute`. Both are enforced by the HTTP/server layer for protected API traffic.

## HTTPS and public share links

A public/tunnel URL must be an HTTPS origin such as `https://example.com` or `https://example.com:8443`; it must not contain a path. Share URLs are generated as `/s/<token>`. With only the generated self-signed certificate, local share links stay on HTTP because browsers reject that certificate.

See [Security overview](../security/overview.md).

## Mail accounts

Mail credentials are encrypted with a per-account Android Keystore key. Each account is verified against the configured SMTP and IMAP servers before it is saved. Passwords are never returned by the API.

See [Email setup](email-setup.md) and [Mail client](../features/mail.md).
