# PhoneServer

![PhoneServer Banner](banner.png)

PhoneServer is an Android app that turns a phone into a network file server, a small website host, a local JSON API platform, an optional self-hosted ad network, and a client for external SMTP/IMAP mail accounts. It runs in-process on the device; there is no backend service.

- Main HTTP server: `http://<phone-ip>:8080`
- Optional HTTPS listener: `https://<phone-ip>:8443` by default
- Optional Ad Network Manager: `http://<phone-ip>:8090`, off by default
- Minimum Android API: `24`
- Build: one Gradle module (`:app`), Gradle wrapper `9.0.0`, Android Gradle Plugin `8.5.2`, Java source/target `11`

[View the published documentation](https://rpy2006.github.io/Phoneserver/) or start with the [documentation index](docs/README.md).

## Quick start

1. Install the APK on Android API 24 or newer.
2. Grant storage access only for the shared-storage browsing/upload features you intend to use.
3. Start the server from the app and open `http://<phone-ip>:8080/status`.
4. Set an access password or issue a bearer token before exposing protected routes.
5. Configure HTTPS and a public HTTPS origin before creating public share links.

See [installation](docs/getting-started/installation.md), [configuration](docs/getting-started/configuration.md), and [security](docs/security/overview.md).

## What is included

- Local file browsing, upload, download, rename, delete, and hosted website files.
- User-defined JSON endpoints under `/api/<name>`, status/statistics screens, WebDAV, and share links.
- A versioned `/api/v1` JSON API for projects, databases, files, backups, tokens, usage, webhooks, scheduled tasks, settings, and system management.
- A System Management screen and `/api/v1/system/*` routes for live monitoring, automation, diagnostics, logs, maintenance, and server lifecycle control.
- Opt-in remote access and multi-device management with HTTPS-gated login, QR/code pairing approval, roles, heartbeat metrics, audit logs and file access controls.
- External SMTP/IMAP mail accounts with TLS/STARTTLS, Keystore-protected credentials, inbox/sent/drafts, attachments, and local search.
- An optional ad network with its own port, encrypted JSON store, accounts, campaigns, placements, creatives, tracking, and payouts.
- A small on-device chat surface backed by the bundled `llama_bridge` native library; only `arm64-v8a` is bundled today.
- Built-in offline developer docs with searchable navigation, an API explorer, and an OpenAPI document.

Feature details live in [feature overview](docs/features/overview.md), [mail](docs/features/mail.md), and [ad network](docs/features/ad-network.md).

## Build and test

Use an Android SDK and a JDK supported by the Gradle wrapper. The test environment used JDK 21 with `ANDROID_HOME=/tmp/android-sdk`.

```sh
ANDROID_HOME=/tmp/android-sdk sh gradlew testDebugUnitTest assembleDebug --offline
```

Latest documented result: `315` tests, `0` failures, `0` errors, `171` skipped. The build emits a non-fatal warning that `libllama_bridge.so` cannot be stripped and is packaged as-is.

See [testing](docs/development/testing.md).

## API

- [HTTP API](docs/api/http-api.md) — legacy/file/website/system routes, auth, WebDAV, sharing, and tokens.
- [Data and storage API](docs/api/data-api.md) — `/api/v1` resource API, including mail.
- [Remote access API](docs/api/remote-api.md) — `/api/v1/remote` login, pairing, RBAC, devices, files and audit.
- [OpenAPI JSON](docs/api/openapi.json) — machine-readable `/api/v1` reference, including remote management.

## Architecture

The app is split into UI, network, data, security, ads, mail, AI, and docs packages under `app/src/main/java/com/yadaventerprise/phoneserver`. Requests flow through `SimpleHttpServer` into `ServerService`/`DataApi` and platform stores. See [architecture](docs/architecture/overview.md) and [storage layout](docs/architecture/storage.md).

## Documentation map

The canonical Markdown documentation is under [`docs/`](docs/README.md):

| Area | Document |
|---|---|
| Setup | [Installation](docs/getting-started/installation.md), [Configuration](docs/getting-started/configuration.md), [Email setup](docs/getting-started/email-setup.md) |
| Architecture | [Overview](docs/architecture/overview.md), [Storage](docs/architecture/storage.md) |
| Features | [Overview](docs/features/overview.md), [Mail](docs/features/mail.md), [Ad network](docs/features/ad-network.md), [Remote access and management](docs/features/remote-access.md) |
| API | [HTTP API](docs/api/http-api.md), [Data API](docs/api/data-api.md), [Remote API](docs/api/remote-api.md), [OpenAPI](docs/api/openapi.json) |
| Security | [Overview](docs/security/overview.md), [Audit](docs/security/audit.md) |
| Development | [Testing](docs/development/testing.md), [Changelog](docs/changelog.md) |

The published site is rendered from `index.html` and `web/data/docs.js`. Files under `app/src/main/assets/docs/` are bundled runtime copies used by the in-app docs viewer.
