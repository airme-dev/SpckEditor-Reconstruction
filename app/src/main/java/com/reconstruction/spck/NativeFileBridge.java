package com.reconstruction.spck;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A deliberately small, app-private filesystem adapter for the bundled editor.
 * It follows the JSON-string result convention used by the recovered JS bundle.
 * This does not recreate the original app's external storage, Git, or server APIs.
 */
public final class NativeFileBridge {
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    public interface ArchiveCallbacks {
        void requestImportArchive();
        void requestExportArchive(String projectName);
        void onArchiveOperationFinished(boolean success, String message);
        void onGitOperationFinished(String operation, boolean success, String resultJson);
        void onProjectCommandFinished(boolean success, String resultJson);
        void onAppColorChanged(int color);
    }

    private static final class InvalidPathException extends IOException {
        InvalidPathException(String message) { super(message); }
    }
    private final Context context;
    private final File root;
    private final WorkspaceStateStore workspaceState;
    private volatile File serverDirectory;
    private volatile LoopbackPreviewServer mainServer;
    private volatile File mainServerRoot;
    private volatile LoopbackPreviewServer previewServer;
    private volatile File previewRoot;
    private volatile String consoleText = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // One queue serializes ZIP import/export, Git and console mutations to avoid races
    // over project files and a repository index while keeping disk I/O off the UI thread.
    private final ExecutorService projectExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SpckProjectOperations");
        thread.setDaemon(true);
        return thread;
    });
    private volatile WeakReference<ArchiveCallbacks> archiveCallbacks = new WeakReference<>(null);

    public NativeFileBridge(Context context, ArchiveCallbacks callbacks) {
        this.context = context.getApplicationContext();
        this.archiveCallbacks = new WeakReference<>(callbacks);
        this.root = new File(this.context.getFilesDir(), "spck-projects");
        if (!root.exists()) root.mkdirs();
        this.workspaceState = new WorkspaceStateStore(
                new File(this.context.getFilesDir(), "spck-workspace.properties"), root);
    }

    @JavascriptInterface
    public String getStorageDir() {
        return "/documents/";
    }

    @JavascriptInterface
    public String getAppColor() {
        return context.getSharedPreferences("spck_settings", Context.MODE_PRIVATE)
                .getString("app_color", "#23293c");
    }

    @JavascriptInterface
    public boolean setAppColor(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) return false;
        final int color;
        try {
            color = Color.parseColor(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        context.getSharedPreferences("spck_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("app_color", value.toLowerCase(Locale.ROOT))
                .apply();
        mainHandler.post(() -> {
            ArchiveCallbacks callbacks = archiveCallbacks.get();
            if (callbacks != null) callbacks.onAppColorChanged(color);
        });
        return true;
    }

    /** Returns persistent, validated workspace metadata for the trusted editor tools overlay. */
    @JavascriptInterface
    public String getWorkspaceState() {
        JSONObject result = new JSONObject();
        try {
            result.put("schemaVersion", 1);
            result.put("lastUsedProject", workspaceState.getLastUsedProject());
            result.put("recentProjects", new JSONArray(workspaceState.getRecentProjects()));
        } catch (JSONException ignored) { }
        return result.toString();
    }

    /** Records a project only when it is a real immediate child of the private project root. */
    @JavascriptInterface
    public boolean recordProjectUse(String projectName) {
        if (!ArchiveSafety.isSafeImmediateProjectName(projectName)) return false;
        try {
            File canonicalRoot = root.getCanonicalFile();
            File project = new File(canonicalRoot, projectName).getCanonicalFile();
            if (!project.isDirectory() || project.getParentFile() == null
                    || !project.getParentFile().equals(canonicalRoot)) return false;
            return workspaceState.recordUse(projectName);
        } catch (IOException e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean forgetRecentProject(String projectName) {
        return workspaceState.forgetProject(projectName);
    }

    /** Called by the trusted editor shell's built-in ZIP tools overlay. */
    @JavascriptInterface
    public void requestImportProjectArchive() {
        mainHandler.post(() -> {
            ArchiveCallbacks callbacks = archiveCallbacks.get();
            if (callbacks != null) callbacks.requestImportArchive();
            else notifyArchiveResult(false, "Project import is not available right now.");
        });
    }

    /** Returns immediate project directories, excluding app-owned export/temp folders. */
    @JavascriptInterface
    public String listExportableProjects() {
        JSONArray projects = new JSONArray();
        try {
            File canonicalRoot = root.getCanonicalFile();
            File[] children = canonicalRoot.listFiles();
            if (children == null) return projects.toString();
            java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                if (!child.isDirectory() || !ArchiveSafety.isSafeImmediateProjectName(child.getName())) continue;
                File canonicalChild = child.getCanonicalFile();
                if (!canonicalChild.getParentFile().equals(canonicalRoot)) continue;
                JSONObject item = new JSONObject();
                item.put("name", child.getName());
                item.put("path", virtualPath(child));
                projects.put(item);
            }
        } catch (Exception e) {
            return error("IoError", safeMessage(e), 100500);
        }
        return projects.toString();
    }

    @JavascriptInterface
    public void requestExportProjectArchive(String projectName) {
        if (!ArchiveSafety.isSafeImmediateProjectName(projectName)) {
            notifyArchiveResult(false, "Invalid project name selected for export.");
            return;
        }
        try {
            File project = resolve("/documents/" + projectName).getCanonicalFile();
            if (!project.isDirectory() || !project.getParentFile().equals(root.getCanonicalFile())) {
                notifyArchiveResult(false, "Project folder could not be found.");
                return;
            }
            mainHandler.post(() -> {
                ArchiveCallbacks callbacks = archiveCallbacks.get();
                if (callbacks != null) callbacks.requestExportArchive(projectName);
                else notifyArchiveResult(false, "Project export is not available right now.");
            });
        } catch (Exception e) {
            notifyArchiveResult(false, "Could not prepare project export: " + safeMessage(e));
        }
    }

    /**
     * Dispatches a bounded local Git operation. The project identifier is a safe immediate
     * child name, never a path supplied by JavaScript. Operations run off the renderer/UI thread.
     */
    @JavascriptInterface
    public void executeLocalGit(String operation, String projectName, String argument) {
        if (!isSupportedGitOperation(operation)) {
            notifyGitResult(operation == null ? "" : operation, false, gitError("Unsupported local Git operation"));
            return;
        }
        if (!ArchiveSafety.isSafeImmediateProjectName(projectName)) {
            notifyGitResult(operation, false, gitError("Invalid project selected"));
            return;
        }
        String safeArgument = argument == null ? "" : argument;
        if (safeArgument.length() > 10_000) {
            notifyGitResult(operation, false, gitError("Git argument is too long"));
            return;
        }
        try {
            projectExecutor.execute(() -> {
                boolean success = false;
                String result;
                try {
                    File canonicalRoot = root.getCanonicalFile();
                    File project = new File(canonicalRoot, projectName).getCanonicalFile();
                    if (!project.getParentFile().equals(canonicalRoot) || !project.isDirectory()) {
                        throw new IOException("Project folder could not be found");
                    }
                    result = LocalGitEngine.execute(project, operation, safeArgument);
                    success = true;
                } catch (Exception e) {
                    result = gitError(safeMessage(e));
                }
                notifyGitResult(operation, success, result);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            notifyGitResult(operation, false, gitError("Git service is shutting down"));
        }
    }

    private boolean isSupportedGitOperation(String operation) {
        return "init".equals(operation) || "status".equals(operation) || "stageAll".equals(operation)
                || "stageFile".equals(operation) || "unstageFile".equals(operation) || "unstageAll".equals(operation)
                || "commit".equals(operation) || "log".equals(operation) || "branches".equals(operation)
                || "createBranch".equals(operation) || "checkout".equals(operation);
    }

    /** Execute one whitelisted project-console command; this does not spawn a shell or process. */
    @JavascriptInterface
    public void executeProjectCommand(String projectName, String commandLine) {
        if (!ArchiveSafety.isSafeImmediateProjectName(projectName)) {
            notifyProjectCommandResult(false, commandError(commandLine, "Invalid project selected"));
            return;
        }
        if (commandLine != null && commandLine.length() > 1024) {
            notifyProjectCommandResult(false, commandError(commandLine, "Command exceeds the 1,024 character limit"));
            return;
        }
        String safeCommand = commandLine == null ? "" : commandLine;
        try {
            projectExecutor.execute(() -> {
                boolean success = false;
                String result;
                try {
                    File canonicalRoot = root.getCanonicalFile();
                    File project = new File(canonicalRoot, projectName).getCanonicalFile();
                    if (!project.getParentFile().equals(canonicalRoot) || !project.isDirectory()) throw new IOException("Project folder could not be found");
                    result = ProjectCommandRunner.execute(project, safeCommand).toJson();
                    success = true;
                } catch (Exception e) {
                    result = commandError(safeCommand, safeMessage(e));
                }
                notifyProjectCommandResult(success, result);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            notifyProjectCommandResult(false, commandError(safeCommand, "Project console is shutting down"));
        }
    }

    private String commandError(String commandLine, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("command", commandLine == null ? "" : commandLine);
            result.put("output", message == null ? "Command failed" : message);
        } catch (JSONException ignored) { }
        return result.toString();
    }

    private void notifyProjectCommandResult(boolean success, String resultJson) {
        mainHandler.post(() -> {
            ArchiveCallbacks callbacks = archiveCallbacks.get();
            if (callbacks != null) callbacks.onProjectCommandFinished(success, resultJson);
        });
    }

    private String gitError(String message) {
        JSONObject result = new JSONObject();
        try { result.put("ok", false); result.put("error", message == null ? "Git operation failed" : message); }
        catch (JSONException ignored) { }
        return result.toString();
    }

    private void notifyGitResult(String operation, boolean success, String resultJson) {
        mainHandler.post(() -> {
            ArchiveCallbacks callbacks = archiveCallbacks.get();
            if (callbacks != null) callbacks.onGitOperationFinished(operation, success, resultJson);
        });
    }

    /** Performs SAF ZIP import away from the UI thread. */
    public void importProjectArchiveAsync(Uri uri) {
        if (uri == null) {
            notifyArchiveResult(false, "No ZIP archive was selected.");
            return;
        }
        try {
            projectExecutor.execute(() -> {
            boolean success = false;
            String message;
            try {
                String displayName = getDisplayName(uri);
                String importedName;
                try (InputStream source = context.getContentResolver().openInputStream(uri)) {
                    if (source == null) throw new IOException("Could not open selected archive");
                    importedName = ProjectArchiveIO.importArchive(source, root, displayName);
                }
                success = true;
                message = "Imported '" + importedName + "' to /documents/" + importedName + ". Refresh the workspace to open it.";
            } catch (Exception e) {
                message = "Import failed: " + safeMessage(e);
            }
            notifyArchiveResult(success, message);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            notifyArchiveResult(false, "Project service is shutting down; import was not started.");
        }
    }

    /** Streams one project directory into a SAF-selected ZIP document. */
    public void exportProjectArchiveAsync(Uri destination, String projectName) {
        if (destination == null || !ArchiveSafety.isSafeImmediateProjectName(projectName)) {
            notifyArchiveResult(false, "Invalid export destination or project name.");
            return;
        }
        try {
            projectExecutor.execute(() -> {
            boolean success = false;
            String message;
            try {
                File project = resolve("/documents/" + projectName).getCanonicalFile();
                File canonicalRoot = root.getCanonicalFile();
                if (!project.isDirectory() || !project.getParentFile().equals(canonicalRoot)) {
                    throw new IOException("Project folder could not be found");
                }
                OutputStream output = context.getContentResolver().openOutputStream(destination, "w");
                if (output == null) throw new IOException("Could not open ZIP destination");
                ProjectArchiveIO.exportProject(project, output);
                success = true;
                message = "Exported '" + projectName + "' as a ZIP archive.";
            } catch (Exception e) {
                message = "Export failed: " + safeMessage(e) + " The destination may contain a partial ZIP.";
            }
            notifyArchiveResult(success, message);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            notifyArchiveResult(false, "Project service is shutting down; export was not started.");
        }
    }

    private void notifyArchiveResult(boolean success, String message) {
        mainHandler.post(() -> {
            ArchiveCallbacks callbacks = archiveCallbacks.get();
            if (callbacks != null) callbacks.onArchiveOperationFinished(success, message);
        });
    }

    @JavascriptInterface
    public synchronized boolean setServerDir(String path) {
        try {
            File target = resolve(path);
            if (!target.exists() && !target.mkdirs()) return false;
            if (!target.isDirectory()) return false;
            serverDirectory = target.getCanonicalFile();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public synchronized String initServer() {
        try {
            File base = serverDirectory == null ? root : serverDirectory;
            if (mainServer == null || mainServerRoot == null || !base.getCanonicalFile().equals(mainServerRoot)) {
                closeMainServer();
                mainServer = new LoopbackPreviewServer(base);
                mainServerRoot = base.getCanonicalFile();
            }
            return mainServer.getUrl();
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public synchronized String initPreviewServer(String path) {
        try {
            File base = resolveFromServerDirectory(path).getCanonicalFile();
            if (!base.exists() && !base.mkdirs()) return "";
            if (!base.isDirectory()) return "";
            if (previewServer != null && base.equals(previewRoot)) return previewServer.getUrl();
            closePreviewServer();
            previewRoot = base.getCanonicalFile();
            previewServer = new LoopbackPreviewServer(previewRoot);
            return previewServer.getUrl();
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public synchronized void stopPreviewServer() {
        closePreviewServer();
    }

    @JavascriptInterface
    public synchronized String getUrlFor(String path) {
        String base = initServer();
        if (base.isEmpty()) return "";
        String value = path == null ? "" : path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        return base + value;
    }

    @JavascriptInterface
    public synchronized void stopServer() {
        closeMainServer();
    }

    @JavascriptInterface
    public String getConsole() {
        return consoleText;
    }

    @JavascriptInterface
    public void setConsole(String value) {
        consoleText = value == null ? "" : value;
    }

    @JavascriptInterface
    public String sdkVersion() {
        return String.valueOf(Build.VERSION.SDK_INT);
    }

    @JavascriptInterface
    public String versionName() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "0.1.0-reconstruction";
        }
    }

    @JavascriptInterface
    public String getAppCode() {
        return "android";
    }

    /** Returns the numeric build version code of this app. */
    @JavascriptInterface
    public int getAppBuild() {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception ignored) {
            return 1;
        }
    }

    /** Returns the BCP-47 language tag of the device's primary locale (e.g. "en-US"). */
    @JavascriptInterface
    public String getDeviceLocale() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            return context.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
        }
        //noinspection deprecation
        return context.getResources().getConfiguration().locale.toLanguageTag();
    }

    /** Returns a JSON object with screen width, height (dp), and density. */
    @JavascriptInterface
    public String getScreenDimensions() {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density;
        int widthDp = Math.round(dm.widthPixels / density);
        int heightDp = Math.round(dm.heightPixels / density);
        JSONObject result = new JSONObject();
        try {
            result.put("widthDp", widthDp);
            result.put("heightDp", heightDp);
            result.put("widthPx", dm.widthPixels);
            result.put("heightPx", dm.heightPixels);
            result.put("density", density);
            result.put("scaledDensity", dm.scaledDensity);
        } catch (JSONException ignored) { }
        return result.toString();
    }

    /** Returns a JSON object with device hardware info for analytics/error reporting. */
    @JavascriptInterface
    public String getDeviceInfo() {
        JSONObject result = new JSONObject();
        try {
            result.put("manufacturer", android.os.Build.MANUFACTURER);
            result.put("model", android.os.Build.MODEL);
            result.put("device", android.os.Build.DEVICE);
            result.put("sdkInt", android.os.Build.VERSION.SDK_INT);
            result.put("release", android.os.Build.VERSION.RELEASE);
            result.put("brand", android.os.Build.BRAND);
            result.put("isTablet", isTablet());
        } catch (JSONException ignored) { }
        return result.toString();
    }

    /**
     * Write text to the system clipboard from JavaScript.
     * Must run on the main thread — posted via mainHandler.
     */
    @JavascriptInterface
    public void setClipboard(String text) {
        final String safeText = text == null ? "" : text;
        mainHandler.post(() -> {
            try {
                ClipboardManager manager = (ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager != null) {
                    manager.setPrimaryClip(ClipData.newPlainText("Spck Editor", safeText));
                }
            } catch (Exception ignored) { }
        });
    }

    /**
     * Open an http/https URL in the device browser. Returns true if the intent was dispatched.
     * Alias for launchWebView with additional scheme validation for clarity.
     */
    @JavascriptInterface
    public boolean openExternalUrl(String rawUrl) {
        return launchWebView(rawUrl);
    }

    @JavascriptInterface
    public boolean isTablet() {
        float density = context.getResources().getDisplayMetrics().density;
        float widthDp = context.getResources().getDisplayMetrics().widthPixels / Math.max(1f, density);
        return widthDp >= 600f;
    }

    @JavascriptInterface
    public boolean proxySupported() {
        return false;
    }

    @JavascriptInterface
    public boolean exists(String path) {
        try {
            return resolve(path).exists();
        } catch (Exception ignored) {
            return false;
        }
    }

    @JavascriptInterface
    public String mkdirp(String path) {
        try {
            File target = resolve(path);
            if (target.isFile()) return error("NotDirectory", "Path is a file: " + path, 100315);
            if (!target.exists() && !target.mkdirs()) return error("IoError", "Could not create directory", 100500);
            return contents(true);
        } catch (Exception e) {
            return error("InvalidPath", safeMessage(e), 100400);
        }
    }

    @JavascriptInterface
    public String mkdir(String path) {
        return mkdirp(path);
    }

    @JavascriptInterface
    public String readFile(String path, String encoding) {
        return readFile(path, encoding, false);
    }

    /**
     * Three-argument overload called by the recovered JS bundle as
     * device.readFile(path, encoding, forceBase64).
     * When forceBase64 is true the content is always returned as a Base64 string
     * regardless of the encoding hint (the JS layer decodes it afterward).
     */
    @JavascriptInterface
    public String readFile(String path, String encoding, boolean forceBase64) {
        try {
            File target = resolve(path);
            if (!target.isFile()) return error("FileNotFound", "File not found: " + path, 100314);
            if (target.length() > MAX_FILE_BYTES) return error("FileTooLarge", "File exceeds 64 MiB limit", 100413);
            byte[] bytes = readBytes(target);
            String enc = normalizeEncoding(encoding);
            String value;
            if (!forceBase64 && ("utf8".equals(enc) || "utf-8".equals(enc))) {
                value = new String(bytes, StandardCharsets.UTF_8);
            } else if (!forceBase64 && ("utf16le".equals(enc) || "utf-16le".equals(enc))) {
                value = new String(bytes, Charset.forName("UTF-16LE"));
            } else if (!forceBase64 && ("utf16be".equals(enc) || "utf-16be".equals(enc))) {
                value = new String(bytes, Charset.forName("UTF-16BE"));
            } else if (!forceBase64 && ("latin1".equals(enc) || "ascii".equals(enc) || "iso-8859-1".equals(enc))) {
                value = new String(bytes, StandardCharsets.ISO_8859_1);
            } else {
                value = Base64.encodeToString(bytes, Base64.NO_WRAP);
            }
            return contents(value);
        } catch (InvalidPathException e) {
            return error("InvalidPath", safeMessage(e), 100400);
        } catch (Exception e) {
            return error("IoError", safeMessage(e), 100500);
        }
    }

    @JavascriptInterface
    public String write(String path, String value, String encoding, boolean includeBOM, boolean executable) {
        try {
            File target = resolve(path);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return error("IoError", "Could not create parent directory", 100500);
            }
            byte[] bytes = decodeContents(value, normalizeEncoding(encoding));
            if (bytes.length > MAX_FILE_BYTES) return error("FileTooLarge", "File exceeds 64 MiB limit", 100413);
            if (includeBOM) bytes = withBom(bytes, normalizeEncoding(encoding));
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write(bytes);
                out.getFD().sync();
            }
            // Android's app-private filesystem does not expose meaningful Unix executable bits.
            return contents(true);
        } catch (InvalidPathException e) {
            return error("InvalidPath", safeMessage(e), 100400);
        } catch (Exception e) {
            return error("IoError", safeMessage(e), 100500);
        }
    }

    @JavascriptInterface
    public String remove(String path) {
        try {
            File target = resolve(path);
            if (target.getCanonicalFile().equals(root.getCanonicalFile())) return error("CriticalDeletionError", "Refusing to remove project root", 100403);
            if (!target.exists()) return "{}";
            if (!deleteRecursively(target)) return error("IoError", "Could not remove path", 100500);
            return "{}";
        } catch (Exception e) {
            return error("InvalidPath", safeMessage(e), 100400);
        }
    }

    @JavascriptInterface
    public String readdir(String path, boolean skipFiles, boolean skipFolders) {
        try {
            File dir = resolve(path);
            if (!dir.isDirectory()) return error("NotDirectory", "Not a directory: " + path, 100315);
            File[] children = dir.listFiles();
            JSONArray result = new JSONArray();
            if (children == null) return result.toString();
            for (File child : children) {
                if (child.isFile() && skipFiles) continue;
                if (child.isDirectory() && skipFolders) continue;
                result.put(child.getName());
            }
            return result.toString();
        } catch (Exception e) {
            return error("InvalidPath", safeMessage(e), 100400);
        }
    }

    @JavascriptInterface
    public String readdirDeep(String path, boolean includeFiles, boolean includeFolders,
                              String ignoreName, String matchPattern, int limit) {
        try {
            JSONArray result = new JSONArray();
            File base = resolve(path);
            if (!base.isDirectory()) return result.toString();
            Pattern matcher = null;
            if (matchPattern != null && !matchPattern.isEmpty()) {
                try { matcher = Pattern.compile(matchPattern); } catch (Exception ignored) { matcher = null; }
            }
            collectDeep(base, base, includeFiles, includeFolders,
                    ignoreName == null ? "" : ignoreName, matcher, limit, result);
            return result.toString();
        } catch (Exception e) {
            return error("InvalidPath", safeMessage(e), 100400);
        }
    }

    @JavascriptInterface
    public String lstat(String path) {
        try {
            File target = resolve(path);
            if (!target.exists()) return error("FileNotFound", "Path not found: " + path, 100314);
            return stat(target).toString();
        } catch (Exception e) {
            return error("InvalidPath", safeMessage(e), 100400);
        }
    }

    /** Alias for lstat matching standard POSIX/Node filesystem semantics. */
    @JavascriptInterface
    public String stat(String path) {
        return lstat(path);
    }

    /** Alias for remove matching standard Node filesystem semantics. */
    @JavascriptInterface
    public String unlink(String path) {
        return remove(path);
    }

    /** Alias for remove matching standard Node filesystem semantics. */
    @JavascriptInterface
    public String rmdir(String path) {
        return remove(path);
    }

    /** Two-argument rename alias for mv matching standard JavaScript filesystem semantics. */
    @JavascriptInterface
    public String rename(String source, String target) {
        return mv(source, target, false);
    }

    @JavascriptInterface
    public String mv(String source, String target, boolean preserveTargetExecute) {
        try {
            File from = resolve(source);
            File to = resolve(target);
            if (!from.exists()) return error("FileNotFound", "Source path not found", 100314);
            if (from.isDirectory() && to.getCanonicalPath().startsWith(from.getCanonicalPath() + File.separator)) {
                return error("InvalidPath", "Cannot move a directory into itself", 100400);
            }
            // The JS bundle uses mv() as an atomic write: it writes to a temp path
            // then moves it over the real destination. Overwrite is therefore allowed.
            if (to.exists() && !deleteRecursively(to)) {
                return error("IoError", "Could not replace existing target", 100500);
            }
            File parent = to.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return error("IoError", "Could not create target parent", 100500);
            boolean done = from.renameTo(to);
            if (!done) {
                done = copyRecursively(from, to);
                if (done) done = deleteRecursively(from);
            }
            if (!done) return error("IoError", "Move failed", 100500);
            return contents(true);
        } catch (Exception e) {
            return error("InvalidPath", safeMessage(e), 100400);
        }
    }

    @JavascriptInterface
    public String copy(String source, String target, boolean preserveTargetExecute) {
        try {
            File from = resolve(source);
            File to = resolve(target);
            if (!from.exists()) return error("FileNotFound", "Source path not found", 100314);
            if (from.isDirectory() && to.getCanonicalPath().startsWith(from.getCanonicalPath() + File.separator)) {
                return error("InvalidPath", "Cannot copy a directory into itself", 100400);
            }
            if (to.exists()) {
                deleteRecursively(to);
            }
            if (!copyRecursively(from, to)) return error("IoError", "Copy failed", 100500);
            return contents(true);
        } catch (Exception e) {
            return error("InvalidPath", safeMessage(e), 100400);
        }
    }

    @JavascriptInterface
    public String readlink(String path) {
        return contents(""); // Links are emulated by the recovered JS filesystem layer.
    }

    @JavascriptInterface
    public String writelink(String path, String target) {
        return error("Unsupported", "Native symbolic links are not supported", 100501);
    }

    @JavascriptInterface
    public String gitHashObject(String type, String path) {
        try {
            File file = resolve(path);
            byte[] data = readBytes(file);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update((type + " " + data.length + "\0").getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return contents(hex.toString());
        } catch (InvalidPathException e) {
            return error("InvalidPath", safeMessage(e), 100400);
        } catch (Exception e) {
            return error("IoError", safeMessage(e), 100500);
        }
    }

    @JavascriptInterface
    public String findInFile(String path, String searchTerm, int maxMatchPerFile, int maxLength,
                             boolean matchCase, boolean useRegEx, boolean onlyWholeWords) {
        try {
            File file = resolve(path);
            if (!file.isFile() || file.length() > MAX_FILE_BYTES) return new JSONArray().toString();
            String text = new String(readBytes(file), StandardCharsets.UTF_8);
            String needle = searchTerm == null ? "" : searchTerm;
            if (needle.isEmpty()) return new JSONArray().toString();
            Pattern pattern;
            if (useRegEx) {
                pattern = Pattern.compile(needle, matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } else {
                String quoted = Pattern.quote(needle);
                if (onlyWholeWords) quoted = "(?<![\\p{L}\\p{N}_])" + quoted + "(?![\\p{L}\\p{N}_])";
                pattern = Pattern.compile(quoted, matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            }
            JSONArray matches = new JSONArray();
            String[] lines = text.split("\\n", -1);
            int count = 0;
            for (int lineNo = 0; lineNo < lines.length; lineNo++) {
                java.util.regex.Matcher m = pattern.matcher(lines[lineNo]);
                while (m.find()) {
                    if (count >= Math.max(1, maxMatchPerFile)) return matches.toString();
                    String line = lines[lineNo];
                    if (maxLength > 0 && line.length() > maxLength) line = line.substring(0, maxLength);
                    JSONObject hit = new JSONObject();
                    hit.put("path", virtualPath(file));
                    hit.put("line", lineNo + 1);
                    hit.put("column", m.start() + 1);
                    hit.put("match", m.group());
                    hit.put("text", line);
                    matches.put(hit);
                    count++;
                }
            }
            return matches.toString();
        } catch (InvalidPathException e) {
            return error("InvalidPath", safeMessage(e), 100400);
        } catch (Exception e) {
            return error("SearchError", safeMessage(e), 100500);
        }
    }

    /**
     * Save an export into private app storage and open Android's share sheet.
     * The bundled editor calls this method for its non-download Share action.
     */
    @JavascriptInterface
    public String saveBase64(String base64Contents, String name) {
        try {
            String safeName = safeExportName(name);
            File target = resolve("/documents/exports/" + safeName);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return error("IoError", "Could not create export directory", 100500);
            }
            byte[] bytes = Base64.decode(base64Contents == null ? "" : base64Contents, Base64.DEFAULT);
            if (bytes.length > MAX_FILE_BYTES) return error("FileTooLarge", "Export exceeds 64 MiB limit", 100413);
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write(bytes);
                out.getFD().sync();
            }
            Uri shareUri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", target.getCanonicalFile());
            launchShareSheet(shareUri, safeName, mimeTypeFor(safeName));
            return contents(virtualPath(target));
        } catch (Exception e) {
            return error("IoError", safeMessage(e), 100500);
        }
    }

    /**
     * Download/export entry point detected by the recovered editor bundle.
     * Android 10+ writes to the public Downloads collection. Older versions use
     * app-specific Downloads to avoid requesting broad legacy storage access.
     */
    @JavascriptInterface
    public String saveBase64AsDownload(String base64Contents, String name) {
        Uri insertedUri = null;
        try {
            String safeName = safeExportName(name);
            byte[] bytes = Base64.decode(base64Contents == null ? "" : base64Contents, Base64.DEFAULT);
            if (bytes.length > MAX_FILE_BYTES) return error("FileTooLarge", "Export exceeds 64 MiB limit", 100413);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(safeName));
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Spck Editor");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                insertedUri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (insertedUri == null) return error("IoError", "Could not create Downloads item", 100500);
                try (OutputStream out = context.getContentResolver().openOutputStream(insertedUri, "w")) {
                    if (out == null) throw new IOException("Could not open Downloads output stream");
                    out.write(bytes);
                    out.flush();
                }
                ContentValues published = new ContentValues();
                published.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(insertedUri, published, null, null);
                return contents(insertedUri.toString());
            }

            File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (downloads == null) downloads = new File(context.getFilesDir(), "Downloads");
            File folder = new File(downloads, "Spck Editor");
            if (!folder.exists() && !folder.mkdirs()) return error("IoError", "Could not create Downloads directory", 100500);
            File target = new File(folder, safeName).getCanonicalFile();
            if (!target.getParentFile().equals(folder.getCanonicalFile())) {
                return error("InvalidPath", "Invalid export filename", 100400);
            }
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write(bytes);
                out.getFD().sync();
            }
            return contents(target.getAbsolutePath());
        } catch (Exception e) {
            if (insertedUri != null) {
                try { context.getContentResolver().delete(insertedUri, null, null); } catch (Exception ignored) { }
            }
            return error("IoError", safeMessage(e), 100500);
        }
    }

    private String getDisplayName(Uri uri) {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            name = last == null ? "Imported Project.zip" : last;
        }
        return name;
    }

    /** Open standalone in-app preview for the project instead of kicking out to Chrome browser. */
    @JavascriptInterface
    public boolean launchWebView(String rawUrl) {
        return launchWebView(rawUrl, null);
    }

    @JavascriptInterface
    public boolean launchWebView(String rawUrl, String optionsJson) {
        try {
            if (rawUrl == null || rawUrl.trim().isEmpty()) return false;
            Intent intent = new Intent(context, PreviewActivity.class);
            intent.putExtra(PreviewActivity.EXTRA_URL, rawUrl);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void launchShareSheet(Uri uri, String name, String mimeType) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mimeType);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, name);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        send.setClipData(android.content.ClipData.newUri(context.getContentResolver(), name, uri));
        Intent chooser = Intent.createChooser(send, "Share " + name);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { context.startActivity(chooser); }
            catch (Exception e) { android.util.Log.e("SpckShare", "Could not open share sheet", e); }
        });
    }

    private String safeExportName(String name) {
        String value = name == null ? "export.bin" : new File(name).getName();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")) value = "export.bin";
        if (value.length() > 180) {
            int dot = value.lastIndexOf('.');
            String ext = dot > 0 && value.length() - dot <= 20 ? value.substring(dot) : "";
            value = value.substring(0, Math.min(160, value.length() - ext.length())) + ext;
        }
        return value;
    }

    private String mimeTypeFor(String name) {
        String ext = "";
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null) return mime;
        if ("zip".equals(ext)) return "application/zip";
        if ("json".equals(ext)) return "application/json";
        if ("md".equals(ext)) return "text/markdown";
        return "application/octet-stream";
    }

    @JavascriptInterface
    public String clipboardText() {
        // On Android 10+ (API 29) ClipboardManager.getPrimaryClip() returns null when
        // called off the main thread from a non-focused app. Since JavascriptInterface
        // methods are always invoked on a background thread we synchronously dispatch
        // to the main thread and block briefly to retrieve the value.
        try {
            final String[] result = {""};  // captured by lambda
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mainHandler.post(() -> {
                try {
                    ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    CharSequence value = manager != null && manager.hasPrimaryClip() && manager.getPrimaryClip() != null
                            ? manager.getPrimaryClip().getItemAt(0).coerceToText(context) : "";
                    result[0] = value == null ? "" : value.toString();
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
            // Wait at most 1 second to avoid blocking the JS thread indefinitely.
            latch.await(1, java.util.concurrent.TimeUnit.SECONDS);
            return result[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public void copyText(String value) {
        try {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("text", value == null ? "" : value));
        } catch (Exception ignored) { }
    }


    public synchronized void close() {
        closeMainServer();
        closePreviewServer();
        // Shut down the executor first so no new callbacks can be posted,
        // then clear the callbacks reference to allow GC.
        projectExecutor.shutdownNow();
        archiveCallbacks.clear();
    }

    private File resolveFromServerDirectory(String path) throws IOException {
        if (path == null || path.trim().isEmpty() || path.equals(".")) {
            return (serverDirectory == null ? root : serverDirectory).getCanonicalFile();
        }
        String value = path.replace('\\', '/');
        if (value.startsWith("/documents/") || value.startsWith("~/") || value.startsWith("/")) {
            return resolve(value);
        }
        File base = serverDirectory == null ? root : serverDirectory;
        String normalized = virtualPath(base) + (virtualPath(base).endsWith("/") ? "" : "/") + value;
        return resolve(normalized);
    }

    private void closeMainServer() {
        if (mainServer != null) {
            mainServer.close();
            mainServer = null;
            mainServerRoot = null;
        }
    }

    private void closePreviewServer() {
        if (previewServer != null) {
            previewServer.close();
            previewServer = null;
        }
        previewRoot = null;
    }

    private void collectDeep(File base, File dir, boolean includeFiles, boolean includeFolders,
                             String ignoreName, Pattern matcher, int limit, JSONArray output) throws IOException, JSONException {
        if (limit >= 0 && output.length() >= limit) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (limit >= 0 && output.length() >= limit) return;
            if (!ignoreName.isEmpty() && child.getName().equals(ignoreName)) continue;
            String relative = relativePath(base, child);
            if (matcher != null && !matcher.matcher(relative).find()) continue;
            if (child.isDirectory()) {
                if (includeFolders) output.put(virtualPath(child));
                collectDeep(base, child, includeFiles, includeFolders, ignoreName, matcher, limit, output);
            } else if (includeFiles) {
                output.put(virtualPath(child));
            }
        }
    }

    private JSONObject entry(File file) throws IOException, JSONException {
        JSONObject json = stat(file);
        json.put("name", file.getName());
        json.put("path", virtualPath(file));
        json.put("isDirectory", file.isDirectory());
        json.put("isFile", file.isFile());
        return json;
    }

    private JSONObject stat(File file) throws JSONException {
        long now = System.currentTimeMillis();
        JSONObject json = new JSONObject();
        json.put("size", file.isFile() ? file.length() : 0);
        json.put("mode", file.isDirectory() ? 16877 : 33188);
        json.put("st_mode", file.isDirectory() ? 16877 : 33188);
        json.put("mtimeMs", file.lastModified() > 0 ? file.lastModified() : now);
        json.put("ctimeMs", file.lastModified() > 0 ? file.lastModified() : now);
        json.put("atimeMs", file.lastModified() > 0 ? file.lastModified() : now);
        json.put("st_mtime", (file.lastModified() > 0 ? file.lastModified() : now) / 1000.0);
        json.put("st_ctime", (file.lastModified() > 0 ? file.lastModified() : now) / 1000.0);
        json.put("st_atime", (file.lastModified() > 0 ? file.lastModified() : now) / 1000.0);
        json.put("isDirectory", file.isDirectory());
        json.put("isFile", file.isFile());
        return json;
    }

    private File resolve(String virtualPath) throws IOException {
        if (virtualPath == null) throw new InvalidPathException("Path is null");
        String value = virtualPath.replace('\\', '/').trim();
        if (value.startsWith("file:") || value.startsWith("content:") || value.contains("://")) {
            throw new InvalidPathException("URI paths are not allowed");
        }
        if (value.startsWith("/documents/")) value = value.substring("/documents/".length());
        else if (value.equals("/documents")) value = "";
        else if (value.startsWith("~/")) value = value.substring(2);
        while (value.startsWith("/")) value = value.substring(1);
        String[] parts = value.split("/");
        StringBuilder normalized = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") || part.indexOf('\0') >= 0 || part.indexOf(':') >= 0) {
                throw new InvalidPathException("Unsafe path segment");
            }
            if (normalized.length() > 0) normalized.append(File.separator);
            normalized.append(part);
        }
        File candidate = normalized.length() == 0 ? root : new File(root, normalized.toString());
        String rootCanonical = root.getCanonicalPath();
        String candidateCanonical = candidate.getCanonicalPath();
        if (!candidateCanonical.equals(rootCanonical) && !candidateCanonical.startsWith(rootCanonical + File.separator)) {
            throw new InvalidPathException("Path escapes the project directory");
        }
        return candidate;
    }

    private String virtualPath(File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Path escapes project directory");
        }
        String rel = filePath.equals(rootPath) ? "" : filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
        return "/documents/" + rel;
    }

    private String relativePath(File base, File file) throws IOException {
        String a = base.getCanonicalPath();
        String b = file.getCanonicalPath();
        if (b.equals(a)) return "";
        return b.substring(a.length() + 1).replace(File.separatorChar, '/');
    }

    private byte[] decodeContents(String value, String encoding) throws Exception {
        String safe = value == null ? "" : value;
        if ("base64".equals(encoding) || "binary".equals(encoding)) {
            return Base64.decode(safe, Base64.DEFAULT);
        }
        if ("utf16be".equals(encoding) || "utf-16be".equals(encoding)) return safe.getBytes(Charset.forName("UTF-16BE"));
        if ("utf16le".equals(encoding) || "utf-16le".equals(encoding)) return safe.getBytes(Charset.forName("UTF-16LE"));
        if ("latin1".equals(encoding) || "ascii".equals(encoding) || "iso-8859-1".equals(encoding)) return safe.getBytes(StandardCharsets.ISO_8859_1);
        return safe.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] withBom(byte[] input, String encoding) {
        byte[] bom;
        if ("utf16le".equals(encoding) || "utf-16le".equals(encoding)) bom = new byte[]{(byte) 0xff, (byte) 0xfe};
        else if ("utf16be".equals(encoding) || "utf-16be".equals(encoding)) bom = new byte[]{(byte) 0xfe, (byte) 0xff};
        else if ("utf8".equals(encoding) || "utf-8".equals(encoding)) bom = new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        else return input;
        byte[] result = new byte[bom.length + input.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(input, 0, result, bom.length, input.length);
        return result;
    }

    private String normalizeEncoding(String encoding) {
        return (encoding == null || encoding.trim().isEmpty()) ? null : encoding.trim().toLowerCase(Locale.ROOT);
    }

    private byte[] readBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_FILE_BYTES) throw new IOException("File exceeds 64 MiB limit");
            }
            return out.toByteArray();
        }
    }

    private boolean copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) return false;
            File[] children = source.listFiles();
            if (children == null) return true;
            for (File child : children) {
                if (!copyRecursively(child, new File(target, child.getName()))) return false;
            }
            return true;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.getFD().sync();
        }
        return true;
    }

    private boolean deleteRecursively(File target) {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        }
        return !target.exists() || target.delete();
    }

    private String contents(Object value) {
        JSONObject result = new JSONObject();
        try { result.put("contents", value); } catch (JSONException ignored) { }
        return result.toString();
    }

    private String error(String code, String message, int status) {
        JSONObject result = new JSONObject();
        try {
            result.put("status", status);
            result.put("code", code);
            result.put("message", message == null ? code : message);
        } catch (JSONException ignored) { }
        return result.toString();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
