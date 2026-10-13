package com.reconstruction.spck;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** End-to-end JVM tests for the exact archive helper used by the Android bridge. */
public final class ProjectArchiveIOTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(ThrowingRunnable runnable, String message) throws Exception {
        checks++;
        try {
            runnable.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Expected rejection by archive importer.
        }
    }

    private static byte[] zip(String[][] entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String[] item : entries) {
                ZipEntry entry = new ZipEntry(item[0]);
                zip.putNextEntry(entry);
                if (item.length > 1 && item[1] != null) zip.write(item[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("spck-archive-test-").toFile();
        try {
            File projects = new File(temp, "projects");
            check(projects.mkdir(), "Test project root creation failed");

            File demo = new File(projects, "demo");
            File source = new File(demo, "src");
            check(source.mkdirs(), "Demo source directory creation failed");
            Files.writeString(new File(demo, "index.html").toPath(), "<h1>hello</h1>", StandardCharsets.UTF_8);
            Files.writeString(new File(source, "main.js").toPath(), "console.log('ok');", StandardCharsets.UTF_8);
            check(new File(demo, "empty").mkdir(), "Empty directory creation failed");

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ProjectArchiveIO.exportProject(demo, bytes);
            String imported = ProjectArchiveIO.importArchive(new ByteArrayInputStream(bytes.toByteArray()), projects, "restored.zip");
            check("restored".equals(imported), "Import project name did not normalize correctly");
            check(Files.readString(new File(projects, "restored/index.html").toPath()).equals("<h1>hello</h1>"), "Root file did not round-trip");
            check(Files.readString(new File(projects, "restored/src/main.js").toPath()).equals("console.log('ok');"), "Nested file did not round-trip");
            check(new File(projects, "restored/empty").isDirectory(), "Empty directory did not round-trip");

            String collision = ProjectArchiveIO.importArchive(new ByteArrayInputStream(bytes.toByteArray()), projects, "restored.zip");
            check("restored (2)".equals(collision), "Import collision should receive a unique suffix");

            File emptyProject = new File(projects, "empty-project");
            check(emptyProject.mkdir(), "Empty project creation failed");
            ByteArrayOutputStream emptyZip = new ByteArrayOutputStream();
            ProjectArchiveIO.exportProject(emptyProject, emptyZip);
            String restoredEmpty = ProjectArchiveIO.importArchive(new ByteArrayInputStream(emptyZip.toByteArray()), projects, "empty-copy.zip");
            check(new File(projects, restoredEmpty).isDirectory(), "Empty project should round-trip");
            check(new File(projects, restoredEmpty).list().length == 0, "Empty project should not gain synthetic files");

            File escaped = new File(temp, "escaped.txt");
            byte[] traversal = zip(new String[][]{{"../escaped.txt", "nope"}});
            expectFailure(() -> ProjectArchiveIO.importArchive(new ByteArrayInputStream(traversal), projects, "evil.zip"), "ZIP traversal archive should be rejected");
            check(!escaped.exists(), "ZIP traversal wrote outside the project root");
            check(projects.list((dir, name) -> name.startsWith(".import-")).length == 0, "Failed import left staging directories behind");

            byte[] absolute = zip(new String[][]{{"/absolute.txt", "nope"}});
            expectFailure(() -> ProjectArchiveIO.importArchive(new ByteArrayInputStream(absolute), projects, "absolute.zip"), "Absolute ZIP path should be rejected");
            byte[] directoryPayload = zip(new String[][]{{"payload/", "hidden payload"}});
            expectFailure(() -> ProjectArchiveIO.importArchive(new ByteArrayInputStream(directoryPayload), projects, "dir-payload.zip"), "Directory entries with data should be rejected");
            byte[] duplicate = zip(new String[][]{{"same.txt/", null}, {"same.txt", "second"}});
            expectFailure(() -> ProjectArchiveIO.importArchive(new ByteArrayInputStream(duplicate), projects, "duplicate.zip"), "Normalized duplicate ZIP paths should be rejected");
            expectFailure(() -> ProjectArchiveIO.importArchive(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8)), projects, "bad.zip"), "Non-ZIP input should be rejected");

            System.out.println("PASS: " + checks + " ProjectArchiveIO tests");
        } finally {
            delete(temp);
        }
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }
}
