# Testing

## How to run

From the project root:

```bash
# Unit tests
sh gradlew :app:testDebugUnitTest

# Debug build
sh gradlew :app:assembleDebug
```

HTML reports are written to `app/build/reports/tests/testDebugUnitTest/`.

## Frameworks

- **JUnit 4** (`junit:junit:4.13.2`) for all tests.
- **Robolectric 4.14.1** (plus `testOptions.unitTests.includeAndroidResources`)
  for stores that need Android APIs (`Context`, `SQLiteDatabase`). No
  instrumentation tests are used, so no emulator/device is required for the
  parts that can run.

Tests live under `app/src/test/java/…`.

## Test suites

| Suite | Kind | Covers |
|---|---|---|
| `PathGuardTest` | plain JVM | normalisation, traversal rejection, filename sanitising, root containment |
| `SqlGuardTest` | plain JVM | single-statement rule, forbidden keywords, read/write verdicts, quote/comment-aware splitting |
| `MimeTypesTest` | plain JVM | extension → MIME mapping, categories, inline-safe allow-list |
| `TokenUtilTest` | plain JVM | token prefix, hash stability, constant-time compare |
| `RateLimiterTest` | plain JVM | windowed allow/deny |
| `StorageLayoutTest` | plain JVM | directory/file paths, id validation, temp files |
| `BackupArchiveTest` | plain JVM | archive create/validate/extract, unsafe-entry rejection, size cap |
| `ProjectStoreTest` | Robolectric | create/list/update/delete, duplicate names, restart persistence, per-project & global stats, delete confirmation |
| `DatabaseManagerTest` | Robolectric | create/list, CRUD, parameterised queries, transactions/rollback, schema inspection, invalid SQL, export/import |
| `FileStoreTest` | Robolectric | upload/download metadata + checksum, rename/move/delete, folders, traversal rejection, quota & size limits, missing/orphan detection |
| `BackupManagerTest` | Robolectric | create/validate/restore, invalid archives, preserve-existing-data |
| `TokenStoreTest` | Robolectric | create/verify/revoke, scoping, name validation, listing |
| `DataApiTest` | Robolectric | end-to-end `DataApi.handle`: auth, project scoping, CORS preflight, `413`, `429`, `404`, `405` |

`DataTestSupport` provides a per-test data root and resets the process-wide
`SystemDatabase` singleton between tests (via reflection), so tests cannot leak
state into one another.

## Results

On the development host used for this implementation the suite reports:

```
88 tests, 41 passed, 47 skipped, 0 failed
```

The 41 pure-JVM tests run everywhere. The 47 Robolectric tests are **skipped on
this specific host** because it is a **Linux/aarch64** environment: Robolectric
loads SQLite through a native runtime (`DefaultNativeRuntimeLoader`) and its
legacy `sqlite4java` fallback, and neither ships Linux/aarch64 binaries (they
support x86_64 Linux/Windows and x86_64/arm64 macOS). On such a host Robolectric
cannot open a database.

Rather than hide this, the Robolectric suites call
`DataTestSupport.assumeRobolectricRuntimeSupported()` from `@Before`, which
skips the test with a clear reason on unsupported platforms (JUnit
`AssumptionViolatedException`). On a supported host (x86_64 Linux/Windows or
x86_64/arm64 macOS) they run in full. See
[platform-compatibility.md](platform-compatibility.md).

To confirm the system-under-test is not the cause, note that the same failure
occurs before any of this project's code runs — the very first database open in
Robolectric's own bootstrap triggers the native-runtime error.

## Relationship to the specification

The suites cover the testing requirements from the feature specification:

- **Database** — initialisation, project-specific DB creation, table creation,
  parameterised queries, CRUD, transactions/rollback, schema inspection, invalid
  SQL and errors, restart persistence, migrations (schema is versioned with
  additive upgrade steps).
- **Projects** — create/list/update/delete, cross-project isolation, duplicate
  names/ids, confirmation requirement.
- **Files** — upload/download and metadata (name/MIME/size/checksum), images and
  documents, rename/move/delete, path traversal, missing files, size and quota
  limits.
- **Backups** — creation, validation, restore, invalid/incomplete archives,
  preservation of existing data.
- **API/Security** — unauthorized requests, cross-project access, invalid input,
  oversized uploads (`413`), rate limiting (`429`), `404`/`405`, SQL-injection
  attempts (via `SqlGuard` and parameter binding).

## Lint / static analysis

`sh gradlew :app:lintDebug` can be run for Android Lint. It is not part of the
default `assembleDebug`/`test` path and was not required to build the debug
APK.

## Ad network coverage

There are **no automated tests for the ad-network classes or the dashboard assets** (and no CI). The 88-test suite covers the data/storage platform only. Manual verification is required:

1. Enable **Settings → Ad Network**, start the server, open `http://<phone-ip>:8090/` and complete setup with the phone-displayed code.
2. Create a site with a real hostname, a placement, a campaign and an approved creative; fund the advertiser and approve the campaign.
3. Serve a page from that hostname with the generated tag (or call `/serve` with matching `Origin`/`Referer`), then confirm `/serve` returns JSON and `/imp` returns `204`.
4. Click through `/c` and confirm the redirect plus a click row in reports; replay a token and confirm it is recorded as invalid and not billed.
5. Confirm no fill (no eligible campaign) returns `204`; confirm a wrong domain returns `403`; confirm the dashboard works with JavaScript on and shows the `<noscript>` panel with it off.

The ad dashboard is CSP-sensitive: static checks should confirm there are no inline `<script>`, `style=` attributes or `on*=` handlers in `assets/adnet/*`.
