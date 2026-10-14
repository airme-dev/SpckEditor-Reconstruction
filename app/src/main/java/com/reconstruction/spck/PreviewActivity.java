package com.reconstruction.spck;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Runs user project previews in a standalone in-app window with a top toolbar
 * containing title, zoom indicator, console toggle (&gt;_), refresh, and
 * options,
 * matching the authentic Spck Editor mobile experience.
 */
public final class PreviewActivity extends Activity {
    public static final String EXTRA_URL = "com.reconstruction.spck.PREVIEW_URL";
    private static final String TAG = "SpckPreviewActivity";

    private static final String PREFS_NAME = "spck_preview_settings";
    private static final String PREF_KEY_FULLSCREEN = "preview_fullscreen";

    private WebView webView;
    private View toolbarView;
    private TextView titleView;
    private TextView subtitleView;
    private ProgressBar progressBar;
    private Uri initialUri;
    private boolean erudaInjected = false;
    private boolean erudaOpen = false;
    private boolean isFullscreen = false;

    // Click phone back button (<) 3 times to restore top bar when in fullscreen
    // mode
    private int backPressCount = 0;
    private long lastBackPressTime = 0;
    private static final long BACK_PRESS_TIMEOUT_MS = 2000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(30, 34, 45));
        getWindow().setNavigationBarColor(Color.rgb(20, 22, 28));

        setContentView(R.layout.activity_preview);

        toolbarView = findViewById(R.id.preview_toolbar);
        titleView = findViewById(R.id.preview_title);
        subtitleView = findViewById(R.id.preview_subtitle);
        progressBar = findViewById(R.id.preview_progress);
        webView = findViewById(R.id.preview_webview);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isFullscreen = prefs.getBoolean(PREF_KEY_FULLSCREEN, false);
        applyFullscreenMode(isFullscreen);

        View btnBack = findViewById(R.id.preview_btn_back);
        View btnConsole = findViewById(R.id.preview_btn_console);
        View btnRefresh = findViewById(R.id.preview_btn_refresh);
        View btnMenu = findViewById(R.id.preview_btn_menu);

        btnBack.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack())
                webView.goBack();
            else
                finish();
        });

        btnRefresh.setOnClickListener(v -> {
            if (webView != null)
                webView.reload();
        });

        btnConsole.setOnClickListener(v -> toggleErudaConsole());

        btnMenu.setOnClickListener(this::showPopupMenu);

        String rawUrl = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        initialUri = parsePreviewUri(rawUrl);
        if (initialUri == null) {
            Toast.makeText(this, "Invalid preview URL", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupWebView();

        boolean restored = false;
        if (savedInstanceState != null) {
            try {
                restored = webView.restoreState(savedInstanceState) != null;
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not restore WebView preview state", e);
            }
        }
        if (!restored) {
            webView.loadUrl(initialUri.toString());
        }
    }

    private void setupWebView() {
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            settings.setSafeBrowsingEnabled(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.trim().isEmpty() && !title.startsWith("http://")
                        && !title.startsWith("https://")) {
                    titleView.setText(title);
                } else if (initialUri != null) {
                    String lastPath = initialUri.getLastPathSegment();
                    titleView.setText(lastPath != null && !lastPath.isEmpty() ? lastPath : "Preview");
                }
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
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
                return routeNavigation(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeNavigation(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectErudaScript();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.w(TAG, "Preview load error: " + error.getDescription());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // In local preview, proceed or cancel depending on domain
                handler.proceed();
            }
        });
    }

    private void applyFullscreenMode(boolean fullscreen) {
        this.isFullscreen = fullscreen;
        if (toolbarView != null) {
            toolbarView.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (progressBar != null && fullscreen) {
            progressBar.setVisibility(View.GONE);
        }
    }

    private void injectErudaScript() {
        if (erudaInjected)
            return;
        try {
            InputStream is = getAssets().open("eruda/eruda.js");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1)
                buffer.write(chunk, 0, n);
            is.close();
            String script = buffer.toString(StandardCharsets.UTF_8.name());
            String init = script + "\n;if (window.eruda) {\n"
                    + "  try {\n"
                    + "    window.eruda.init();\n"
                    + "    if (window.eruda._entryBtn) window.eruda._entryBtn.hide();\n"
                    + "    if (window.eruda.get && window.eruda.get('entryBtn')) window.eruda.get('entryBtn').hide();\n"
                    + "    window.eruda.hide();\n"
                    + "    var css = document.createElement('style');\n"
                    + "    css.innerHTML = '.eruda-entry-btn, [class*=\"eruda-entry-btn\"] { display: none !important; }';\n"
                    + "    (document.head || document.documentElement).appendChild(css);\n"
                    + "  } catch(e) {}\n"
                    + "}\n";
            webView.evaluateJavascript(init, result -> erudaInjected = true);
        } catch (Exception e) {
            Log.e(TAG, "Could not inject Eruda script", e);
        }
    }

    private void toggleErudaConsole() {
        erudaOpen = !erudaOpen;
        String js = erudaOpen ? "if (window.eruda) { window.eruda.show(); }"
                : "if (window.eruda) { window.eruda.hide(); }";
        if (webView != null) {
            webView.evaluateJavascript(js, null);
        }
    }

    private void showPopupMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 10, 0, isFullscreen ? "Exit Fullscreen (Show Top Bar)" : "Fullscreen (Hide Top Bar)");
        popup.getMenu().add(0, 20, 1, erudaOpen ? "Close Developer Console" : "Developer Console (>_)");
        popup.getMenu().add(0, 1, 2, "Open in External Browser");
        popup.getMenu().add(0, 2, 3, "Clear Cache & Reload");
        popup.getMenu().add(0, 3, 4, "Close Preview");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 10:
                    boolean nextFs = !isFullscreen;
                    applyFullscreenMode(nextFs);
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_KEY_FULLSCREEN, nextFs)
                            .apply();
                    if (nextFs) {
                        Toast.makeText(this, "Press again to exit", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Top bar restored", Toast.LENGTH_SHORT).show();
                    }
                    return true;

                case 20:
                    toggleErudaConsole();
                    return true;

                case 1:
                    if (webView != null && webView.getUrl() != null) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(webView.getUrl()));
                            startActivity(intent);
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(this, "No browser installed", Toast.LENGTH_SHORT).show();
                        }
                    }
                    return true;

                case 2:
                    if (webView != null) {
                        webView.clearCache(true);
                        webView.reload();
                    }
                    return true;

                case 3:
                    finish();
                    return true;

                default:
                    return false;
            }
        });
        popup.show();
    }

    private boolean routeNavigation(Uri uri) {
        if (uri == null)
            return true;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("about".equals(scheme))
            return false;
        if ("file".equals(scheme) || "javascript".equals(scheme))
            return false;

        // Keep loopback and same-site URLs inside the preview WebView
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
            if (loopback || isSameOrigin(uri)) {
                return false; // let WebView handle it internally
            }
            // For external websites clicked inside preview, open in browser
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return false;
    }

    private boolean isSameOrigin(Uri uri) {
        return initialUri != null
                && uri.getScheme() != null
                && uri.getScheme().equalsIgnoreCase(initialUri.getScheme())
                && uri.getHost() != null
                && uri.getHost().equalsIgnoreCase(initialUri.getHost())
                && uri.getPort() == initialUri.getPort();
    }

    private Uri parsePreviewUri(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return null;
        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme) || "file".equals(scheme)) {
                return uri;
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    protected void onPause() {
        if (webView != null)
            webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null)
            webView.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null)
            webView.saveState(outState);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (erudaOpen) {
            toggleErudaConsole();
            return;
        }
        if (isFullscreen) {
            long now = System.currentTimeMillis();
            if (now - lastBackPressTime > BACK_PRESS_TIMEOUT_MS) {
                backPressCount = 0;
            }
            backPressCount++;
            lastBackPressTime = now;

            if (backPressCount == 1) {
                Toast.makeText(this, "Press again to exit", Toast.LENGTH_SHORT).show();
                return;
            } else if (backPressCount == 2) {
                Toast.makeText(this, "Press again to exit", Toast.LENGTH_SHORT).show();
                return;
            } else {
                backPressCount = 0;
                applyFullscreenMode(false);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_KEY_FULLSCREEN, false)
                        .apply();
                Toast.makeText(this, "Top bar restored", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
