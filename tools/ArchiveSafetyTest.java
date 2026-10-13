package com.reconstruction.spck;

import java.io.IOException;

/** Small no-dependency regression tests for the ZIP path/name policy. */
public final class ArchiveSafetyTest {
    private static int checks;

    private static void expectSafe(String input, String expected) throws Exception {
        checks++;
        String actual = ArchiveSafety.normalizeZipEntryName(input);
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " for " + input + ", got " + actual);
    }

    private static void expectRejected(String input) throws Exception {
        checks++;
        try {
            ArchiveSafety.normalizeZipEntryName(input);
            throw new AssertionError("Unsafe archive entry was accepted: " + input);
        } catch (IOException expected) {
            // Expected policy rejection.
        }
    }

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        expectSafe("index.html", "index.html");
        expectSafe("src/main.js", "src/main.js");
        expectSafe("project/assets/logo.svg", "project/assets/logo.svg");
        expectSafe("folder/", "folder");
        expectRejected("");
        expectRejected("/");
        expectRejected("/absolute.txt");
        expectRejected("../escape.txt");
        expectRejected("project/../../escape.txt");
        expectRejected("./file.txt");
        expectRejected("folder//file.txt");
        expectRejected("C:/windows.ini");
        expectRejected("folder\\file.txt");
        expectRejected("name\u0000.txt");
        expectRejected("a".repeat(4097));
        expect("Sample Project".equals(ArchiveSafety.sanitizeProjectName("Sample Project.zip")), "ZIP extension should be removed once");
        expect("evil".equals(ArchiveSafety.sanitizeProjectName("../evil.zip")), "Archive name must not escape project root");
        expect(!ArchiveSafety.isSafeImmediateProjectName("../x"), "Traversal project name must be rejected");
        expect(!ArchiveSafety.isSafeImmediateProjectName("exports"), "Reserved exports directory must be rejected");
        expect(ArchiveSafety.isSafeImmediateProjectName("my-project (2)"), "Normal project name should be accepted");
        System.out.println("PASS: " + checks + " ArchiveSafety tests");
    }
}
