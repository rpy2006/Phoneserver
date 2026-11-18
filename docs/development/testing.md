# Testing PhoneServer

## Navigation

- [Documentation index](../README.md)
- [Architecture overview](../architecture/overview.md)
- [Security audit](../security/audit.md)
- [Changelog](../changelog.md)

## Commands

```sh
ANDROID_HOME=/tmp/android-sdk sh gradlew compileDebugJavaWithJavac
ANDROID_HOME=/tmp/android-sdk sh gradlew testDebugUnitTest
ANDROID_HOME=/tmp/android-sdk sh gradlew assembleDebug
```

Use `--offline` when the dependency cache is already populated.

## Test layout

- `data/storage/` — backups, files, projects and storage settings.
- `data/security/` — intrusion detection, token policy, scanner and schedules.
- `data/webhooks/` — webhook config round-trip and signed delivery to a
  loopback HTTP server.
- `data/tasks/` — scheduled task JSON round-trip and interval validation.
- `data/sharing/` — token shape/hashing, PBKDF2 verification, link state,
  store lifecycle, download consumption and access-log queries.
- `ads/` — encrypted ad JSON persistence and rollback, delete filters, geo
  CSV/CIDR lookup, conversion attribution/dedupe and experiment/payout
  ownership.
- `network/http/` — request parsing, encoding and HTTP security regression
  tests.
- `network/tls/` — self-signed certificate generation, fingerprinting and
  signature verification.
- `security/`, `util/`, `testing/` — guards, MIME helpers and shared
  Robolectric support.

## Host notes

Robolectric tests use `@Config(sdk = 34)` and, where TLS providers are loaded,
`@ConscryptMode(OFF)`. On aarch64 hosts the Conscrypt native library is often
unavailable, so some Robolectric tests are skipped by design; the rest still
run. `DataTestSupport.assumeRobolectricRuntimeSupported()` performs the same
guard for storage tests.

Latest full run after Phase 5: `157 tests, 0 failures, 0 errors, 50 skipped`.
The sharing, ad-network storage, mail-core, docs-assets and token-policy tests
run in this suite. Some Robolectric tests skip on this aarch64 host by design.
