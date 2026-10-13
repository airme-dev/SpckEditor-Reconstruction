package com.reconstruction.spck;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;

/** JVM regression tests for local Git interoperability with the host Git implementation. */
public final class LocalGitEngineTest {
    private LocalGitEngineTest() { }

    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("spck-local-git-test-").toFile();
        try {
            File project = new File(temp, "site");
            check(project.mkdirs(), "create project folder");
            write(new File(project, "index.html"), "<!doctype html>\n<h1>main</h1>\n");
            File assets = new File(project, "src/components");
            check(assets.mkdirs(), "create nested folder");
            write(new File(assets, "greeting.js"), "export const greeting = 'hello';\n");

            String init = run(project, "init", "");
            contains(init, "\"ok\":true", "init returns success");
            contains(init, "main", "main is initial branch");
            String initialStatus = run(project, "status", "");
            contains(initialStatus, "index.html", "untracked html file appears");
            contains(initialStatus, "greeting.js", "nested untracked file appears");
            contains(git(project, "status", "--porcelain", "--untracked-files=all"), "?? index.html", "host Git reads initial worktree");

            String staged = run(project, "stageAll", "");
            contains(staged, "\"fileCount\":2", "stage-all indexes both files");
            String stagedStatus = run(project, "status", "");
            contains(stagedStatus, "\"added\":[\"index.html\",\"src/components/greeting.js\"]", "new files show as staged additions");
            contains(git(project, "status", "--porcelain"), "A  index.html", "host Git reads custom v2 index");

            String firstCommit = run(project, "commit", "Initial commit\nCreated by test");
            String firstId = field(firstCommit, "id");
            check(firstId.length() == 40, "commit id is a SHA-1");
            check(git(project, "fsck", "--full", "--no-reflogs").trim().isEmpty(), "host Git fsck reports no object errors");
            check(git(project, "status", "--porcelain").trim().isEmpty(), "host Git sees clean worktree after commit");
            contains(git(project, "log", "-1", "--format=%s"), "Initial commit", "host Git reads commit object");

            write(new File(project, "index.html"), "<!doctype html>\n<h1>main changed</h1>\n");
            contains(run(project, "status", ""), "\"modified\":[\"index.html\"]", "working-tree modifications are reported");
            run(project, "stageAll", "");
            run(project, "commit", "Update main page");
            run(project, "createBranch", "feature/git-tools");
            contains(run(project, "branches", ""), "feature/git-tools", "nested branch is listed");
            run(project, "checkout", "feature/git-tools");
            write(new File(project, "index.html"), "<!doctype html>\n<h1>feature</h1>\n");
            run(project, "stageAll", "");
            run(project, "commit", "Feature change");
            check(read(new File(project, "index.html")).contains("feature"), "feature change is present");
            contains(run(project, "log", ""), "Feature change", "local log includes selected branch commit");
            run(project, "checkout", "main");
            check(read(new File(project, "index.html")).contains("main changed"), "checkout restores main branch content");
            contains(git(project, "status", "--porcelain"), "", "host Git has no unexpected changes", true);
            contains(git(project, "branch", "--list"), "feature/git-tools", "host Git recognizes nested local branch");
            contains(run(project, "log", ""), "Update main page", "local log reflects main branch history after checkout");

            File deleted = new File(project, "src/components/greeting.js");
            check(deleted.delete(), "delete tracked file");
            contains(run(project, "status", ""), "\"deleted\":[\"src/components/greeting.js\"]", "tracked deletion is reported");
            run(project, "stageAll", "");
            contains(run(project, "status", ""), "\"staged\":{\"added\":[],\"modified\":[],\"deleted\":[\"src/components/greeting.js\"]}", "tracked deletion is staged");
            run(project, "commit", "Remove greeting module");
            contains(git(project, "status", "--porcelain"), "", "host Git remains clean after delete commit", true);

            expectFailure(project, "createBranch", "../escape", "unsafe branch names rejected");
            expectFailure(project, "checkout", "missing-branch", "missing branch rejected");
            expectFailure(project, "commit", "", "empty commit message rejected");
            contains(run(project, "status", ""), "\"ok\":true", "repository remains usable after invalid operations");

            System.out.println("PASS: LocalGitEngineTest (" + assertions + " assertions); local repo is interoperable with host git.");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static int assertions;
    private static String run(File project, String op, String arg) throws IOException {
        String json = LocalGitEngine.execute(project, op, arg);
        contains(json, "\"ok\":true", op + " success result");
        return json;
    }
    private static String git(File project, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 3];
        command[0] = "git"; command[1] = "-C"; command[2] = project.getAbsolutePath();
        System.arraycopy(args, 0, command, 3, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) { in.transferTo(out); }
        int code = process.waitFor();
        String text = out.toString(StandardCharsets.UTF_8);
        if (code != 0) throw new AssertionError("git command failed (" + String.join(" ", args) + "): " + text);
        return text;
    }
    private static String field(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new AssertionError("Missing JSON field: " + key + " in " + json);
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) throw new AssertionError("Malformed JSON field: " + key);
        return json.substring(start, end);
    }
    private static void expectFailure(File project, String op, String arg, String message) throws Exception {
        try { LocalGitEngine.execute(project, op, arg); throw new AssertionError(message + " (operation unexpectedly succeeded)"); }
        catch (IOException expected) { assertions++; }
    }
    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not make test directory");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    private static String read(File file) throws IOException { return Files.readString(file.toPath(), StandardCharsets.UTF_8); }
    private static void contains(String actual, String expected, String message) { contains(actual, expected, message, false); }
    private static void contains(String actual, String expected, String message, boolean allowEmptyExpected) {
        assertions++;
        if (allowEmptyExpected && expected.isEmpty()) { if (!actual.trim().isEmpty()) throw new AssertionError(message + ": got " + actual); return; }
        if (!actual.contains(expected)) throw new AssertionError(message + ": expected to find [" + expected + "] in [" + actual + "]");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Could not clean test path: " + file);
    }
}
