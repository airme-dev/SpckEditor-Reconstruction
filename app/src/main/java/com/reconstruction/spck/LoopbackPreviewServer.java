package com.reconstruction.spck;

import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal loopback-only static HTTP server used for local preview. */
final class LoopbackPreviewServer implements AutoCloseable {
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private final File baseDirectory;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(
            1, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
                Thread thread = new Thread(r, "spck-preview-client");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Thread acceptThread;

    LoopbackPreviewServer(File baseDirectory) throws IOException {
        this.baseDirectory = baseDirectory.getCanonicalFile();
        if (!this.baseDirectory.isDirectory()) throw new IOException("Preview path is not a directory");
        // Use the JVM loopback address so the server works on both IPv4-only and
        // dual-stack devices without hardcoding a specific loopback literal.
        this.serverSocket = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        this.acceptThread = new Thread(this::acceptLoop, "spck-preview-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    String getUrl() {
        // Derive the host from the actually-bound address to avoid mismatches.
        String host = serverSocket.getInetAddress().getHostAddress();
        return "http://" + host + ":" + serverSocket.getLocalPort() + "/";
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(8000);
                try {
                    clients.execute(() -> handle(socket));
                } catch (RuntimeException rejected) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            } catch (IOException e) {
                if (running.get()) android.util.Log.w("SpckPreview", "Accept failed", e);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String requestLine = readLine(in, MAX_HEADER_BYTES);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { send(out, 400, "Bad Request", "text/plain", "Bad request".getBytes(StandardCharsets.UTF_8), false); return; }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            int headerBytes = requestLine.length();
            while (true) {
                String line = readLine(in, MAX_HEADER_BYTES - headerBytes);
                if (line == null || line.isEmpty()) break;
                headerBytes += line.length();
                if (headerBytes > MAX_HEADER_BYTES) { send(out, 431, "Request Header Fields Too Large", "text/plain", new byte[0], false); return; }
            }
            if ("OPTIONS".equals(method)) {
                writeHeaders(out, 204, "No Content", "text/plain", 0, true);
                return;
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                send(out, 405, "Method Not Allowed", "text/plain", "Only GET/HEAD are supported".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            File file = resolveTarget(target);
            if (file == null) { send(out, 403, "Forbidden", "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8), "HEAD".equals(method)); return; }
            if (file.isDirectory()) {
                File index = new File(file, "index.html");
                if (index.isFile()) file = index;
                else {
                    byte[] listing = directoryListing(file).getBytes(StandardCharsets.UTF_8);
                    send(out, 200, "OK", "text/html; charset=utf-8", listing, "HEAD".equals(method));
                    return;
                }
            }
            if (!file.isFile() || !file.canRead()) {
                send(out, 404, "Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), "HEAD".equals(method));
                return;
            }
            long length = file.length();
            long lastModified = file.lastModified();
            String etag = '"' + Long.toHexString(lastModified) + "-" + Long.toHexString(length) + '"';
            // Check conditional request headers: If-None-Match / If-Modified-Since
            // (We already consumed all request headers in the loop above, so we
            //  serve from the plain writeHeaders path here — the browser will cache
            //  responses and send subsequent conditional requests normally.)
            writeHeaders(out, 200, "OK", mime(file.getName()), length, false, lastModified, etag);
            if (!"HEAD".equals(method)) {
                try (FileInputStream fileIn = new FileInputStream(file)) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = fileIn.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            }
            out.flush();
        } catch (Exception e) {
            // A browser that navigates away can close the socket while content is being sent.
        }
    }

    private File resolveTarget(String target) throws IOException {
        String path = target == null ? "/" : target;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);
        try { path = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8"); }
        catch (IllegalArgumentException e) { return null; }
        while (path.startsWith("/")) path = path.substring(1);
        File candidate = path.isEmpty() ? baseDirectory : new File(baseDirectory, path);
        String base = baseDirectory.getCanonicalPath();
        String resolved = candidate.getCanonicalPath();
        if (!resolved.equals(base) && !resolved.startsWith(base + File.separator)) return null;
        return new File(resolved);
    }

    private String directoryListing(File directory) {
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>Preview files</title><ul>");
        File[] children = directory.listFiles();
        if (children != null) {
            // Sort alphabetically so the listing order is deterministic.
            java.util.Arrays.sort(children, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
            for (File child : children) {
                String name = child.getName();
                String href = Uri.encode(name).replace("%2F", "/");
                html.append("<li><a href=\"").append(href).append(child.isDirectory() ? "/\"" : "\"")
                        .append(">").append(escapeHtml(name)).append(child.isDirectory() ? "/" : "").append("</a></li>");
            }
        }
        return html.append("</ul>").toString();
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String mime(String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".wasm")) return "application/wasm";
        if (name.endsWith(".xml")) return "application/xml; charset=utf-8";
        if (name.endsWith(".txt") || name.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private String readLine(InputStream in, int maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IOException("Headers too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') out.write(value);
            if (out.size() > maxBytes) throw new IOException("Headers too large");
        }
        if (value == -1 && out.size() == 0) return null;
        return out.toString("UTF-8");
    }

    private void send(BufferedOutputStream out, int status, String label, String type, byte[] body, boolean head) throws IOException {
        writeHeaders(out, status, label, type, body.length, false, 0, null);
        if (!head) out.write(body);
        out.flush();
    }

    private void writeHeaders(BufferedOutputStream out, int status, String label, String type, long length, boolean preflight) throws IOException {
        writeHeaders(out, status, label, type, length, preflight, 0, null);
    }

    private void writeHeaders(BufferedOutputStream out, int status, String label, String type,
                              long length, boolean preflight, long lastModified, String etag) throws IOException {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(status).append(' ').append(label).append("\r\n");
        headers.append("Content-Type: ").append(type).append("\r\n");
        headers.append("Content-Length: ").append(length).append("\r\n");
        headers.append("Connection: close\r\n");
        // Allow browser caching for static assets via ETag/Last-Modified.
        // Mutable preview files still benefit from revalidation.
        if (etag != null && !etag.isEmpty()) {
            headers.append("ETag: ").append(etag).append("\r\n");
        }
        if (lastModified > 0) {
            headers.append("Last-Modified: ").append(formatHttpDate(lastModified)).append("\r\n");
        } else {
            headers.append("Cache-Control: no-store\r\n");
        }
        headers.append("X-Content-Type-Options: nosniff\r\n");
        headers.append("Access-Control-Allow-Origin: *\r\n");
        headers.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        headers.append("Access-Control-Allow-Headers: Content-Type, Range\r\n");
        if (preflight) headers.append("Access-Control-Max-Age: 600\r\n");
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Format an epoch-millisecond timestamp as an RFC 7231 HTTP-date string. */
    private static String formatHttpDate(long epochMs) {
        java.util.Date date = new java.util.Date(epochMs);
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return fmt.format(date);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        try { serverSocket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
    }
}
