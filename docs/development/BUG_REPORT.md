# BUG_REPORT.md

## Scope
- App: PhoneServer
- Package: `com.yadaventerprise.phoneserver`
- Build: AndroidIDE, Gradle wrapper 9.0.0, AGP 8.5.2, compileSdk 34, minSdk 24, targetSdk 34.
- Symptom: installed app starts, writes startup trace, then the process is gone within about one second. No Java stack trace is available.

## Last confirmed checkpoint
Trace from installed APK SHA-256 `7095bcf898c6fc3a11dc9906329bebb09951933acbcf331006c59785a79d38c9`:

```text
1795625766832 MainActivity.onCreate:start
1795625766992 MainActivity.onCreate:contentView
1795625766995 MainActivity.onCreate:views
1795625766999 MainActivity.onCreate:restoreStart
1795625767004 MainActivity.onCreate:restoreDone
1795625767007 MainActivity.onCreate:listenersStart
```

`MainActivity.onCreate:listenersStart` is the last confirmed checkpoint. The next statement is `bottomNav.setOnItemSelectedListener(...)` at `app/src/main/java/com/yadaventerprise/phoneserver/ui/main/MainActivity.java:68`; after that come the drawer listener, `showPage(restoredPage)`, fragment creation, fragment transaction, `onStart`, and `onResume`.

## Triage hypotheses

### H1 — Process dies in/after `BottomNavigationView.setOnItemSelectedListener(...)`
- Evidence: trace stops immediately before `MainActivity.java:68` `bottomNav.setOnItemSelectedListener(item -> {`.
- Against: the previous ViewPager2 build executed the same bottom-nav listener and reached its `bottomListener` checkpoint.
- Confirm or kill with: trace from APK SHA-256 `bb89eb31e1397c32c6ae4fc0ff1eab4ea5ac4c663d5ce612b8a7c0e0338301a7`; look for `MainActivity.onCreate:bottomListenerSet` and `MainActivity.onCreate:drawerListenerStart`.

### H2 — Process dies during restored-page fragment creation/transaction
- Evidence: `MainActivity.java:99` `showPage(restoredPage);` runs after listener setup, then `MainActivity.java:112` `Fragment fragment = MainPagerAdapter.createFragment(page);` and `MainActivity.java:141` `transaction.commit();`.
- Against: not reached in the last installed build, so there is no direct evidence yet.
- Confirm or kill with: trace from APK `bb89eb31...`; look for `MainActivity.showPage:start:0`, `createStart`, `createDone`, `transactionStart`, and `transactionCommitted`.

### H3 — Fixed-time external process kill rather than a Java crash
- Evidence: as instrumentation changed, the last checkpoint moved from `postScheduled` to `views` to `listenersStart`; no `AndroidRuntime`/`FATAL EXCEPTION` was observed in accessible logcat buffers.
- Against: tombstone and crash-buffer access are denied on this host, so absence of a stack is not proof.
- Confirm or kill with: on-device bug report, tombstone, or `logcat -b events` showing `am_kill`/`am_proc_died` for `com.yadaventerprise.phoneserver`.

## Audit findings

Rules applied: only files actually read in this session are audited. Anything without direct evidence is marked UNVERIFIED.

| ID | Severity | file:line (quoted exactly) | Root cause | How to reproduce | Fix |
|---|---|---|---|---|---|
| BUG-01 | High | `app/src/main/java/com/yadaventerprise/phoneserver/ui/main/MainActivity.java:67` — `StartupTrace.write(this, "MainActivity.onCreate:listenersStart");` | **UNVERIFIED.** Process death occurs at or after this checkpoint. No Java exception is available. Next code is `bottomNav.setOnItemSelectedListener(...)` at line 68. | Install APK SHA-256 `7095bcf898c6fc3a11dc9906329bebb09951933acbcf331006c59785a79d38c9`, launch, read `/storage/emulated/0/Download/phoneserver-startup-trace.log`; it ends at `listenersStart` and `pidof com.yadaventerprise.phoneserver` is empty after 5 seconds. | No root-cause fix applied yet. New instrumentation in APK `bb89eb31...` adds `bottomListenerSet` and `drawerListenerStart` checkpoints to localize the next statement. Do not guess beyond this. |
| BUG-02 | Medium | `app/src/main/java/com/yadaventerprise/phoneserver/ui/main/MainActivity.java:99` — `showPage(restoredPage);` | **UNVERIFIED.** Fragment creation/transaction is the next lifecycle-heavy path after listener setup; it may be where the process dies once earlier checkpoints are passed. | Use APK `bb89eb31...` and observe whether trace reaches `MainActivity.showPage:start:0`, `createStart`, `createDone`, `transactionStart`, and `transactionCommitted`. | Pending trace evidence. Do not change fragment code until the new trace confirms or kills this. |
| BUG-03 | Low | `app/src/main/java/com/yadaventerprise/phoneserver/util/StartupTrace.java:26` — `FileOutputStream out = new FileOutputStream(trace, true);` | Diagnostic class performs synchronous external-storage writes on the main thread. It is not proven causal, but it is temporary instrumentation and must not remain in a release build. | Any app launch writes to `/storage/emulated/0/Download/phoneserver-startup-trace.log`; trace lines are observed. | Remove all `StartupTrace` calls and delete `StartupTrace.java` after the root cause is confirmed. |

## Not audited in this pass
- Native/JNI loading: no native crash evidence and no JNI file was re-read with line numbers here.
- Security/path traversal/auth/exported components: no new evidence in this pass; earlier Phase 9 audit docs were changed but are not cited here.
- Manifest and permissions: not re-read with line numbers in this pass.
- Resource leaks: only `MainActivity`, `MainPagerAdapter`, `StartupTrace`, `activity_main.xml`, `build.gradle`, and `app/build.gradle` were read with line numbers.
