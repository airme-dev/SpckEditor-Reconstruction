package com.reconstruction.spck;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

/** JVM regression tests for persistent workspace selection and MRU semantics. */
public final class WorkspaceStateStoreTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("spck-workspace-state-").toFile();
        try {
            File root = new File(temp, "projects");
            check(root.mkdirs(), "create project root");
            project(root, "alpha");
            project(root, "beta");
            project(root, "gamma");
            File stateFile = new File(temp, "meta/workspace.properties");

            WorkspaceStateStore state = new WorkspaceStateStore(stateFile, root);
            eq("", state.getLastUsedProject(), "empty store has no last-used project");
            check(state.recordUse("alpha"), "record alpha");
            check(state.recordUse("beta"), "record beta");
            check(state.recordUse("alpha"), "record alpha again");
            eq("alpha", state.getLastUsedProject(), "last-used project persists");
            eq(List.of("alpha", "beta"), state.getRecentProjects(), "MRU ordering deduplicates projects");

            state = new WorkspaceStateStore(stateFile, root);
            eq("alpha", state.getLastUsedProject(), "last project survives object recreation");
            eq(List.of("alpha", "beta"), state.getRecentProjects(), "recents survive object recreation");
            check(!state.recordUse("../escape"), "reject traversal project name");
            check(!state.recordUse("missing"), "reject missing project folder");
            check(!state.recordUse("."), "reject dot project name");
            check(!state.forgetProject("../escape"), "forget rejects unsafe name");

            check(state.forgetProject("alpha"), "forget alpha");
            eq("", state.getLastUsedProject(), "forget clears last-used selection");
            eq(List.of("beta"), state.getRecentProjects(), "forget removes recent item");

            check(state.recordUse("gamma"), "record gamma");
            check(new File(root, "gamma").delete(), "remove gamma directory");
            eq(List.of("beta"), state.getRecentProjects(), "missing workspace is pruned");
            eq("", state.getLastUsedProject(), "missing last-used workspace is pruned");

            File corrupt = new File(temp, "corrupt.properties");
            try (FileOutputStream out = new FileOutputStream(corrupt)) {
                out.write(new byte[]{(byte) 0x00, (byte) 0xFF, (byte) 0xA1});
            }
            WorkspaceStateStore recovered = new WorkspaceStateStore(corrupt, root);
            eq("", recovered.getLastUsedProject(), "corrupt store safely recovers empty");
            check(recovered.recordUse("beta"), "store remains writable after corrupt-state recovery");
            eq("beta", new WorkspaceStateStore(corrupt, root).getLastUsedProject(), "recovered state can be read again");

            WorkspaceStateStore bounded = new WorkspaceStateStore(new File(temp, "bounded.properties"), root);
            for (int i = 0; i < 20; i++) project(root, "project" + i);
            for (int i = 0; i < 20; i++) check(bounded.recordUse("project" + i), "record bounded project " + i);
            eq(WorkspaceStateStore.MAX_RECENT_PROJECTS, bounded.getRecentProjects().size(), "recent list has fixed upper bound");
            eq("project19", bounded.getLastUsedProject(), "most recent project stays at front");
            check(bounded.getRecentProjects().get(0).equals("project19"), "newest project is first");
            check(!bounded.getRecentProjects().contains("project0"), "oldest project is evicted past cap");

            File hostileCountFile = new File(temp, "hostile-count.properties");
            Properties hostile = new Properties();
            hostile.setProperty("schemaVersion", "1");
            hostile.setProperty("lastUsedProject", "beta");
            hostile.setProperty("recentCount", "2147483647");
            hostile.setProperty("recent.0", "beta");
            try (FileOutputStream out = new FileOutputStream(hostileCountFile)) { hostile.store(out, "test untrusted count"); }
            WorkspaceStateStore hostileCountState = new WorkspaceStateStore(hostileCountFile, root);
            eq("beta", hostileCountState.getLastUsedProject(), "hostile oversized count keeps valid last project");
            eq(List.of("beta"), hostileCountState.getRecentProjects(), "hostile recent-count metadata is capped and normalized");

            System.out.println("PASS: WorkspaceStateStoreTest (" + assertions + " assertions)");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void project(File root, String name) throws Exception {
        File dir = new File(root, name);
        check(dir.mkdirs() || dir.isDirectory(), "create project " + name);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual), message + " (expected=" + expected + ", actual=" + actual + ")");
    }
    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
