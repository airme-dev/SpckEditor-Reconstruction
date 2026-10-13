package com.reconstruction.spck;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.view.WindowCompat;
import org.json.JSONObject;
import android.net.http.SslError;
import android.widget.Toast;

public final class MainActivity extends Activity implements NativeFileBridge.ArchiveCallbacks {
    private static final String TAG = "SpckReconstruction";
    private static final int REQUEST_FILE_CHOOSER = 4102;
    private static final int REQUEST_IMPORT_ARCHIVE = 4103;
    private static final int REQUEST_EXPORT_ARCHIVE = 4104;
    private static final String START_URL = "file:///android_asset/index.html";
    private static volatile boolean forceFreshStartAfterRendererExit;
    private static volatile long lastRendererRecoveryElapsedMs;
    private static final String PROJECT_ARCHIVE_TOOLS_SCRIPT = """
            (function(){
              if (window.__spckZipToolsInstalled) return;
              window.__spckZipToolsInstalled = true;
              const root = document.createElement('div');
              root.id = '__spck_zip_tools';
              root.style.cssText = 'position:fixed;right:12px;bottom:12px;z-index:2147483647;font:14px/1.35 system-ui,-apple-system,sans-serif;color:#f7f7f8;max-width:calc(100vw - 24px);';
              root.innerHTML = '<button type="button" aria-label="Project tools" id="__spck_zip_toggle" style="border:1px solid #777;background:#22252d;color:#fff;border-radius:999px;padding:10px 14px;box-shadow:0 3px 14px #0006;font-weight:700">Tools</button>' +
                '<section id="__spck_zip_panel" style="display:none;margin-top:8px;padding:12px;background:#20232b;border:1px solid #545966;border-radius:12px;box-shadow:0 5px 22px #0008;width:300px;max-height:calc(100vh - 85px);overflow:auto;box-sizing:border-box">' +
                '<div style="font-weight:700;margin-bottom:8px">Project archive tools</div>' +
                '<div id="__spck_workspace_label" style="font-size:11px;color:#aeb4c2;margin:4px 0 8px">Workspace selection is saved on this device.</div>' +
                '<button type="button" id="__spck_zip_import" style="display:block;width:100%;padding:9px;margin:5px 0;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Import ZIP…</button>' +
                '<button type="button" id="__spck_zip_export" style="display:block;width:100%;padding:9px;margin:5px 0;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Export project ZIP…</button>' +
                '<div id="__spck_zip_export_row" style="display:none;margin-top:8px"><select id="__spck_zip_projects" aria-label="Project to export" style="width:100%;padding:8px;background:#17191f;color:white;border:1px solid #606777;border-radius:7px"></select><button type="button" id="__spck_zip_save" style="display:block;width:100%;padding:9px;margin-top:6px;background:#315d9b;color:white;border:0;border-radius:7px">Save ZIP…</button></div>' +
                '<hr style="margin:12px 0;border:0;border-top:1px solid #545966"><div style="font-weight:700;margin:6px 0">Local Git (device only)</div>' +
                '<select id="__spck_git_projects" aria-label="Git project" style="width:100%;padding:8px;background:#17191f;color:white;border:1px solid #606777;border-radius:7px;margin:4px 0"></select>' +
                '<div style="display:grid;grid-template-columns:1fr 1fr;gap:5px;margin-top:5px"><button type="button" id="__spck_git_init" style="padding:8px;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Init repo</button><button type="button" id="__spck_git_status_btn" style="padding:8px;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Status</button><button type="button" id="__spck_git_stage" style="padding:8px;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Stage all</button><button type="button" id="__spck_git_branches_btn" style="padding:8px;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Branches</button><button type="button" id="__spck_git_log" style="padding:8px;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Recent commits</button></div>' +
                '<input id="__spck_git_message" aria-label="Commit message" placeholder="Commit message" style="width:100%;box-sizing:border-box;padding:8px;margin-top:7px;background:#17191f;color:white;border:1px solid #606777;border-radius:7px"><button type="button" id="__spck_git_commit" style="display:block;width:100%;padding:9px;margin-top:5px;background:#315d9b;color:white;border:0;border-radius:7px">Commit staged changes</button>' +
                '<select id="__spck_git_branches" aria-label="Local branch" style="width:100%;padding:8px;background:#17191f;color:white;border:1px solid #606777;border-radius:7px;margin-top:7px"></select><button type="button" id="__spck_git_checkout" style="display:block;width:100%;padding:8px;margin-top:5px;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Switch branch</button>' +
                '<input id="__spck_git_new_branch" aria-label="New branch name" placeholder="New branch name" style="width:100%;box-sizing:border-box;padding:8px;margin-top:7px;background:#17191f;color:white;border:1px solid #606777;border-radius:7px"><button type="button" id="__spck_git_create_branch" style="display:block;width:100%;padding:8px;margin-top:5px;background:#343946;color:white;border:1px solid #606777;border-radius:7px">Create branch</button>' +
                '<pre id="__spck_git_result" role="status" aria-live="polite" style="white-space:pre-wrap;overflow-wrap:anywhere;max-height:180px;overflow:auto;background:#14161c;color:#d6d8df;padding:8px;border-radius:7px;font:11px/1.4 ui-monospace,monospace;margin-top:8px">Choose a project to start.</pre>' +
                '<hr style="margin:12px 0;border:0;border-top:1px solid #545966"><div style="font-weight:700;margin:6px 0">Project console</div><div style="font-size:11px;color:#aeb4c2">Restricted built-ins only; no arbitrary shell or process execution.</div>' +
                '<div style="display:flex;gap:5px;margin-top:6px"><input id="__spck_console_command" aria-label="Project console command" placeholder="help, ls, cat file…" style="min-width:0;flex:1;padding:8px;background:#17191f;color:white;border:1px solid #606777;border-radius:7px"><button type="button" id="__spck_console_run" style="padding:8px;background:#315d9b;color:white;border:0;border-radius:7px">Run</button></div>' +
                '<pre id="__spck_console_output" role="status" aria-live="polite" style="white-space:pre-wrap;overflow-wrap:anywhere;max-height:150px;overflow:auto;background:#14161c;color:#d6d8df;padding:8px;border-radius:7px;font:11px/1.4 ui-monospace,monospace;margin-top:6px">Type help and tap Run.</pre>' +
                '<div id="__spck_zip_status" role="status" aria-live="polite" style="margin-top:8px;font-size:12px;color:#d6d8df;overflow-wrap:anywhere"></div>' +
                '<button type="button" id="__spck_zip_reload" style="display:none;width:100%;padding:8px;margin-top:8px;background:#484e5d;color:white;border:1px solid #687184;border-radius:7px">Reload editor</button>' +
                '</section>';
              document.body.appendChild(root);
              const panel = root.querySelector('#__spck_zip_panel');
              const status = root.querySelector('#__spck_zip_status');
              const reload = root.querySelector('#__spck_zip_reload');
              const exportRow = root.querySelector('#__spck_zip_export_row');
              const select = root.querySelector('#__spck_zip_projects');
              const gitProjectSelect = root.querySelector('#__spck_git_projects');
              const gitBranchSelect = root.querySelector('#__spck_git_branches');
              const gitResult = root.querySelector('#__spck_git_result');
              const workspaceLabel = root.querySelector('#__spck_workspace_label');
              const consoleCommand = root.querySelector('#__spck_console_command');
              const consoleOutput = root.querySelector('#__spck_console_output');
              function readWorkspaceState() {
                try { return JSON.parse(window.Android.getWorkspaceState() || '{}'); }
                catch (_) { return {lastUsedProject:'', recentProjects:[]}; }
              }
              function rememberProject(name) {
                if (!name) return;
                try {
                  if (window.Android.recordProjectUse(name)) {
                    const state = readWorkspaceState();
                    workspaceLabel.textContent = 'Last used tool project: ' + String(state.lastUsedProject || name) +
                      (Array.isArray(state.recentProjects) && state.recentProjects.length ? ' · ' + state.recentProjects.length + ' recent' : '');
                  }
                } catch (_) { workspaceLabel.textContent = 'Workspace selection could not be saved.'; }
              }
              function refreshGitProjects(preferred) {
                const projects = JSON.parse(window.Android.listExportableProjects());
                const workspace = readWorkspaceState();
                while (gitProjectSelect.firstChild) gitProjectSelect.removeChild(gitProjectSelect.firstChild);
                projects.forEach(function(project){ const option = document.createElement('option'); option.value = project.name; option.textContent = project.name; gitProjectSelect.appendChild(option); });
                const wanted = preferred || workspace.lastUsedProject;
                if (wanted) gitProjectSelect.value = wanted;
                if (!gitProjectSelect.value && projects.length) gitProjectSelect.selectedIndex = 0;
                if (workspaceLabel) workspaceLabel.textContent = workspace.lastUsedProject
                  ? 'Last used tool project: ' + workspace.lastUsedProject + (Array.isArray(workspace.recentProjects) ? ' · ' + workspace.recentProjects.length + ' recent' : '')
                  : 'Project selection is saved on this device for the ZIP/Git/console tools.';
                if (!projects.length) gitResult.textContent = 'No project folders. Create or import a project first.';
              }
              function runGit(operation, argument) {
                const project = gitProjectSelect.value;
                if (!project) { gitResult.textContent = 'Select a project first.'; return; }
                rememberProject(project);
                gitResult.textContent = 'Running ' + operation + '…';
                try { window.Android.executeLocalGit(operation, project, argument || ''); }
                catch (e) { gitResult.textContent = 'Could not start Git operation: ' + String(e); }
              }
              try { panel.style.display = localStorage.getItem('__spck_tools_panel_open') === '1' ? 'block' : 'none'; } catch (_) {}
              root.querySelector('#__spck_zip_toggle').addEventListener('click', function(){ panel.style.display = panel.style.display === 'none' ? 'block' : 'none'; try { localStorage.setItem('__spck_tools_panel_open', panel.style.display === 'block' ? '1' : '0'); } catch (_) {} });
              root.querySelector('#__spck_git_init').addEventListener('click', function(){ runGit('init', ''); });
              root.querySelector('#__spck_git_status_btn').addEventListener('click', function(){ runGit('status', ''); });
              root.querySelector('#__spck_git_stage').addEventListener('click', function(){ runGit('stageAll', ''); });
              root.querySelector('#__spck_git_branches_btn').addEventListener('click', function(){ runGit('branches', ''); });
              root.querySelector('#__spck_git_commit').addEventListener('click', function(){ runGit('commit', root.querySelector('#__spck_git_message').value); });
              root.querySelector('#__spck_git_log').addEventListener('click', function(){ runGit('log', ''); });
              root.querySelector('#__spck_git_create_branch').addEventListener('click', function(){ runGit('createBranch', root.querySelector('#__spck_git_new_branch').value); });
              root.querySelector('#__spck_git_checkout').addEventListener('click', function(){ runGit('checkout', gitBranchSelect.value); });
              function runConsole() {
                const command = consoleCommand.value;
                const project = gitProjectSelect.value;
                if (!project) { consoleOutput.textContent = 'Select a project folder first.'; return; }
                rememberProject(project);
                consoleOutput.textContent = '$ ' + command + '\\nRunning…';
                try { window.Android.executeProjectCommand(project, command); }
                catch (e) { consoleOutput.textContent = 'Could not start command: ' + String(e); }
              }
              root.querySelector('#__spck_console_run').addEventListener('click', runConsole);
              consoleCommand.addEventListener('keydown', function(event){ if (event.key === 'Enter') { event.preventDefault(); runConsole(); } });
              gitProjectSelect.addEventListener('change', function(){ gitBranchSelect.replaceChildren(); rememberProject(gitProjectSelect.value); gitResult.textContent = 'Project selected: ' + gitProjectSelect.value; consoleOutput.textContent = 'Project selected: ' + gitProjectSelect.value; });
              select.addEventListener('change', function(){ rememberProject(select.value); });
              try { refreshGitProjects(); } catch (e) { gitResult.textContent = 'Could not list projects: ' + String(e); }
              reload.addEventListener('click', function(){ window.location.reload(); });
              root.querySelector('#__spck_zip_import').addEventListener('click', function(){
                try { status.textContent = 'Choose a ZIP archive in the Android picker.'; window.Android.requestImportProjectArchive(); }
                catch (e) { status.textContent = 'Could not start import: ' + String(e); }
              });
              root.querySelector('#__spck_zip_export').addEventListener('click', function(){
                try {
                  const projects = JSON.parse(window.Android.listExportableProjects());
                  if (!Array.isArray(projects) || projects.length === 0) { status.textContent = 'No project folders are available to export.'; exportRow.style.display = 'none'; return; }
                  while (select.firstChild) select.removeChild(select.firstChild);
                  projects.forEach(function(project){ const option = document.createElement('option'); option.value = project.name; option.textContent = project.name; select.appendChild(option); });
                  const workspace = readWorkspaceState(); if (workspace.lastUsedProject) select.value = workspace.lastUsedProject;
                  if (select.value) rememberProject(select.value);
                  exportRow.style.display = 'block'; status.textContent = 'Choose a project folder, then select where to save its ZIP.';
                } catch (e) { status.textContent = 'Could not list projects: ' + String(e); }
              });
              root.querySelector('#__spck_zip_save').addEventListener('click', function(){
                if (!select.value) { status.textContent = 'Select a project first.'; return; }
                try { rememberProject(select.value); status.textContent = 'Choose a save location.'; window.Android.requestExportProjectArchive(select.value); }
                catch (e) { status.textContent = 'Could not start export: ' + String(e); }
              });
              window.__spckZipTools = { setStatus: function(message, success) {
                const text = String(message || '');
                status.textContent = text;
                status.style.color = success ? '#a7e8b0' : '#f0c2a8';
                reload.style.display = success && text.indexOf('Imported ') === 0 ? 'block' : 'none';
              } };
              window.__spckGitTools = { setResult: function(operation, success, resultJson) {
                let data;
                try { data = JSON.parse(String(resultJson || '{}')); } catch (_) { data = {raw: String(resultJson || '')}; }
                gitResult.textContent = JSON.stringify(data, null, 2);
                gitResult.style.color = success ? '#a7e8b0' : '#f0c2a8';
                if (success && operation === 'branches' && Array.isArray(data.branches)) {
                  gitBranchSelect.replaceChildren();
                  data.branches.forEach(function(branch){ const option = document.createElement('option'); option.value = branch.name; option.textContent = (branch.current ? '* ' : '') + branch.name; gitBranchSelect.appendChild(option); });
                }
              } };
              window.__spckConsoleTools = { setResult: function(success, resultJson) {
                let data;
                try { data = JSON.parse(String(resultJson || '{}')); } catch (_) { data = {output: String(resultJson || '')}; }
                consoleOutput.textContent = (data.command ? '$ ' + data.command + '\\n' : '') + String(data.output || (success ? '' : 'Command failed'));
                consoleOutput.style.color = success ? '#d6d8df' : '#f0c2a8';
                consoleOutput.scrollTop = consoleOutput.scrollHeight;
                if (data.command === 'clear' && success) consoleOutput.textContent = '';
              } };
            })();
            """;

    private WebView webView;
    private NativeFileBridge nativeBridge;
    private ValueCallback<Uri[]> pendingFileCallback;
    private String pendingExportProject;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(35, 41, 60));
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 11));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: let the editor window extend edge-to-edge and handle insets itself
            // so the on-screen keyboard correctly resizes the content area.
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        } else {
            //noinspection deprecation
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR ^ View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(35, 41, 60));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);       // Required for bundled editor assets and workers.
        settings.setAllowContentAccess(true);    // Used only for user-selected content URIs.
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);

        // The JS bridge is deliberately restricted to app-private files and rejects traversal.
        nativeBridge = new NativeFileBridge(this, this);
        webView.addJavascriptInterface(nativeBridge, "Android");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = filePathCallback;
                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (RuntimeException e) {
                    pendingFileCallback = null;
                    filePathCallback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "No file picker available", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri requested = request.getUrl();
                // The Android JavaScript interface is injected into every frame in a WebView.
                // Never render user project HTML in this bridge-enabled editor WebView. Open
                // loopback preview documents in PreviewActivity, which has no native bridge.
                if (isLoopbackPreviewUrl(requested)) {
                    openIsolatedPreview(requested);
                    return true;
                }
                if (!request.isForMainFrame()) {
                    String childScheme = requested.getScheme() == null ? "" : requested.getScheme().toLowerCase(java.util.Locale.ROOT);
                    if ("http".equals(childScheme) || "https".equals(childScheme)) return true;
                }
                return routeNavigation(requested);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri requested = Uri.parse(url);
                if (isLoopbackPreviewUrl(requested)) {
                    openIsolatedPreview(requested);
                    return true;
                }
                return routeNavigation(requested);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return MainActivity.this.handleRendererGone(view, detail);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri requested = request.getUrl();
                String path = requested != null ? requested.getPath() : null;
                if (path != null) {
                    String assetPath = null;
                    if (path.startsWith("/android_asset/")) {
                        assetPath = path.substring("/android_asset/".length());
                    } else if (path.startsWith("/assets/")) {
                        assetPath = path.substring(1);
                    } else if (path.startsWith("assets/")) {
                        assetPath = path;
                    }
                    if (assetPath != null) {
                        try {
                            java.io.InputStream stream = getAssets().open(assetPath);
                            String mimeType = getAssetMimeType(assetPath);
                            WebResourceResponse response = new WebResourceResponse(mimeType, "UTF-8", stream);
                            // Allow the Worker polyfill's synchronous XHR to read worker scripts.
                            java.util.Map<String, String> headers = new java.util.HashMap<>();
                            headers.put("Access-Control-Allow-Origin", "*");
                            headers.put("Cache-Control", "no-cache");
                            response.setResponseHeaders(headers);
                            return response;
                        } catch (java.io.IOException ignored) { }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isTrustedEditorUrl(url)) {
                    view.evaluateJavascript(PROJECT_ARCHIVE_TOOLS_SCRIPT, null);
                    // Validate that the Android Worker polyfill was installed before the bundles ran.
                    view.evaluateJavascript(
                        "(function(){ " +
                        "  var ok = typeof Worker !== 'undefined' && Worker.name !== 'Worker' || " +
                        "           (typeof Worker !== 'undefined' && Worker.toString().indexOf('FakeWorkerGate') !== -1); " +
                        "  console.log('[SpckPatch] Worker polyfill active: ' + ok + ' | Worker=' + (typeof Worker !== 'undefined' ? Worker.name : 'none')); " +
                        "})()",
                        null
                    );
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.e(TAG, "Main page load failed: " + error.getDescription());
                    Toast.makeText(MainActivity.this, "Editor failed to load. See Logcat for details.", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Never bypass certificate validation.
                handler.cancel();
            }
        });
        setContentView(webView);
        boolean forceFresh = forceFreshStartAfterRendererExit;
        forceFreshStartAfterRendererExit = false;
        boolean restored = false;
        if (!forceFresh && savedInstanceState != null) {
            try {
                restored = webView.restoreState(savedInstanceState) != null && isTrustedEditorUrl(webView.getUrl());
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not restore WebView history; loading the bundled editor", e);
                restored = false;
            }
        }
        if (!restored) {
            webView.stopLoading();
            webView.loadUrl(START_URL);
        }
    }

    private boolean isTrustedEditorUrl(String url) {
        return url != null && (START_URL.equals(url) || url.startsWith(START_URL + "?")
                || url.startsWith(START_URL + "#"));
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    private boolean handleRendererGone(WebView view, RenderProcessGoneDetail detail) {
        if (view != webView) {
            view.destroy();
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        boolean recentlyRecovered = lastRendererRecoveryElapsedMs > 0
                && now - lastRendererRecoveryElapsedMs < 15_000L;
        Log.e(TAG, "WebView renderer exited; didCrash=" + (detail != null && detail.didCrash()));
        releaseWebView(view);
        if (recentlyRecovered) {
            Toast.makeText(this, "Editor renderer crashed repeatedly. Reopen the app after checking device memory.", Toast.LENGTH_LONG).show();
            finish();
            return true;
        }
        lastRendererRecoveryElapsedMs = now;
        forceFreshStartAfterRendererExit = true;
        recreate();
        return true;
    }

    private void releaseWebView(WebView target) {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (target != null) {
            try { target.stopLoading(); } catch (RuntimeException ignored) { }
            target.setWebChromeClient(null);
            target.setWebViewClient(null);
            target.removeJavascriptInterface("Android");
            target.destroy();
            if (webView == target) webView = null;
        }
        if (nativeBridge != null) {
            nativeBridge.close();
            nativeBridge = null;
        }
    }

    private boolean routeNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String url = uri.toString();
        if ("about".equals(scheme) && "about:blank".equals(url)) return false;
        if ("file".equals(scheme) && url.startsWith("file:///android_asset/")) return false;
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
                openIsolatedPreview(uri);
                return true;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        // Block arbitrary file:, javascript:, intent:, and unknown navigation schemes.
        return true;
    }


    private boolean isLoopbackPreviewUrl(Uri uri) {
        if (uri == null || uri.getUserInfo() != null || uri.getPort() < 1 || uri.getPort() > 65535) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return "http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host));
    }

    private void openIsolatedPreview(Uri uri) {
        if (!isLoopbackPreviewUrl(uri)) return;
        try {
            Intent intent = new Intent(this, PreviewActivity.class);
            intent.putExtra(PreviewActivity.EXTRA_URL, uri.toString());
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not open isolated preview", e);
            Toast.makeText(this, "Could not open project preview", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_ARCHIVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && nativeBridge != null) {
                nativeBridge.importProjectArchiveAsync(data.getData());
            } else if (nativeBridge != null) {
                onArchiveOperationFinished(false, "Project import was cancelled.");
            }
            return;
        }
        if (requestCode == REQUEST_EXPORT_ARCHIVE) {
            String projectName = pendingExportProject;
            pendingExportProject = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && nativeBridge != null && projectName != null) {
                nativeBridge.exportProjectArchiveAsync(data.getData(), projectName);
            } else if (nativeBridge != null) {
                onArchiveOperationFinished(false, "Project export was cancelled.");
            }
            return;
        }
        if (requestCode == REQUEST_FILE_CHOOSER && pendingFileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
        }
    }

    @Override
    public void requestImportArchive() {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_IMPORT_ARCHIVE);
            } catch (RuntimeException e) {
                onArchiveOperationFinished(false, "No compatible document picker is available.");
            }
        });
    }

    @Override
    public void requestExportArchive(String projectName) {
        runOnUiThread(() -> {
            if (!ArchiveSafety.isSafeImmediateProjectName(projectName)) {
                onArchiveOperationFinished(false, "Invalid project folder name.");
                return;
            }
            pendingExportProject = projectName;
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_TITLE, projectName + ".zip");
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_EXPORT_ARCHIVE);
            } catch (RuntimeException e) {
                pendingExportProject = null;
                onArchiveOperationFinished(false, "No compatible save dialog is available.");
            }
        });
    }

    @Override
    public void onArchiveOperationFinished(boolean success, String message) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, message, success ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            if (webView != null) {
                String quoted = JSONObject.quote(message == null ? "" : message);
                webView.evaluateJavascript("if(window.__spckZipTools){window.__spckZipTools.setStatus(" + quoted + "," + success + ");}", null);
            }
        });
    }

    @Override
    public void onGitOperationFinished(String operation, boolean success, String resultJson) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String safeOperation = JSONObject.quote(operation == null ? "" : operation);
            String safeResult = JSONObject.quote(resultJson == null ? "{}" : resultJson);
            webView.evaluateJavascript("if(window.__spckGitTools){window.__spckGitTools.setResult(" + safeOperation + "," + success + "," + safeResult + ");}", null);
        });
    }

    @Override
    public void onProjectCommandFinished(boolean success, String resultJson) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String safeResult = JSONObject.quote(resultJson == null ? "{}" : resultJson);
            webView.evaluateJavascript("if(window.__spckConsoleTools){window.__spckConsoleTools.setResult(" + success + "," + safeResult + ");}", null);
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // super must be called FIRST to avoid Bundle corruption on some OEM variants.
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        WebView current = webView;
        if (current != null) {
            try { current.loadUrl("about:blank"); } catch (RuntimeException ignored) { }
        }
        releaseWebView(current);
        super.onDestroy();
    }

    private static String getAssetMimeType(String path) {
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}
