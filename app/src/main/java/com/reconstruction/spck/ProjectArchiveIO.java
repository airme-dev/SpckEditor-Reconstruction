package com.reconstruction.spck;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Bounded ZIP import/export routines with no Android dependency, suitable for JVM testing. */
final class ProjectArchiveIO {
    static final long MAX_ARCHIVE_INPUT_BYTES = 320L * 1024L * 1024L;
    static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    static final long MAX_ARCHIVE_EXPANDED_BYTES = 256L * 1024L * 1024L;
    static final long MAX_ARCHIVE_EXPORT_BYTES = 256L * 1024L * 1024L;
    static final int MAX_ARCHIVE_ENTRIES = 20000;
    static final int MAX_DIRECTORY_DEPTH = 64;

    private ProjectArchiveIO() { }

    /**
     * Extracts an untrusted ZIP to a hidden staging directory and promotes it only after
     * extraction succeeds. The input stream is closed by this method.
     */
    static String importArchive(InputStream input, File projectsRoot, String requestedName) throws IOException {
        if (input == null) throw new IOException("Archive stream is unavailable");
        if (!projectsRoot.isDirectory() && !projectsRoot.mkdirs()) throw new IOException("Project storage is unavailable");
        File canonicalRoot = projectsRoot.getCanonicalFile();
        String baseName = ArchiveSafety.sanitizeProjectName(requestedName);
        File staging = new File(canonicalRoot, ".import-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("Could not create temporary import directory");

        File destination = null;
        boolean promoted = false;
        try {
            long expanded = 0L;
            int entryCount = 0;
            byte[] buffer = new byte[32768];
            Set<String> seenEntries = new HashSet<>();
            try (InputStream source = input;
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new CountingLimitInputStream(source, MAX_ARCHIVE_INPUT_BYTES)))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Import was cancelled");
                    if (++entryCount > MAX_ARCHIVE_ENTRIES) throw new IOException("Archive contains too many entries");
                    String relative = ArchiveSafety.normalizeZipEntryName(entry.getName());
                    if (!seenEntries.add(relative)) throw new IOException("Archive contains duplicate paths");
                    File target = new File(staging, relative.replace('/', File.separatorChar)).getCanonicalFile();
                    String stagingPath = staging.getCanonicalPath();
                    if (!target.getPath().startsWith(stagingPath + File.separator)) {
                        throw new IOException("Archive entry escapes the project folder");
                    }
                    if (entry.isDirectory()) {
                        // Directory entries must not carry a payload; otherwise a ZIP bomb could
                        // hide decompression work in closeEntry() without contributing to limits.
                        if (zip.read() != -1) throw new IOException("Archive directory entries must be empty");
                        if (target.exists() && !target.isDirectory()) throw new IOException("Archive path conflicts with a file");
                        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Could not create archive directory");
                    } else {
                        File parent = target.getParentFile();
                        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Could not create archive parent directory");
                        if (target.exists()) throw new IOException("Archive path conflicts with an existing entry");
                        long fileBytes = 0L;
                        try (FileOutputStream output = new FileOutputStream(target, false)) {
                            int read;
                            while ((read = zip.read(buffer)) != -1) {
                                if (Thread.currentThread().isInterrupted()) throw new IOException("Import was cancelled");
                                fileBytes += read;
                                expanded += read;
                                if (fileBytes > MAX_FILE_BYTES) throw new IOException("Archive contains a file exceeding 64 MiB");
                                if (expanded > MAX_ARCHIVE_EXPANDED_BYTES) throw new IOException("Archive expands beyond the 256 MiB safety limit");
                                output.write(buffer, 0, read);
                            }
                            output.getFD().sync();
                        }
                    }
                    zip.closeEntry();
                }
            }

            // Reject random/empty input, but accept the explicit root directory emitted by our exporter.
            File[] children = staging.listFiles();
            if (entryCount == 0 || children == null || children.length == 0) throw new IOException("The selected file is not a usable ZIP project archive");
            File contentRoot = staging;
            if (children.length == 1 && children[0].isDirectory()) contentRoot = children[0];
            String finalName = findAvailableProjectName(canonicalRoot, baseName);
            destination = new File(canonicalRoot, finalName);
            if (contentRoot.renameTo(destination)) {
                promoted = true;
            } else {
                try {
                    if (!copyRecursively(contentRoot, destination)) throw new IOException("Could not finalize imported project");
                    if (!deleteRecursively(contentRoot)) throw new IOException("Could not clean up temporary import data");
                    promoted = true;
                } catch (IOException e) {
                    deleteRecursively(destination);
                    throw e;
                }
            }
            if (staging.exists()) deleteRecursively(staging);
            return finalName;
        } finally {
            if (!promoted && destination != null && destination.exists()) deleteRecursively(destination);
            if (staging.exists()) deleteRecursively(staging);
        }
    }

    /** Streams a complete project directory into a ZIP with a single top-level directory. */
    static void exportProject(File project, OutputStream output) throws IOException {
        if (project == null || !project.isDirectory()) throw new IOException("Project folder does not exist");
        if (output == null) throw new IOException("ZIP destination is unavailable");
        File canonicalProject = project.getCanonicalFile();
        long[] totalBytes = {0L};
        int[] entries = {0};
        Set<String> visitedDirectories = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new CountingLimitOutputStream(output, MAX_ARCHIVE_INPUT_BYTES, "ZIP archive exceeds the 320 MiB export limit")))) {
            String topLevel = canonicalProject.getName();
            zip.putNextEntry(new ZipEntry(topLevel + "/"));
            zip.closeEntry();
            addDirectoryToZip(canonicalProject, canonicalProject.getPath(), topLevel,
                    0, zip, totalBytes, entries, visitedDirectories);
            zip.finish();
        }
    }

    private static void addDirectoryToZip(File current, String rootPath, String relative,
                                          int depth, ZipOutputStream zip, long[] totalBytes, int[] entries,
                                          Set<String> visitedDirectories) throws IOException {
        if (depth > MAX_DIRECTORY_DEPTH) throw new IOException("Project folder nesting exceeds export limit");
        String canonical = current.getCanonicalPath();
        if (!canonical.equals(rootPath) && !canonical.startsWith(rootPath + File.separator)) {
            throw new IOException("Project contains a path outside its root");
        }
        if (!visitedDirectories.add(canonical)) return;
        File[] children = current.listFiles();
        if (children == null) throw new IOException("Could not read project folder");
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Export was cancelled");
            if (++entries[0] > MAX_ARCHIVE_ENTRIES) throw new IOException("Project contains too many entries to export");
            String childPath = relative + "/" + child.getName();
            if (childPath.length() > 4096) throw new IOException("Project path exceeds the ZIP path safety limit");
            String childCanonical = child.getCanonicalPath();
            if (!childCanonical.equals(rootPath) && !childCanonical.startsWith(rootPath + File.separator)) {
                throw new IOException("Project contains a path outside its root");
            }
            if (child.isDirectory()) {
                zip.putNextEntry(new ZipEntry(childPath + "/"));
                zip.closeEntry();
                addDirectoryToZip(child, rootPath, childPath, depth + 1, zip, totalBytes, entries, visitedDirectories);
            } else if (child.isFile()) {
                long expectedSize = child.length();
                if (expectedSize > MAX_FILE_BYTES) throw new IOException("A project file exceeds 64 MiB");
                if (totalBytes[0] + expectedSize > MAX_ARCHIVE_EXPORT_BYTES) throw new IOException("Project exceeds the 256 MiB export safety limit");
                zip.putNextEntry(new ZipEntry(childPath));
                try (InputStream in = new BufferedInputStream(new FileInputStream(child))) {
                    byte[] buffer = new byte[32768];
                    int read;
                    long fileBytes = 0L;
                    while ((read = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Export was cancelled");
                        fileBytes += read;
                        totalBytes[0] += read;
                        if (fileBytes > MAX_FILE_BYTES || totalBytes[0] > MAX_ARCHIVE_EXPORT_BYTES) {
                            throw new IOException("Project exceeds the export safety limit");
                        }
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static String findAvailableProjectName(File root, String baseName) throws IOException {
        for (int suffix = 1; suffix < 10000; suffix++) {
            String candidate = suffix == 1 ? baseName : baseName + " (" + suffix + ")";
            if (!ArchiveSafety.isSafeImmediateProjectName(candidate)) continue;
            if (!new File(root, candidate).exists()) return candidate;
        }
        throw new IOException("Too many projects with the same name");
    }

    private static boolean copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (target.exists() && !target.isDirectory()) return false;
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
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.getFD().sync();
        }
        return true;
    }

    private static boolean deleteRecursively(File target) {
        if (target == null || !target.exists()) return true;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        }
        return target.delete();
    }

    private static final class CountingLimitOutputStream extends java.io.FilterOutputStream {
        private final long limit;
        private long count;
        private final String limitMessage;
        CountingLimitOutputStream(OutputStream output, long limit, String limitMessage) {
            super(output);
            this.limit = limit;
            this.limitMessage = limitMessage;
        }
        @Override public void write(int value) throws IOException {
            if (++count > limit) throw new IOException(limitMessage);
            out.write(value);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len < 0 || count + len > limit) throw new IOException(limitMessage);
            count += len;
            out.write(b, off, len);
        }
    }

    private static final class CountingLimitInputStream extends FilterInputStream {
        private final long limit;
        private long count;
        CountingLimitInputStream(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value != -1 && ++count > limit) throw new IOException("Archive exceeds the 320 MiB import limit");
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                count += read;
                if (count > limit) throw new IOException("Archive exceeds the 320 MiB import limit");
            }
            return read;
        }
    }
}
