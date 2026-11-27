# Phase 11 report — production readiness and reliability

Status: **source-verified and host-test-verified; on-device runtime verification remains blocked**.

Phase 11 was implemented in `/tmp/Phoneserver` on top of `0c66fd1`. This report records only evidence available in the repository and this host. It does not claim live on-device verification where none was possible.

## Implemented work

- Added real request-outcome instrumentation to `ServerStats` and `SimpleHttpServer`:
  - total requests, error responses, 4xx responses, 5xx responses, blocked requests;
  - average and maximum request latency;
  - counters are reset with existing local stats and exposed through `/system/reliability`.
- Added network usage sampling from `TrafficStats`, with an explicit unsupported state instead of invented values.
- Added reliability aggregation:
  - health score computed from listener state, storage, memory, error rate and blocked requests;
  - explicit warning list and `healthy`/`degraded`/`critical`/`offline` state;
  - app-process CPU remains labelled as app CPU; no device-wide CPU percentage is fabricated.
- Added crash/startup diagnostics:
  - `PhoneServerApplication` installs a process-wide uncaught-exception handler;
  - redacted crash and startup records are stored in a bounded app-private JSON-lines store;
  - `/system/crashes` exposes redacted records under `system.read`.
- Added a full diagnostics report:
  - `/system/diagnostics/report` combines reliability status, maintenance/backup state, unified logs, security events and crash diagnostics;
  - secrets, authorization headers, emails, query secrets and IPv4 addresses are redacted or masked before export.
- Hardened backup/integrity behaviour:
  - preview no longer mutates the stored backup validity flag;
  - validation catches non-`IOException` failures while preserving user-correctable password errors;
  - delete removes `.psbak`, `.zip.enc` and `.zip` archive variants;
  - added pure checksum verification and restore-confirmation guard helpers.
- Hardened security monitoring:
  - remote login failures now feed the existing durable `SecurityEventStore` and intrusion detector;
  - auth-failure and suspicious-activity alert switches and rate limits are configurable in Security Center;
  - alerts are marked on stored events and webhook emission respects policy;
  - `/system/security-events` exposes redacted durable events.
- Added a Production Readiness card to the Admin Dashboard with live health, request/error, network, security-event and warning status.
- Fixed Phase 9/10 blockers discovered during assessment:
  - `AiActionCatalog.requiredPermission(String)` no longer recurses for write actions;
  - `RemoteApiAuthTest` now reads the flat `token` field returned by the remote login API.

## New API routes

All routes require `system.read` and preserve the existing `/api/v1` auth boundary.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/system/reliability` | Live health score, request outcomes, resources and network state |
| `GET` | `/api/v1/system/diagnostics/report` | Redacted combined reliability report |
| `GET` | `/api/v1/system/crashes` | Bounded redacted crash/startup records |
| `GET` | `/api/v1/system/security-events` | Redacted durable security event history |

`GET /api/v1/system/diagnostics` keeps its original response shape; Phase 11 data is available under `/system/diagnostics/report`.

## Verification evidence

Command:

```sh
ANDROID_HOME=/tmp/android-sdk sh gradlew --no-daemon compileDebugJavaWithJavac testDebugUnitTest assembleDebug --offline
```

Result: `BUILD SUCCESSFUL`.

JUnit XML totals from `app/build/test-results/testDebugUnitTest`:

- `336` tests
- `0` failures
- `0` errors
- `171` skipped by `DataTestSupport` because Robolectric SQLite/HTTP execution is unsupported on this Linux `aarch64` host
- `165` tests actually executed

New host-executable tests cover:

- health/error-rate calculation and warnings;
- diagnostic redaction of credentials, query secrets, emails and IP addresses;
- JSON record validation;
- backup checksum verification and missing/mismatch handling;
- restore guard decisions;
- security alert policy thresholds and rate limits;
- real `ServerStats` outcome/latency counters;
- the Phase 10 `AiActionCatalog` permission fix.

Built APK:

```text
app/build/outputs/apk/debug/app-debug.apk
sha256 a72d67b14f4c9aa63ede0a9657426611f0d719884c0c4866624a1b101481ee03
```

`git diff --check` passed for the Phase 11 working tree.

## Known limits

- The on-device launch process-death issue remains unresolved. The new crash handler records events only if execution reaches `Application.onCreate`; a process death before that point is still outside app-level instrumentation.
- `lintDebug` is blocked offline by missing cached AndroidX artifacts (`core-ktx:1.2.0`, `core-runtime:2.0.0`).
- CPU remains app-process CPU only because device-wide CPU usage is not available to this app.
- Network usage is device-total traffic from `TrafficStats`, not per-request bytes; per-request bytes remain in the existing stats system.
- Phase 9 and Phase 10 are source-verified and compile-tested here, but not fully runtime-verified on a device.

## Git state

Phase 11 changes are committed on top of `0c66fd1`; see the repository history for the Phase 11 commit hash and pushed branch state.
