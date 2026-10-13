# Spck Editor Reconstruction — Final Phase 9 Package

This is the final planned reconstruction package, based on resources recovered from the supplied APK. It is **not the original upstream source repository and is not a verified full clone**. The final phase consolidates the source, adds reproducible build bootstrapping and CI configuration, and reruns the available offline regression suite.

## Phase 9 finalization

- Version metadata advanced to `0.9.0-reconstruction-final` / versionCode 9.
- Added checksum-pinned Gradle 8.9 bootstrap launchers for Linux/macOS (`./gradlew`) and Windows (`gradlew.bat`). These scripts download the official Gradle binary distribution and verify its SHA-256 before use. Because this environment had neither Gradle nor network access, the **official Gradle Wrapper JAR could not be generated**; these launchers are clearly documented custom bootstrappers, not the official Wrapper implementation.
- Added `.github/workflows/android-build.yml` to run the regression suite, set up JDK 17 and Android API 35, build the debug APK, and upload it as a workflow artifact when run in a GitHub Actions environment. The workflow has been authored but was not executed from this offline session.
- Added `.gitignore`, a per-file SHA-256 package manifest, final package/report validation, and final reconstruction status documentation.

## Phase 8 additions

- Added crash-tolerant persistent workspace metadata outside user project folders, with a last-used tool project and a bounded 12-item most-recently-used list.
- Validated workspace names against canonical in-root project paths; atomically persisted metadata and prune stale/missing projects.
- Remembered the selected project in the ZIP/Git/restricted-console tools and the Tools panel open/closed state.
- Added WebView pause/resume lifecycle handling, trusted editor URL checks before state restoration, bounded renderer-crash recovery, and consolidated WebView/native bridge disposal.
- Added graceful reporting when ZIP work is requested as the operation executor is shutting down.
- Version metadata advanced to `0.8.0-reconstruction` / versionCode 8.
- See `PHASE_8_IMPLEMENTATION_REPORT.md` for details and regression results from that phase.

## Phase 7 additions

- Added a local Git workflow using standard SHA-1 loose objects and Git index v2: initialize, status, stage all, commit, recent log, list/create branches, and safe branch switching.
- Added a restricted in-project console with `help`, `pwd`, `ls`, `cat`, `head`, `find`, `mkdir`, `touch`, `cp`, `mv`, single-file `rm`, and `clear`. It rejects shell operators and never launches an arbitrary shell/process.
- Wired both tools into the native WebView bridge and Project Tools overlay. ZIP, Git, and console operations share one serial background executor to avoid overlapping worktree/index mutations.
- Added local Git interoperability tests against the host machine's Git implementation and project-console path/command safety tests.
- Version metadata advanced to `0.7.0-reconstruction` / versionCode 7.
- See `PHASE_7_IMPLEMENTATION_REPORT.md` for scope, test outcomes, limits, and unfinished features.

## Phase 6 additions

- Added Import ZIP and Export project ZIP actions using Android's Storage Access Framework without broad external-storage permissions.
- ZIP imports are staged before promotion and enforce archive path/name/size limits; exports stream to the selected document.
- See `PHASE_6_IMPLEMENTATION_REPORT.md` for details.

## Existing reconstruction features

- Android Studio scaffold using Java, Android WebView, Android Gradle Plugin 8.7.3, compileSdk 35, minSdk 23, targetSdk 28, and JDK 17.
- Bundled web-app assets recovered from the supplied APK: Ace language modes/snippets, workers, fonts, templates, CSS, Git UI bundle, terminal emulator bundle, and vendor resources.
- Native JavaScript filesystem bridge for app-private project files: directory creation/listing, read/write/delete/copy/move, stats, deep listing, search-in-file, Base64 share/download, clipboard, Git object hashing, and restricted local Git/console operations.
- Virtual filesystem root exposed as `/documents/`. Bridged paths are normalized and constrained to the app-private `files/spck-projects` directory. Individual files are capped at 64 MiB.
- Minimal loopback-only static HTTP server for local HTML/CSS/JS preview, bound to `127.0.0.1`; default cleartext HTTP remains disabled outside the localhost exception.
- Isolated project preview screen in a separate WebView with no native JavaScript bridge, restricted file/content URI access, and origin-constrained top-level navigation.
- HTML file-input picker handling, WebView console logging in debug builds, navigation restrictions, SSL-error cancellation, export/share integration, and Android lifecycle cleanup.

## Open and run

1. Use Android Studio with JDK 17 and Android SDK Platform 35, or use a terminal with JDK 17, `curl` (or `wget`), `unzip`, and a network connection for the first Gradle bootstrap.
2. Open this directory (`SpckEditor-Reconstruction`) as an existing Gradle project.
3. On Linux/macOS, run `./gradlew assembleDebug`; on Windows, run `gradlew.bat assembleDebug`. The first call downloads Gradle 8.9 and validates the SHA-256 hash.
4. Or open the project in Android Studio and let it sync dependencies.
5. Install and run the generated debug APK on a device/emulator, then complete the device checklist in `PHASE_9_FINAL_REPORT.md`.

The included `gradlew` / `gradlew.bat` files are checksum-pinned custom bootstrap launchers, **not** Gradle's official wrapper JAR implementation. They can download Gradle 8.9 on a network-connected computer and verify the distribution SHA-256 before executing it. The execution environment used to prepare this package has no system Gradle or Android SDK, and network DNS was unavailable, so a successful Android APK build is **not claimed**. In a connected development environment, maintainers can generate the official wrapper with `gradle wrapper --gradle-version 8.9 --distribution-type bin` and commit all four standard wrapper files after verifying them. The checked-in CI workflow uses the supplied custom launcher unless replaced with the official wrapper.

## Run the archive regression tests

With a JDK 17+ and Python 3 available, run:

```sh
sh tools/test_archive_io.sh
```

The script compiles and runs the pure-Java archive, local Git, and restricted-console tests, then runs the offline static project verifier. The Git tests use the host Git binary as an interoperability oracle; the app itself does not spawn it. These tests do not build the Android Activity or validate SAF/WebView behavior on a device.

## Current limitations

- Imported ZIP files are placed under `/documents/<archive-name-derived-folder>`. The UI prompts the user to refresh/reopen the workspace to discover the new project, but the recovered frontend's end-to-end project discovery still needs to be tested on Android.
- The reconstruction's project preview opens in a separate screen rather than the original embedded preview pane. This is an intentional security trade-off until a secure origin-restricted messaging architecture can be verified.
- Local Git is intentionally partial: `.gitignore` matching, remote Git operations and credentials, merge/rebase/reset/stash/tag, symlink/executable-mode preservation, submodules, Git LFS, signed commits, and non-v2 index extensions are not implemented. Its “Stage all” behavior does not honor `.gitignore` yet.
- The Project Console is a restricted set of safe built-ins, not a real shell. It does not run arbitrary processes, Node/Python, compilers, package managers, or general terminal commands. The recovered xterm bundles are still not connected to a real process backend.
- Still unimplemented or unverified: account/auth services, purchases/billing, QR scanning, browser-proxy mode, cloud services, broad external-folder integration, and original preview-server semantics.
- The JavaScript/CSS/fonts and other production resources are copied compiled/minified output. This does not reconstruct the original source modules, comments, source maps, backend services, secrets, or CI/deployment setup.
- Runtime parity needs side-by-side comparison with the original installed application, including project create/open/edit/save, ZIP import/export, preview, local Git behavior, restricted console behavior, settings, orientation changes, file picker, and error flows.

**Security note:** project HTML and JavaScript are executable code. The preview uses a separate WebView without the native filesystem bridge, but project code can still make network requests and should be treated as untrusted.

Before redistributing a derivative, verify your rights to reuse the bundled code, branding, styles, fonts, and assets. This project is provided as a personal engineering reference.

## Key files

- `PHASE_9_FINAL_REPORT.md` — final contents, validation results, build status, known gaps, and device release checklist.
- `PHASE_8_IMPLEMENTATION_REPORT.md` — workspace metadata, lifecycle recovery, and regression coverage.
- `PHASE_7_IMPLEMENTATION_REPORT.md` — local Git and restricted console implementation, tests, safety controls, and limitations.
- `PHASE_6_IMPLEMENTATION_REPORT.md` — archive import/export, safeguards, test scope, and limitations.
- `PHASE_5_IMPLEMENTATION_REPORT.md` — preview isolation changes and trade-offs.
- `PHASE_4_IMPLEMENTATION_REPORT.md` — share/download integration.
- `PHASE_3_IMPLEMENTATION_REPORT.md` — filesystem bridge and loopback preview baseline.
- `APK_INSPECTION_REPORT.md` — APK inspection findings.
- `app/src/main/java/com/reconstruction/spck/MainActivity.java` — editor WebView host, SAF pickers, and ZIP tools UI.
- `app/src/main/java/com/reconstruction/spck/NativeFileBridge.java` — filesystem and native utility bridge.
- `app/src/main/java/com/reconstruction/spck/ProjectArchiveIO.java` — bounded ZIP import/export implementation.
- `app/src/main/java/com/reconstruction/spck/LocalGitEngine.java` — pure-Java local Git implementation.
- `app/src/main/java/com/reconstruction/spck/ProjectCommandRunner.java` — safe allowlisted project console.
- `app/src/main/java/com/reconstruction/spck/WorkspaceStateStore.java` — atomic, validated persistence for tool workspace selection and recents.
- `tools/LocalGitEngineTest.java` and `tools/ProjectCommandRunnerTest.java` — Phase 7 regression tests.
- `tools/test_reconstruction.sh` — complete offline validation script.
- `app/src/main/java/com/reconstruction/spck/ArchiveSafety.java` — archive path and project-name policy.
- `app/src/main/java/com/reconstruction/spck/PreviewActivity.java` — isolated project preview WebView.
- `app/src/main/java/com/reconstruction/spck/LoopbackPreviewServer.java` — localhost-only static preview server.
- `tools/test_archive_io.sh` — run pure-JVM archive tests and static checks.
- `tools/verify_reconstruction.py` — repeatable static project validation, including package-manifest verification.
- `PACKAGE_MANIFEST.json` and `tools/create_package_manifest.py` — per-file integrity list and generator.
- `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.properties` — checksum-pinned custom Gradle bootstrap.
- `.github/workflows/android-build.yml` — CI workflow to test, build, and publish a debug APK artifact.
