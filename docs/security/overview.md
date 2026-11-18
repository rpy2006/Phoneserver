# Security overview

This page summarizes the security controls that are present in the current code. For the audit findings and fixes, see [Security audit](audit.md).

## Navigation

- [Documentation index](../README.md)
- [Configuration](../getting-started/configuration.md)
- [HTTP API](../api/http-api.md)
- [Data API](../api/data-api.md)
- [Security audit](audit.md)

## Network boundary

The main HTTP server listens on `8080`; the optional HTTPS listener uses `8443` by default. There is no automatic redirect from HTTP to HTTPS. The opt-in ad server is separate on `8090` and has no TLS support of its own, so expose it only on a trusted network or behind a TLS-terminating proxy.

Remote access is gated by the `allow_remote_access` setting. Public share links require an HTTPS public origin; local links remain on HTTP when only the generated self-signed certificate is available.

## Authentication

- File API and WebDAV accept `X-Auth-Password`, `?pw=`, HTTP Basic, or `Authorization: Bearer <token>`.
- `/status` and the hosted website are public.
- `/api/v1` requires a bearer token except `GET /health` and CORS preflight.
- Admin-only routes require the `auth.admin` token policy.
- When no password is configured, anonymous admin API access is refused.
- Password checks also accept the basic-auth password form used by WebDAV clients.

Token records are hashed and scoped by permissions. See [Data API](../api/data-api.md) for token routes and permission names.

## Request hardening

`SimpleHttpServer` enforces limits on header line length, header count, socket read timeout, concurrent clients, and request bodies. Upload paths are normalized and rejected when they escape the intended root. Response content is escaped where user-controlled file names or paths are rendered.

## Storage and secrets

Settings, tokens, sharing state, TLS configuration, and mail data are app-private. Mail passwords are encrypted with a per-account Android Keystore AES/GCM key; ad-network and mail JSON stores use encryption at rest. Passwords and secrets are not returned by APIs or logged in plaintext.

See [Storage layout](../architecture/storage.md).

## Mail security

PhoneServer is a mail client, not a mail server. It requires an external SMTP/IMAP provider, TLS or STARTTLS, and password/app-password authentication. It does not support OAuth2, POP3, unencrypted mail, or trusting self-signed/untrusted certificates. Credentials are verified before an account is saved.

See [Email setup](../getting-started/email-setup.md) and [Mail client](../features/mail.md).

## Deployment guidance

- Use a CA-signed certificate or a trusted HTTPS reverse proxy when exposing any service beyond the local network.
- Keep `allow_remote_access`, ad network, and share links disabled unless needed.
- Rotate bearer tokens and mail app passwords when a device is lost or a user changes role.
- Do not place credentials in custom JSON endpoints, website files, screenshots, or docs.
- Backups contain sensitive state; store them only in trusted locations.

## Verification status

Security fixes have automated regression coverage in the unit-test suite. The latest documented run passed `157` tests with `0` failures, `0` errors, and `50` skipped. No claim is made here about penetration testing or live external verification.
