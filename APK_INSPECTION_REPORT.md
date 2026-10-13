# Spck Editor APK Reconstruction — Initial Inspection

## Input examined
- File: `Spck Editor_10.8.1.0_io.spck.apk`
- APK size: 17,357,376 bytes (~16.6 MiB)
- ZIP entries: 894
- Android `res/` entries: 254
- App `assets/` entries: 479
- DEX files: `classes.dex` (~9.8 MB) and `classes2.dex` (~0.8 MB)

## Key finding
This APK includes a substantial web application bundled inside the Android app. `assets/index.html` loads JavaScript/CSS bundles and the application uses a web-based editor UI. The APK is useful as a reconstruction reference, but it is not the original editable Android Studio/source repository. The bundled/minified JavaScript is compiled production output, so original module boundaries, comments, and development setup may be missing.

## Bundled web application files
- `assets/assets/bundle/ace.bundle.js` — 729,480 bytes
- `assets/assets/bundle/app.bundle.js` — 1,710,151 bytes
- `assets/assets/bundle/app.css` — 159,138 bytes
- `assets/assets/bundle/editor_tools.bundle.js` — 216,093 bytes
- `assets/assets/bundle/git.bundle.js` — 323,431 bytes
- `assets/assets/bundle/spck-ui-icons.woff` — 45,812 bytes
- `assets/assets/bundle/spck-ui-icons.woff2` — 36,296 bytes
- `assets/assets/bundle/vendor.bundle.css` — 139,008 bytes
- `assets/assets/bundle/vendor.bundle.js` — 525,745 bytes
- `assets/assets/bundle/xterm.bundle.js` — 316,003 bytes

## Directly evidenced capabilities / technology clues
- Code editor assets include Ace editor language modes: **156** mode files detected.
- A Git-focused bundle is present (`git.bundle.js`) with Git object/index/commit/branch/remote-related implementation strings.
- A terminal emulator bundle is present (`xterm.bundle.js`) with matching CSS.
- An editor-tools bundle is present (`editor_tools.bundle.js`), including formatting/beautification utilities.
- Main app bundle contains identifiers including: `ProjectStore`, `initPreviewServer`, `stopPreviewServer`, `setConsole`, `getConsole`, `readFile`, `findInFile`, `gitHashObject`, `previewUrl`, `openedFilePaths`, `unpinnedTabs`, `previewMode`, `showEditor`, `preserveHistory`, `gitconfig`, `.spck-editor`, `run.spck.io`.
- `index.html` includes English plus German, Spanish, French, Indonesian, Japanese, Korean, Russian, Portuguese, Simplified Chinese and Traditional Chinese warning text, suggesting multilingual UI support.
- Icon font assets are included: `spck-ui-icons.woff` and `.woff2`.
- CSS color literals detected in `app.css`: #050810, #06b6d4, #09090b, #0a0f18, #0dbf7f, #101b2d, #1069a9, #111827, #172640, #1976d2, #1a2a4a, #1e9dff, #22c55e, #23902e, #28c840, #2d2d2d, #2e7ee5, #3871db, #38bdf8, #3a8fe4, #3b82f6, #3d3d3d, #4ade80, #4d4d4d, #4d5969, #52535b, #529cec, #56b6c2, #588fff, #5ae6a5, #5d5d5d, #61afef, #64748b, #64afff, #71717a, #7f1d1d, #854ca0, #902323, #909194, #94a3b8.

## Important reconstruction limits
- APK inspection can recover bundled frontend resources and identify many behaviors, but it does not automatically recover the original clean source tree or build scripts.
- Some behavior is implemented in native Android code, plugins, local services, or remote endpoints; these need separate reverse engineering or behavior testing.
- The exact installed screen states, menus, dialogs, and responsive behavior should be captured from the running app using screenshots/screen recordings. A static APK alone does not show every state.
- Recreating a similar app is different from having the original upstream source; exact parity must be verified screen by screen.
- Use only assets and code you have rights to use. If publishing a derivative, review the original app's licensing and branding terms.

## Recommended reconstruction plan
1. **Inventory and manifest pass:** decode Android manifest/package metadata, permissions, activities, services, receivers, deep links and SDK versions.
2. **Asset/UI pass:** catalog icons, font glyphs, CSS variables, theme names, app routes, translation keys and templates.
3. **Runtime observation:** run the reference APK on a test device/emulator; record all primary flows, long-press/context menus, dialogs, editor modes, settings and failure states.
4. **Behavior map:** build a screen/action/state matrix covering project list, file explorer, editor tabs, search/replace, editor preferences, preview/local server, terminal, Git, import/export and settings, validating each item against runtime evidence.
5. **Native bridge pass:** map JavaScript-to-Android bridge calls, filesystem permissions, document picker, share/export, local server, terminal and Git integration.
6. **Implementation:** build a fresh maintainable Android project, reusing standard open-source components where licensing permits; separate UI, editor engine, project filesystem, Git, preview server, persistence and platform bridge.
7. **Verification:** automated tests for storage, project/file operations, editing persistence, search/replace, Git operations, preview lifecycle, permissions, and regression screenshots.

## Bundled files extracted for reference
The accompanying archive contains the APK's web entrypoint, app/vendor/editor/Git/terminal bundles, CSS, and a full APK entry inventory. It is an inspection/reference pack, **not a reconstructed source project or installable APK**.


## Phase 3 update (October 2026)

The starter project now includes a first-pass Android bridge and local preview foundation. Common filesystem calls are backed by the app-private `files/spck-projects` directory and exposed at virtual path `/documents/`. The implementation also includes a loopback static preview server, clipboard access, HTML file chooser handling, WebView navigation controls, and a network-security exception scoped to `localhost`.

This implementation is inferred from the bundled minified JavaScript and is not an exact recovery of the original native layer. See `PHASE_3_IMPLEMENTATION_REPORT.md` for the implemented surface, security assumptions, limitations, and verification status. No Gradle build, APK install, or device runtime comparison has been performed because this environment lacks Android SDK/Gradle tooling.
