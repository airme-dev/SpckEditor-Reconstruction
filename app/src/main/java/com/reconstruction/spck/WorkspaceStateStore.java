package com.reconstruction.spck;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Crash-tolerant, atomic persistence for the last project selected in the reconstructed tools
 * and a short most-recently-used list. The store intentionally lives outside the project tree.
 */
final class WorkspaceStateStore {
    static final int MAX_RECENT_PROJECTS = 12;
    private final File stateFile;
    private final File projectsRoot;
    private Properties values = new Properties();

    WorkspaceStateStore(File stateFile, File projectsRoot) {
        this.stateFile = stateFile;
        this.projectsRoot = projectsRoot;
        loadFromDisk();
        pruneMissingProjects();
    }

    synchronized String getLastUsedProject() {
        String name = values.getProperty("lastUsedProject", "");
        return isExistingProject(name) ? name : "";
    }

    synchronized List<String> getRecentProjects() {
        pruneMissingProjects();
        return readRecentNames();
    }

    synchronized boolean recordUse(String projectName) {
        if (!isExistingProject(projectName)) return false;
        Properties previous = copyProperties(values);
        List<String> recent = readRecentNames();
        recent.remove(projectName);
        recent.add(0, projectName);
        while (recent.size() > MAX_RECENT_PROJECTS) recent.remove(recent.size() - 1);

        values.setProperty("schemaVersion", "1");
        values.setProperty("lastUsedProject", projectName);
        writeRecentNames(recent);
        if (persistAtomically()) return true;
        values = previous;
        return false;
    }

    synchronized boolean forgetProject(String projectName) {
        if (!ArchiveSafety.isSafeImmediateProjectName(projectName)) return false;
        Properties previous = copyProperties(values);
        List<String> recent = readRecentNames();
        recent.remove(projectName);
        if (projectName.equals(values.getProperty("lastUsedProject", ""))) {
            values.remove("lastUsedProject");
        }
        writeRecentNames(recent);
        if (persistAtomically()) return true;
        values = previous;
        return false;
    }

    private void pruneMissingProjects() {
        Properties previous = copyProperties(values);
        List<String> recent = readRecentNames();
        recent.removeIf(name -> !isExistingProject(name));
        String last = values.getProperty("lastUsedProject", "");
        if (!isExistingProject(last)) values.remove("lastUsedProject");
        writeRecentNames(recent);
        if (!values.equals(previous) && !persistAtomically()) values = previous;
    }

    private List<String> readRecentNames() {
        int count;
        try {
            count = Integer.parseInt(values.getProperty("recentCount", "0"));
        } catch (NumberFormatException e) {
            count = 0;
        }
        count = Math.max(0, Math.min(MAX_RECENT_PROJECTS, count));
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            String name = values.getProperty("recent." + i, "");
            if (ArchiveSafety.isSafeImmediateProjectName(name) && isExistingProject(name)) names.add(name);
        }
        return new ArrayList<>(names);
    }

    private void writeRecentNames(List<String> recent) {
        List<String> bounded = new ArrayList<>();
        for (String name : recent) {
            if (ArchiveSafety.isSafeImmediateProjectName(name) && isExistingProject(name) && !bounded.contains(name)) {
                bounded.add(name);
                if (bounded.size() == MAX_RECENT_PROJECTS) break;
            }
        }
        int oldCount;
        try { oldCount = Integer.parseInt(values.getProperty("recentCount", "0")); }
        catch (NumberFormatException e) { oldCount = MAX_RECENT_PROJECTS; }
        oldCount = Math.max(0, Math.min(MAX_RECENT_PROJECTS, oldCount));
        for (int i = 0; i < Math.max(oldCount, MAX_RECENT_PROJECTS); i++) values.remove("recent." + i);
        values.setProperty("recentCount", Integer.toString(bounded.size()));
        for (int i = 0; i < bounded.size(); i++) values.setProperty("recent." + i, bounded.get(i));
    }

    private boolean isExistingProject(String name) {
        if (!ArchiveSafety.isSafeImmediateProjectName(name)) return false;
        try {
            File canonicalRoot = projectsRoot.getCanonicalFile();
            File project = new File(canonicalRoot, name).getCanonicalFile();
            return project.isDirectory() && project.getParentFile() != null
                    && project.getParentFile().equals(canonicalRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private void loadFromDisk() {
        Properties loaded = new Properties();
        if (stateFile.isFile()) {
            try (FileInputStream in = new FileInputStream(stateFile)) {
                loaded.load(in);
                values = loaded;
                return;
            } catch (IOException | IllegalArgumentException ignored) {
                // Corrupt or partially written state is discarded; project files are untouched.
            }
        }
        values = new Properties();
    }

    private boolean persistAtomically() {
        File parent = stateFile.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        File temp = new File(parent, stateFile.getName() + ".tmp");
        File backup = new File(parent, stateFile.getName() + ".bak");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                values.store(out, "Spck reconstruction workspace metadata");
                out.flush();
                out.getFD().sync();
            }
            if (backup.exists() && !backup.delete()) throw new IOException("Cannot clean old workspace backup");
            boolean backedUp = false;
            if (stateFile.exists()) {
                if (!stateFile.renameTo(backup)) throw new IOException("Cannot preserve prior workspace state");
                backedUp = true;
            }
            if (!temp.renameTo(stateFile)) {
                if (backedUp) backup.renameTo(stateFile);
                throw new IOException("Cannot promote new workspace state");
            }
            if (backup.exists()) backup.delete();
            return true;
        } catch (IOException e) {
            if (temp.exists()) temp.delete();
            if (!stateFile.exists() && backup.exists()) backup.renameTo(stateFile);
            return false;
        }
    }

    private static Properties copyProperties(Properties source) {
        Properties result = new Properties();
        result.putAll(source);
        return result;
    }
}
