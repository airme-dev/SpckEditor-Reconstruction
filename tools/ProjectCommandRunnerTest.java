package com.reconstruction.spck;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Regression tests for the restricted built-in project console (no arbitrary shell execution). */
public final class ProjectCommandRunnerTest {
    private static int assertions;
    private ProjectCommandRunnerTest() { }

    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("spck-project-console-test-").toFile();
        File project = new File(temp, "site");
        File outside = new File(temp, "outside");
        try {
            check(project.mkdir(), "create project");
            check(outside.mkdir(), "create outside directory");
            write(new File(project, "hello.txt"), "one\ntwo\nthree\n");
            File hidden = new File(project, ".git/objects");
            check(hidden.mkdirs(), "create git metadata fixture");
            write(new File(hidden, "secret-object"), "not for console");
            write(new File(outside, "secret.txt"), "outside");

            includes(run(project, "pwd"), "/documents/site", "pwd prints virtual project path");
            includes(run(project, "ls"), "hello.txt", "ls lists project files");
            notIncludes(run(project, "ls"), "secret-object", "ls hides Git metadata");
            includes(run(project, "cat hello.txt"), "one\\ntwo\\nthree", "cat reads UTF-8 text");
            includes(run(project, "head hello.txt 2"), "one\\ntwo", "head supports bounded line count");
            includes(run(project, "find txt"), "hello.txt", "find filters filenames");
            includes(run(project, "mkdir src/components"), "Created directory", "mkdir creates nested path");
            includes(run(project, "touch src/components/app.js"), "Touched", "touch creates a file");
            write(new File(project, "copy.txt"), "copy me");
            includes(run(project, "cp copy.txt copy-2.txt"), "Copied", "cp copies one file");
            includes(run(project, "mv copy-2.txt moved.txt"), "Moved", "mv moves one file");
            includes(run(project, "rm moved.txt"), "Removed", "rm deletes a single file");
            includes(run(project, "help"), "not a general shell", "help discloses restricted command model");
            includes(run(project, "clear"), "\"output\":\"\"", "clear outputs empty content");

            expectFailure(project, "rm -r .", "rm does not recursively delete directories");
            expectFailure(project, "cat ../outside/secret.txt", "parent traversal blocked");
            expectFailure(project, "cat /etc/passwd", "absolute paths blocked");
            expectFailure(project, "cat .git/objects/secret-object", "Git internals blocked");
            expectFailure(project, "pwd; rm hello.txt", "shell command chaining blocked");
            expectFailure(project, "echo $(id)", "shell expansion blocked");
            expectFailure(project, "sh -c id", "arbitrary process commands rejected");
            includes(run(project, ""), "\"output\":\"\"", "empty command is a harmless no-op");

            try {
                Files.createSymbolicLink(new File(project, "outside-link").toPath(), outside.toPath());
                expectFailure(project, "cat outside-link/secret.txt", "symlink escape blocked");
            } catch (UnsupportedOperationException | SecurityException | IOException ignored) {
                // Symlink creation is not available on all test hosts; canonical path checks are also static-verified.
            }
            System.out.println("PASS: ProjectCommandRunnerTest (" + assertions + " assertions)");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static String run(File project, String command) throws IOException {
        ProjectCommandRunner.Result result = ProjectCommandRunner.execute(project, command);
        check(result.ok, "command succeeds: " + command);
        return result.toJson();
    }
    private static void expectFailure(File project, String command, String message) throws Exception {
        try {
            ProjectCommandRunner.execute(project, command);
            throw new AssertionError(message + " (command unexpectedly succeeded)");
        } catch (IOException expected) { assertions++; }
    }
    private static void includes(String actual, String expected, String message) {
        assertions++; if (!actual.contains(expected)) throw new AssertionError(message + ": missing [" + expected + "] in [" + actual + "]");
    }
    private static void notIncludes(String actual, String expected, String message) {
        assertions++; if (actual.contains(expected)) throw new AssertionError(message + ": unexpected [" + expected + "] in [" + actual + "]");
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create fixture path");
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }
    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!Files.isSymbolicLink(file.toPath()) && !file.delete()) throw new IOException("Could not clean test path: " + file);
        if (Files.isSymbolicLink(file.toPath())) Files.deleteIfExists(file.toPath());
    }
}
