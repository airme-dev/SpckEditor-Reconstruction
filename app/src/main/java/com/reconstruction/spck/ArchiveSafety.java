package com.reconstruction.spck;

import java.io.IOException;
import java.util.Locale;

/** Pure-Java path policy shared by ZIP import/export code and offline unit tests. */
final class ArchiveSafety {
    private ArchiveSafety() { }

    /**
     * Normalize an untrusted ZIP entry name into a relative slash-separated path.
     * Rejects absolute paths, Windows separators/drive prefixes, traversal, NULs,
     * colons, empty paths, and dot components. A final slash for directories is okay.
     */
    static String normalizeZipEntryName(String rawName) throws IOException {
        if (rawName == null || rawName.isEmpty()) throw new IOException("Archive contains an empty entry name");
        if (rawName.length() > 4096) throw new IOException("Archive entry name is too long");
        if (rawName.startsWith("/") || rawName.startsWith("\\") || rawName.indexOf('\\') >= 0
                || rawName.indexOf('\0') >= 0 || rawName.indexOf(':') >= 0) {
            throw new IOException("Archive contains an unsafe path");
        }

        String name = rawName.endsWith("/") ? rawName.substring(0, rawName.length() - 1) : rawName;
        if (name.isEmpty()) throw new IOException("Archive contains an empty path");
        String[] parts = name.split("/", -1);
        StringBuilder result = new StringBuilder(name.length());
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("Archive contains a traversal or empty path component");
            }
            if (part.length() > 255) throw new IOException("Archive path component is too long");
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    /** Produces a conservative project folder name from an untrusted archive filename. */
    static String sanitizeProjectName(String rawName) {
        String value = rawName == null ? "" : rawName;
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        value = value.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        value = value.replaceAll("[. ]+$", "");
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) value = "Imported Project";
        if (value.startsWith(".")) value = "Imported " + value.replaceFirst("^\\.+", "");
        if (value.length() > 80) value = value.substring(0, 80).trim();
        if (value.isEmpty()) value = "Imported Project";
        return value;
    }

    static boolean isSafeImmediateProjectName(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 100) return false;
        if (value.startsWith(".") || value.contains("/") || value.contains("\\")
                || value.contains(":") || value.contains("\0") || "..".equals(value)) return false;
        return !"exports".equals(value.toLowerCase(Locale.ROOT));
    }
}
