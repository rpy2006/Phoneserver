# TEST_REPORT.md

## Build/test evidence actually run

| Item | Result |
|---|---|
| `ANDROID_HOME=/tmp/android-sdk sh gradlew compileDebugJavaWithJavac testDebugUnitTest assembleDebug --offline` in `/storage/emulated/0/AndroidIDEProjects/phoneserver` | **BUILD SUCCESSFUL** in 2m 51s on the current source state. |
| JUnit XML aggregation from `app/build/test-results/testDebugUnitTest` | `tests=315 failures=0 errors=0 skipped=171`. |
| `git diff --check` in `/tmp/Phoneserver` | Passed with no output. |
| Current debug APK | `/storage/emulated/0/AndroidIDEProjects/phoneserver/app/build/outputs/apk/debug/app-debug.apk` |
| Current APK SHA-256 | `bb89eb31e1397c32c6ae4fc0ff1eab4ea5ac4c663d5ce612b8a7c0e0338301a7` |
| `lintDebug` | **NOT RUN** in this pass. Earlier offline attempts were blocked by missing cached `androidx.core:core-ktx:1.2.0` and `androidx.arch.core:core-runtime:2.0.0`. |
| On-device runtime fix | **NOT VERIFIED**. The newest APK `bb89eb31...` has not been installed. |

## On-device evidence already observed

Installed APK SHA-256 `7095bcf898c6fc3a11dc9906329bebb09951933acbcf331006c59785a79d38c9` matched the local build at that time. Launching through the existing helper returned `result=0`, but after 5 seconds `pidof com.yadaventerprise.phoneserver` was empty.

Trace file `/storage/emulated/0/Download/phoneserver-startup-trace.log` contained exactly six lines:

```text
1795625766832 MainActivity.onCreate:start
1795625766992 MainActivity.onCreate:contentView
1795625766995 MainActivity.onCreate:views
1795625766999 MainActivity.onCreate:restoreStart
1795625767004 MainActivity.onCreate:restoreDone
1795625767007 MainActivity.onCreate:listenersStart
```

Verdict for APK `7095bcf8...`: **FAIL**. The app process died before the listener/fragment checkpoints that are now present in APK `bb89eb31...`.

## Exact on-device verification steps for the newest APK

1. Install this exact file over the existing app:
   - `/storage/emulated/0/AndroidIDEProjects/phoneserver/app/build/outputs/apk/debug/app-debug.apk`
   - Expected SHA-256: `bb89eb31e1397c32c6ae4fc0ff1eab4ea5ac4c663d5ce612b8a7c0e0338301a7`
2. Delete the old trace:
   - `/storage/emulated/0/Download/phoneserver-startup-trace.log`
3. Launch PhoneServer from the launcher.
4. Wait 5 seconds.
5. Send the full contents of `/storage/emulated/0/Download/phoneserver-startup-trace.log`.

### Pass criteria

All of these must be true:

- Trace contains `MainActivity.onCreate:bottomListenerSet`.
- Trace contains `MainActivity.onCreate:drawerListenerStart`.
- Trace contains `MainActivity.onCreate:drawerListenerSet`.
- Trace contains `MainActivity.showPage:start:0`.
- Trace contains `MainActivity.showPage:createStart:0`.
- Trace contains `MainActivity.showPage:createDone:0`.
- Trace contains `MainActivity.showPage:transactionCommitted:0`.
- Trace contains `MainActivity.showPage:done:0`.
- Trace contains `MainActivity.onStart` and `MainActivity.onResume`.
- App remains in foreground after 5 seconds.

### Fail interpretation

- Stops at `listenersStart`, missing `bottomListenerSet`: death is in/around `BottomNavigationView.setOnItemSelectedListener`.
- Has `bottomListenerSet` but missing `drawerListenerStart`: death is between the two listener calls.
- Has `drawerListenerSet` but missing `showPage:start:0`: death is in restored-page computation or the `showPage` call.
- Has `showPage:start:0` but missing `createDone:0`: death is in fragment construction.
- Has `createDone:0` but missing `transactionCommitted:0`: death is in `FragmentTransaction.add`/`commit`.
- Reaches `onResume` but the app still closes: the failure is after startup and needs a different trace window.

## Verification status summary

- Compile: **VERIFIED**.
- Unit tests: **VERIFIED** as `315 tests, 0 failures, 0 errors, 171 skipped`.
- Static diff check: **VERIFIED**.
- Runtime crash fix: **NOT VERIFIED**.
- Temporary trace writer: **VERIFIED** only as a diagnostic; it must be removed later.
