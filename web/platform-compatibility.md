# Platform compatibility & setup

This project targets **Android** (the app) and is built with the existing
Gradle/AGP setup. The storage layer itself is plain Java + Android SQLite and
runs on the device.

## Device / runtime requirements

- Android API 24+ (unchanged from the existing project).
- A network interface the clients can reach; the server binds port **8080**
  (`ServerService.PORT`).
- Full storage access (`MANAGE_EXTERNAL_STORAGE` / all-files access) is only
  needed for the pre-existing shared-storage browsing features. The new data
  platform defaults to an **app-private** data root and does not require it.

## Build environment

- JDK 21, Gradle wrapper 9.0.0, Android Gradle Plugin 8.5.2.
- `local.properties` must point at an Android SDK
  (`sdk.dir=/path/to/android-sdk`). It is git-ignored and intentionally **not**
  part of the update archive.
- Build commands:

  ```bash
  sh gradlew :app:assembleDebug
  sh gradlew :app:testDebugUnitTest
  ```

## Dependencies added

Only test dependencies were added to `app/build.gradle`:

```groovy
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.robolectric:robolectric:4.14.1'
```

No new runtime (non-test) dependencies were introduced: the platform uses only
the Android framework, `org.json` (already used by the project) and
`java.util.zip` (JDK). This satisfies the "avoid unnecessary dependencies"
requirement — SQLite is the device's built-in SQLite; no external database
server is needed.

## Host architecture note (tests)

Robolectric runs Android code on the host JVM. Its SQLite backend requires a
**native** library and ships binaries only for:

| OS | Architectures |
|---|---|
| Linux | x86_64 |
| Windows | x86_64 |
| macOS | x86_64, arm64 (Apple Silicon) |

There is **no Linux/aarch64** build of Robolectric's native runtime (verified
against `nativeruntime-dist-compat` 1.0.16 … 1.0.19) nor of its legacy
`sqlite4java` fallback. On a Linux/aarch64 host JVM (for example an
`aarch64` Linux container or an on-device JDK), Robolectric cannot open a
database and the DB-backed tests are skipped by
`DataTestSupport.assumeRobolectricRuntimeSupported()`.

Consequences:

- **Pure-JVM tests** (path/SQL/MIME/token/rate-limit/storage-layout/backup
  archive) run on any host, including Linux/aarch64.
- **Robolectric tests** run on x86_64 Linux/Windows and on x86_64/arm64 macOS
  (the usual developer/CI machines). They are skipped on Linux/aarch64 with an
  explicit reason rather than failing.
- The **app itself** is unaffected: on a real Android device the platform uses
  the device's own SQLite.

The `--warning-mode all` Gradle hint and the "Deprecated Gradle features" notice
come from the existing build setup (Gradle 9 deprecations) and are unrelated to
this change.
