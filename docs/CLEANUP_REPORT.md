# Repository Cleanup Report

Date: 2026-11-26
Baseline: `7ef60f8 Add startup diagnostic checkpoints and investigation reports`
Repository: `/tmp/Phoneserver`

## Goal

Organize the repository for review and handoff without changing application behavior, stored formats, endpoints, test coverage, or user data.

## Changes applied

### Generated artifacts

- Removed ignored build and IDE cache trees:
  - `app/build/`
  - `build/`
  - `.gradle/`
  - `.acside/`
- Build outputs were regenerated during validation and remain ignored by Git.
- Pruned Git object garbage and stale temporary pack files. Final object count: `0` loose objects, `0` garbage, one pack.

### Report organization

- Moved from repository root into `docs/development/`:
  - `BUG_REPORT.md`
  - `FIX_REPORT.md`
  - `TEST_REPORT.md`
- Linked all three reports from `README.md` and `docs/README.md`.

### Documentation organization

- Added a complete top-level repository layout section to `README.md`.
- Expanded the `README.md` documentation map with remote access, Phase 8 backup/import/export, remote API, Phase 9 authorization matrix, investigation reports, and this cleanup report.
- Added the Phase 9 authorization matrix and investigation reports to `docs/README.md`.
- Created this report and linked it from `README.md` and `docs/README.md`.

### Stale references corrected

- Updated test counts from `314/170` and `157/50` to the current `315 tests, 0 failures, 0 errors, 171 skipped` in:
  - `README.md`
  - `index.html`
  - `app/src/main/assets/docs/README.md`
- Replaced the stale single-commit baseline note in `index.html` with a current snapshot description.
- Fixed the OpenAPI link in `docs/features/remote-access.md` to point to `../api/openapi.json`.
- Replaced nonexistent root-document references:
  - `ARCHITECTURE.md` → `docs/architecture/overview.md`
  - `SECURITY_AUDIT.md` → `docs/security/audit.md`
- Applied the same architecture/security reference corrections to both generated documentation bundles:
  - `web/data/docs.js`
  - `app/src/main/assets/docs/docs.js`

## Preserved intentionally

- AndroidIDE local-only files were not touched:
  - `/storage/emulated/0/AndroidIDEProjects/phoneserver/gradle.properties`
  - `/storage/emulated/0/AndroidIDEProjects/phoneserver/web/data/file_00000000f8fc81fa98d2fdd19a53f3b7.png`
- `app/debug.keystore`, `libllama_bridge.so`, all assets, resources, source packages, endpoints, and user-data formats are unchanged.
- `banner.png` and the hashed web banner have identical SHA-256 values but serve different consumers; both were retained.
- `androidx.viewpager2:viewpager2:1.1.0` was retained. Although source no longer uses `ViewPager2` directly, `com.google.android.material:material:1.9.0` has a transitive ViewPager2 dependency and the offline cache does not contain the requested `1.0.0` artifact.
- Temporary `StartupTrace` instrumentation was retained because the startup process-death investigation is still active.

## Validation

- Command:
  - `ANDROID_HOME=/tmp/android-sdk sh gradlew compileDebugJavaWithJavac testDebugUnitTest assembleDebug --offline`
- Result: `BUILD SUCCESSFUL`
- Unit suite: `315 tests, 0 failures, 0 errors, 171 skipped`
- `git diff --check`: passed.
- Relative-link scan over `README.md` and all `docs/**/*.md`: 24 files, 0 broken links. The flattened in-app asset bundle was excluded because it intentionally resolves links through `docs.js`.
- Rebuilt APK SHA-256: `05f954cd52f52a72781df879fd254e8bf3d1f1979a4d2c3bd955f6ca0cb5f598`
- Targeted sweeps found no remaining stale `314 tests`, `170 skipped`, `157 tests`, `50 skipped`, `ARCHITECTURE.md`, `SECURITY_AUDIT.md`, or `d9315c9` references.

## Not verified

- No live device runtime verification was performed during cleanup.
- The startup process-death root cause remains unresolved; the latest installed runtime trace stopped at `MainActivity.onCreate:listenersStart`.
- `lintDebug` remains blocked by uncached `androidx.core:core-ktx:1.2.0` and `androidx.arch.core:core-runtime:2.0.0` artifacts.
- No commit or push was created for this cleanup because none was requested.
