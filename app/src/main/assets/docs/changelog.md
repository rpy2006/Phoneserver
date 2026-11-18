# Changelog

## Navigation

- [Documentation index](README.md)
- [Root README](../README.md)
- [Testing](development/testing.md)

## Phase 6
- Added a System Management screen and `/api/v1/system/*` routes for live monitoring, automation, diagnostics, logs, maintenance and server lifecycle control.
- Added one-time and recurring scheduled tasks with bounded retries, persisted execution history and failure/recovery notifications.
- Added configurable webhook methods, headers, timeouts and attempts, with signed delivery, secret redaction and expanded event coverage.
- Added watchdog auto-restart, boot start, storage warnings, notification channels, and explicit confirmation for temporary-file cleanup.

## Phase 5
- Added external SMTP/IMAP mail accounts with TLS/STARTTLS, Android Keystore credential protection, and encrypted local mail data.
- Added Inbox/Sent/Drafts mail screens, compose with attachments, message detail, attachment sharing, and local search.
- Added mail API endpoints for account setup, connection testing, sync, send, drafts, read state, message detail, deletion, and attachment download.
- Added `mail.read` and `mail.write` token permissions.
- Added offline developer docs, an interactive API explorer, syntax-highlighted examples, searchable navigation, and an OpenAPI document.
- Added a step-by-step email setup guide for app accounts, common providers, VPS mail servers, and troubleshooting.

## Phase 4
- Added ad network dashboard, campaigns, sites, placements, geography, experiments, payouts, conversions, and SMTP summary settings.
- Added ad server documentation and offline dashboard assets.

## Phase 3
- Added HTTPS/pairing, WebDAV, sharing, tokens, scheduled tasks, webhooks, and security center.

## Phase 2
- Added projects, databases, files, backups, CORS, rate limiting, path safety, and the `/api/v1` JSON API.
