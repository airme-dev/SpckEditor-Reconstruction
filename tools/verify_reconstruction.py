#!/usr/bin/env python3
"""Offline sanity checks for the Spck Editor reconstruction project.

This script validates project packaging/configuration only. It does not build an APK,
prove runtime parity, or replace Android Studio/device testing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

ANDROID_NS = "http://schemas.android.com/apk/res/android"


class AssetRefParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.references: list[str] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        attr_map = dict(attrs)
        for key in ("src", "href"):
            value = attr_map.get(key)
            if value:
                self.references.append(value)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", nargs="?", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root: Path = args.project.resolve()
    failures: list[str] = []
    checks = 0

    def require(condition: bool, message: str) -> None:
        nonlocal checks
        checks += 1
        if not condition:
            failures.append(message)

    manifest_path = root / "app/src/main/AndroidManifest.xml"
    paths_path = root / "app/src/main/res/xml/file_paths.xml"
    network_path = root / "app/src/main/res/xml/network_security_config.xml"
    index_path = root / "app/src/main/assets/index.html"
    bridge_path = root / "app/src/main/java/com/reconstruction/spck/NativeFileBridge.java"
    activity_path = root / "app/src/main/java/com/reconstruction/spck/MainActivity.java"
    preview_activity_path = root / "app/src/main/java/com/reconstruction/spck/PreviewActivity.java"
    archive_safety_path = root / "app/src/main/java/com/reconstruction/spck/ArchiveSafety.java"
    archive_io_path = root / "app/src/main/java/com/reconstruction/spck/ProjectArchiveIO.java"
    git_engine_path = root / "app/src/main/java/com/reconstruction/spck/LocalGitEngine.java"
    project_command_path = root / "app/src/main/java/com/reconstruction/spck/ProjectCommandRunner.java"
    workspace_state_path = root / "app/src/main/java/com/reconstruction/spck/WorkspaceStateStore.java"
    workspace_state_test_path = root / "tools/WorkspaceStateStoreTest.java"
    bundle_path = root / "app/src/main/assets/assets/bundle/app.bundle.js"
    build_path = root / "app/build.gradle"

    for required in (manifest_path, paths_path, network_path, index_path, bridge_path, activity_path, preview_activity_path, archive_safety_path, archive_io_path, git_engine_path, project_command_path, workspace_state_path, workspace_state_test_path, bundle_path, build_path):
        require(required.is_file(), f"Missing required file: {required.relative_to(root) if required.is_relative_to(root) else required}")

    if failures:
        for failure in failures:
            print(f"FAIL {failure}")
        return 1

    try:
        manifest = ET.parse(manifest_path).getroot()
        paths_xml = ET.parse(paths_path).getroot()
        network = ET.parse(network_path).getroot()
        require(manifest.tag == "manifest", "Manifest root element is not <manifest>")
        provider = manifest.find(f"application/provider[@{{{ANDROID_NS}}}name='androidx.core.content.FileProvider']")
        require(provider is not None, "AndroidX FileProvider is not declared")
        if provider is not None:
            require(provider.get(f"{{{ANDROID_NS}}}authorities") == "${applicationId}.fileprovider", "Unexpected FileProvider authority")
            require(provider.get(f"{{{ANDROID_NS}}}exported") == "false", "FileProvider must not be exported")
            meta = provider.find("meta-data")
            require(meta is not None and meta.get(f"{{{ANDROID_NS}}}resource") == "@xml/file_paths", "FileProvider path metadata missing")
        files_paths = list(paths_xml)
        require(any(x.tag.endswith("files-path") and x.get("path") == "spck-projects/exports/" for x in files_paths), "FileProvider scope is not limited to export directory")
        base_configs = network.findall("base-config")
        require(bool(base_configs) and base_configs[0].get("cleartextTrafficPermitted") == "false", "Default cleartext policy is not disabled")
        require(any(x.find("domain") is not None and x.find("domain").text == "localhost" for x in network.findall("domain-config")), "Localhost cleartext exception missing")
        preview_activity = manifest.find("application/activity[@{" + ANDROID_NS + "}name='.PreviewActivity']")
        require(preview_activity is not None, "Isolated PreviewActivity missing from manifest")
        if preview_activity is not None:
            require(preview_activity.get("{" + ANDROID_NS + "}exported") == "false", "PreviewActivity must not be exported")
    except ET.ParseError as exc:
        failures.append(f"XML parse failed: {exc}")

    try:
        html = index_path.read_text(encoding="utf-8", errors="replace")
        ref_parser = AssetRefParser()
        ref_parser.feed(html)
        for ref in ref_parser.references:
            parsed = urlparse(ref)
            if parsed.scheme or ref.startswith(("#", "data:")):
                continue
            target = root / "app/src/main/assets" / Path(ref.split("?", 1)[0])
            require(target.is_file(), f"Entry-page asset reference missing: {ref}")
    except OSError as exc:
        failures.append(f"Could not read entry HTML: {exc}")

    bridge = bridge_path.read_text(encoding="utf-8", errors="replace")
    activity = activity_path.read_text(encoding="utf-8", errors="replace")
    preview_activity = preview_activity_path.read_text(encoding="utf-8", errors="replace")
    archive_safety = archive_safety_path.read_text(encoding="utf-8", errors="replace")
    archive_io = archive_io_path.read_text(encoding="utf-8", errors="replace")
    git_engine = git_engine_path.read_text(encoding="utf-8", errors="replace")
    project_command = project_command_path.read_text(encoding="utf-8", errors="replace")
    workspace_state = workspace_state_path.read_text(encoding="utf-8", errors="replace")
    workspace_state_test = workspace_state_test_path.read_text(encoding="utf-8", errors="replace")
    bundle = bundle_path.read_text(encoding="utf-8", errors="replace")
    gradle = build_path.read_text(encoding="utf-8", errors="replace")
    required_methods = ("mkdirp", "exists", "readFile", "write", "remove", "readdir", "readdirDeep", "lstat", "mv", "copy", "gitHashObject", "findInFile", "saveBase64", "saveBase64AsDownload", "initServer", "initPreviewServer", "stopPreviewServer", "clipboardText", "copyText", "launchWebView")
    for name in required_methods:
        require(bool(re.search(r"@JavascriptInterface\s+public\s+(?:(?:static|final|synchronized)\s+)*[\w<>.?\[\]]+\s+" + re.escape(name) + r"\s*\(", bridge)), f"Expected @JavascriptInterface method missing: {name}")
    require("saveBase64AsDownload" in bundle, "Recovered frontend bundle does not reference saveBase64AsDownload")
    require("androidx.core:core:1.15.0" in gradle, "AndroidX Core dependency missing (required for FileProvider)")
    require("versionCode 9" in gradle and "0.9.0-reconstruction-final" in gradle, "Gradle version metadata was not advanced for final Phase 9")
    require("ACTION_OPEN_DOCUMENT" in activity and "REQUEST_IMPORT_ARCHIVE" in activity, "SAF ZIP import picker is not wired")
    require("ACTION_CREATE_DOCUMENT" in activity and "REQUEST_EXPORT_ARCHIVE" in activity, "SAF ZIP export destination picker is not wired")
    require("PROJECT_ARCHIVE_TOOLS_SCRIPT" in activity and "__spck_zip_import" in activity and "__spck_zip_export" in activity, "Editor ZIP tools overlay is missing")
    require("__spck_zip_reload" in activity and "window.location.reload()" in activity, "User-controlled workspace reload action is missing")
    require("listExportableProjects" in bridge and "requestImportProjectArchive" in bridge and "requestExportProjectArchive" in bridge, "ZIP tools bridge methods are missing")
    require("ProjectArchiveIO.importArchive" in bridge and "ProjectArchiveIO.exportProject" in bridge, "Native bridge does not delegate archive work to the testable ZIP utility")
    require(bool(re.search(r"@JavascriptInterface\s+public\s+void\s+executeLocalGit\s*\(", bridge)), "Asynchronous local Git bridge method missing")
    require(bool(re.search(r"@JavascriptInterface\s+public\s+void\s+executeProjectCommand\s*\(", bridge)), "Restricted project-console bridge method missing")
    for op in ("init", "status", "stageAll", "commit", "log", "branches", "createBranch", "checkout"):
        require('case "' + op + '"' in git_engine, f"Local Git operation missing: {op}")
    require("write(new byte[]{'D', 'I', 'R', 'C'})" in git_engine and "writeIndex" in git_engine and "writeTree" in git_engine and "writeObject" in git_engine, "Standard local Git object/index support is incomplete")
    require("MAX_PROJECT_BYTES" in git_engine and "MAX_FILES" in git_engine and 'part.equals(".git")' in git_engine, "Local Git worktree limits or .git path protection missing")
    require("executeProjectCommand" in bridge and "ProjectCommandRunner.execute" in bridge, "Restricted command runner is not wired to the Android bridge")
    require("ProcessBuilder" not in project_command and "Runtime.getRuntime" not in project_command and "exec(" not in project_command, "Project console must not spawn arbitrary processes")
    require("Shell operators and expansions are not supported" in project_command and 'part.equalsIgnoreCase(".git")' in project_command, "Project console command/path safety controls missing")
    require("__spck_git_init" in activity and "__spck_git_stage" in activity and "__spck_git_commit" in activity and "window.Android.executeLocalGit" in activity, "Local Git UI panel wiring missing")
    require("__spck_console_run" in activity and "window.Android.executeProjectCommand" in activity and "window.__spckConsoleTools" in activity, "Project console UI callback wiring missing")
    require("normalizeZipEntryName" in archive_safety and "isSafeImmediateProjectName" in archive_safety, "Archive path policy helpers are missing")
    require("MAX_ARCHIVE_INPUT_BYTES" in archive_io and "MAX_ARCHIVE_EXPANDED_BYTES" in archive_io and "MAX_ARCHIVE_ENTRIES" in archive_io, "ZIP size/entry bounds are missing")
    require("duplicate paths" in archive_io.lower() and "Thread.currentThread().isInterrupted()" in archive_io, "ZIP duplicate-path or cancellation protection is missing")
    require("openIsolatedPreview(requested)" in activity, "Editor WebView does not route loopback preview navigation to isolated activity")
    require('webView.addJavascriptInterface(nativeBridge, "Android")' in activity, "Editor native bridge binding missing")
    require("webView.addJavascriptInterface" not in preview_activity and "nativeBridge" not in preview_activity, "PreviewActivity must not expose a JavaScript bridge")
    require("setAllowFileAccess(false)" in preview_activity, "Preview WebView must disable direct file access")
    require("setAllowContentAccess(false)" in preview_activity, "Preview WebView must disable direct content URI access")
    require("isSamePreviewOrigin(uri)" in preview_activity, "Preview URL navigation must be origin-scoped")
    for name in ("getWorkspaceState", "recordProjectUse", "forgetRecentProject"):
        require(bool(re.search(r"@JavascriptInterface\s+public\s+(?:(?:static|final|synchronized)\s+)*[\w<>.?\[\]]+\s+" + re.escape(name) + r"\s*\(", bridge)), f"Workspace persistence bridge method missing: {name}")
    require("new WorkspaceStateStore" in bridge and "spck-workspace.properties" in bridge, "Native bridge does not persist workspace metadata outside project directories")
    require("persistAtomically" in workspace_state and "MAX_RECENT_PROJECTS" in workspace_state and "getCanonicalFile" in workspace_state, "Workspace persistence lacks atomic writes, bounded recents, or canonical path checks")
    require("window.Android.getWorkspaceState()" in activity and "window.Android.recordProjectUse(name)" in activity, "Tool overlay is not connected to persistent workspace metadata")
    require("localStorage.setItem('__spck_tools_panel_open'" in activity, "Project tools panel visibility is not persisted")
    require("onRenderProcessGone(WebView view, RenderProcessGoneDetail detail)" in activity and "handleRendererGone(view, detail)" in activity, "WebView renderer-exit recovery handler is not installed")
    require("isTrustedEditorUrl(webView.getUrl())" in activity and "forceFreshStartAfterRendererExit" in activity, "WebView restoration does not validate editor origin or recover from renderer exit")
    require("protected void onPause()" in activity and "protected void onResume()" in activity, "WebView lifecycle pause/resume hooks are missing")
    require("recordUse(\"project\" + i)" in workspace_state_test and "corrupt store safely recovers empty" in workspace_state_test, "Workspace persistence regression coverage is incomplete")
    require("MAX_RECENT_PROJECTS" in workspace_state_test and "2147483647" in workspace_state_test, "Workspace metadata count-bound regression test is missing")

    java_files = list((root / "app/src/main/java").rglob("*.java"))
    require(len(java_files) >= 5, "Expected Android Java source files are missing")
    require((root / "tools/ArchiveSafetyTest.java").is_file(), "Archive path policy regression tests are missing")
    require((root / "tools/ProjectArchiveIOTest.java").is_file(), "ZIP import/export regression tests are missing")
    require((root / "tools/LocalGitEngineTest.java").is_file(), "Local Git interoperability tests are missing")
    require((root / "tools/ProjectCommandRunnerTest.java").is_file(), "Restricted project-console regression tests are missing")
    require((root / "tools/WorkspaceStateStoreTest.java").is_file(), "Workspace persistence regression tests are missing")
    require((root / "tools/check_injected_tools_js.sh").is_file(), "JavaScript text-block syntax checker is missing")
    wrapper_props = root / "gradle/wrapper/gradle-wrapper.properties"
    unix_launcher = root / "gradlew"
    windows_launcher = root / "gradlew.bat"
    workflow = root / ".github/workflows/android-build.yml"
    require(wrapper_props.is_file(), "Checksum-pinned Gradle distribution properties are missing")
    require(unix_launcher.is_file() and bool(unix_launcher.stat().st_mode & 0o111), "Unix Gradle bootstrap launcher is missing or not executable")
    require(windows_launcher.is_file(), "Windows Gradle bootstrap launcher is missing")
    if wrapper_props.is_file():
        wrapper_text = wrapper_props.read_text(encoding="utf-8", errors="replace")
        require("gradle-8.9-bin.zip" in wrapper_text and "distributionSha256Sum=" in wrapper_text, "Gradle bootstrap version or checksum pin is missing")
        expected_sha = re.search(r"^distributionSha256Sum=([0-9a-f]{64})$", wrapper_text, re.MULTILINE)
        windows_launcher_text = windows_launcher.read_text(encoding="utf-8", errors="replace") if windows_launcher.is_file() else ""
        if expected_sha:
            require(expected_sha.group(1) in windows_launcher_text.lower(), "Windows bootstrap checksum does not match the Gradle properties")
        else:
            require(False, "Gradle bootstrap checksum is not a 64-character lowercase SHA-256 value")
        require("services.gradle.org/distributions/gradle-8.9-bin.zip" in wrapper_text.replace("\\:", ":"), "Gradle distribution URL is not the expected official 8.9 binary distribution")
    if unix_launcher.is_file():
        launcher_text = unix_launcher.read_text(encoding="utf-8", errors="replace")
        require("sha256sum" in launcher_text and "checksum mismatch" in launcher_text.lower(), "Unix bootstrap does not verify downloaded Gradle checksum")
    if windows_launcher.is_file():
        launcher_text = windows_launcher.read_text(encoding="utf-8", errors="replace")
        require("Get-FileHash" in launcher_text and "EXPECTED_SHA" in launcher_text, "Windows bootstrap does not verify downloaded Gradle checksum")
        require("https://services.gradle.org/distributions/gradle-8.9-bin.zip" in launcher_text, "Windows bootstrap distribution URL does not match the pinned Gradle version")
    require(workflow.is_file(), "Android CI build workflow is missing")
    if workflow.is_file():
        workflow_text = workflow.read_text(encoding="utf-8", errors="replace")
        require("assembleDebug" in workflow_text and "android-35" in workflow_text, "CI workflow must install API 35 and build the debug APK")
        require("tools/test_reconstruction.sh" in workflow_text and "upload-artifact@v4" in workflow_text, "CI workflow must test sources and publish the APK artifact")
    assets = root / "app/src/main/assets"
    require((assets / "assets/bundle/ace.bundle.js").is_file(), "Ace editor bundle missing")
    require((assets / "assets/bundle/git.bundle.js").is_file(), "Git UI bundle missing")
    require((assets / "assets/bundle/xterm.bundle.js").is_file(), "Terminal emulator bundle missing")

    manifest_path = root / "PACKAGE_MANIFEST.json"
    manifest_valid = False
    if manifest_path.is_file():
        try:
            manifest_doc = json.loads(manifest_path.read_text(encoding="utf-8"))
            entries = manifest_doc.get("files", [])
            expected_paths = [entry.get("path") for entry in entries]
            actual_paths = []
            for candidate in root.rglob("*"):
                if not candidate.is_file() or candidate == manifest_path:
                    continue
                relative_parts = candidate.relative_to(root).parts
                if any(part in {".git", ".gradle", "build", "__pycache__"} for part in relative_parts):
                    continue
                if candidate.suffix in {".pyc", ".iml"} or candidate.name in {".DS_Store", "Thumbs.db"}:
                    continue
                actual_paths.append(candidate.relative_to(root).as_posix())
            if len(expected_paths) != len(set(expected_paths)):
                raise ValueError("manifest contains duplicate file paths")
            if set(expected_paths) != set(actual_paths):
                missing = sorted(set(actual_paths) - set(expected_paths))
                extra = sorted(set(expected_paths) - set(actual_paths))
                raise ValueError(f"manifest file set differs (unlisted={missing[:3]}, missing={extra[:3]})")
            by_path = {entry["path"]: entry for entry in entries}
            for relative in sorted(actual_paths):
                target = root / relative
                entry = by_path[relative]
                if target.stat().st_size != entry.get("bytes"):
                    raise ValueError(f"manifest size mismatch for {relative}")
                digest = hashlib.sha256(target.read_bytes()).hexdigest()
                if digest != entry.get("sha256"):
                    raise ValueError(f"manifest SHA-256 mismatch for {relative}")
            if manifest_doc.get("phase") != 9 or manifest_doc.get("versionName") != "0.9.0-reconstruction-final":
                raise ValueError("manifest package version metadata does not match Phase 9")
            manifest_valid = True
        except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            failures.append(f"Package manifest validation failed: {exc}")
    require(manifest_valid, "Package integrity manifest is missing or invalid")
    require((root / "tools/create_package_manifest.py").is_file(), "Package integrity manifest generator missing")

    for failure in failures:
        print(f"FAIL {failure}")
    if failures:
        print(f"\n{len(failures)} failure(s) across {checks} checks")
        return 1
    print(f"PASS: {checks} static checks; {len(java_files)} Java source files; recovered frontend bundles, ZIP tools, local Git, and restricted project console present.")
    print("Note: these checks do not compile the Android app or verify runtime parity.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
