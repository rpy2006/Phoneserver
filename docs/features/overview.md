# Feature overview

PhoneServer is one Android application (`:app`) with several cooperating subsystems. All network servers are in-process; there is no backend service.

## Navigation

- [Documentation index](../README.md)
- [Installation](../getting-started/installation.md)
- [Architecture](../architecture/overview.md)
- [HTTP API](../api/http-api.md)
- [Data API](../api/data-api.md)

## Local HTTP server

The main server is started by `ServerService` and listens on `8080` by default. It serves:

- the uploaded website from `files/website/` at `/` and `/index.html`;
- file browse, download, upload, create, rename, and delete routes;
- JSON status, statistics, usage, and endpoint activity;
- user-defined JSON endpoints under `/api/<name>`;
- WebDAV, share links, token management, and system configuration routes;
- the `/api/v1` data API and `/api/v1/mail` mail API.

See [HTTP API](../api/http-api.md) and [Data and storage API](../api/data-api.md).

## HTTPS and remote access

An optional HTTPS listener runs on `8443` by default and can be configured with a generated self-signed certificate or a CA-signed certificate. Remote access is controlled by app settings. A public/tunnel URL must be an HTTPS origin and is used for share links; browsers reject the generated self-signed certificate, so local links remain on HTTP unless a trusted certificate or public origin is configured.

See [Configuration](../getting-started/configuration.md) and [Security overview](../security/overview.md).

## Client mode

The app can connect to another PhoneServer instance, list its shared files, and download or upload through the same HTTP API. Pairing and remote credentials are handled in the UI and stored in app-private preferences/database records.

## Data platform

The `/api/v1` API exposes projects, databases, files, backups, tokens, usage statistics, scheduled tasks, webhooks, sharing, and security configuration. Data is stored in app-private SQLite and JSON files; uploaded media is written to shared storage and indexed with `MediaScannerConnection`.

See [Storage layout](../architecture/storage.md).

## System management

The **System Management** screen and `/api/v1/system/*` routes report live server
and resource state, schedule automation, run diagnostics, inspect a unified
system log, verify backups, clean only app view-cache files, and start/stop the
foreground server. Monitoring uses real `ServerStats`, `DeviceStats`, storage and
uptime values; app CPU is explicitly reported as app-process CPU because Android
does not expose system-wide CPU without elevated privileges.

Notification channels cover server failures/recovery, task results, low storage
and maintenance completion. Watchdog auto-restart and boot start honor Android
background limits and are stored in app-private JSON. See
[Data API](../api/data-api.md#system-management-systemread-or-admin) for the API.

## Mail

The mail feature is a client for an external SMTP/IMAP provider. It supports multiple accounts, TLS/STARTTLS, password/app-password login, inbox/sent/drafts folders, attachments, local search, and a REST API. It does not publish SMTP or IMAP ports.

See [Email setup](../getting-started/email-setup.md) and [Mail client](../features/mail.md).

## Ad Network Manager

The ad network is an optional, separate server on `8090`. It is off by default. When enabled it maintains its own accounts, campaigns, sites, placements, creatives, targeting, impression/click counting, fraud checks, and payout ledger in encrypted JSON.

See [Ad Network Manager](ad-network.md).

## On-device AI

`ChatFragment` and `LlmEngine` provide a small on-device chat surface backed by the bundled `llama_bridge` native library and a GGUF model managed by `ModelStore`. If the native library is unavailable, the UI reports that chat/LLM features are disabled. The build currently bundles only the `arm64-v8a` native library.

## Offline documentation

`DeveloperDocsActivity` loads `file:///android_asset/docs/index.html`, which bundles a local docs site, search, API explorer, and OpenAPI document. The canonical Markdown sources for that content live in this `docs/` folder; the files under `app/src/main/assets/docs/` are bundled runtime copies used by the app.
