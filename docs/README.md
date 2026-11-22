# PhoneServer documentation

PhoneServer is a single-module Android app that runs an HTTP server on the device, exposes a local file/website API, provides a versioned JSON data API, and can optionally run a separate ad-network server. This folder is the canonical documentation set; the root [README](../README.md) is a short entry point.

## Navigation

- [Root README](../README.md)
- [Published documentation site](../index.html)
- [Changelog](changelog.md)

## Get started

- [Installation and platform support](getting-started/installation.md) — device, SDK, build, and run requirements.
- [Configuration](getting-started/configuration.md) — access credentials, ports, data root, CORS, rate limits, HTTPS, and sharing.
- [Email account setup](getting-started/email-setup.md) — SMTP/IMAP account values, provider presets, VPS setup, and troubleshooting.

## Architecture and features

- [Feature overview](features/overview.md) — what the app can do, including monitoring, automation and system management.
- [Architecture](architecture/overview.md) — package layout, layering, request flow, and design decisions.
- [Storage layout](architecture/storage.md) — SQLite, encrypted JSON, app-private files, and shared media.
- [Mail client](features/mail.md) — external SMTP/IMAP integration, local cache, and API surface.
- [Ad Network Manager](features/ad-network.md) — opt-in ad server, accounts, campaigns, creatives, counting, and billing.
- [Phase 8 backup/import/export](features/phase8-backup-import-export.md) — encrypted backup packages, validation, restore, config import/export, recovery, rollback.
- [Remote access and management](features/remote-access.md) — enable switch, HTTPS, QR pairing, roles, device monitoring, audit and file controls.

## API reference

- [HTTP API](api/http-api.md) — file, website, stats, auth, WebDAV, sharing, tokens, and system routes.
- [Data and storage API](api/data-api.md) — `/api/v1` JSON API for projects, databases, files, backups, tokens, mail, and `/system/*` management.
- [Remote access API](api/remote-api.md) — `/api/v1/remote` login, pairing, RBAC, devices, files, audit and settings endpoints.
- [OpenAPI document](api/openapi.json) — machine-readable description of the `/api/v1` surface, including remote management.

## Security and development

- [Security overview](security/overview.md) — current controls and deployment guidance.
- [Security audit](security/audit.md) — findings, fixes, and regression notes.
- [Testing](development/testing.md) — commands, test layout, and manual checks.
