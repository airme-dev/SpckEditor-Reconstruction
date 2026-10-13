package com.reconstruction.spck;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minimal, interoperable local Git implementation for app-private projects.
 * It writes the standard loose-object, refs, tree, commit, and v2 index formats.
 * Network remotes, hooks, submodules, filters, merge algorithms, and Git LFS are
 * deliberately unsupported. All filesystem access is constrained to one project.
 */
final class LocalGitEngine {
    private static final int MAX_FILES = 20_000;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_PROJECT_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_OBJECT_BYTES = 128 * 1024 * 1024;
    private static final int MAX_TREE_DEPTH = 64;
    private static final int MAX_LOG_COMMITS = 50;
    private static final String DEFAULT_AUTHOR = "Spck Reconstruction <spck@local.invalid>";

    private LocalGitEngine() { }

    static String execute(File projectDirectory, String operation, String argument) throws IOException {
        File project = canonicalProject(projectDirectory);
        String arg = argument == null ? "" : argument;
        switch (operation == null ? "" : operation) {
            case "init": return init(project);
            case "status": return status(project);
            case "stageAll": return stageAll(project);
            case "stageFile": return stageFile(project, arg);
            case "unstageFile": return unstageFile(project, arg);
            case "unstageAll": return unstageAll(project);
            case "commit": return commit(project, arg);
            case "log": return log(project);
            case "branches": return branches(project);
            case "createBranch": return createBranch(project, arg);
            case "checkout": return checkout(project, arg);
            default: throw new IOException("Unsupported local Git operation");
        }
    }

    static File canonicalProject(File input) throws IOException {
        if (input == null) throw new IOException("Project folder was not provided");
        File canonical = input.getCanonicalFile();
        if (!canonical.isDirectory()) throw new IOException("Project folder does not exist");
        if (canonical.getName().startsWith(".")) throw new IOException("Hidden folders cannot be opened as projects");
        return canonical;
    }

    private static File gitDir(File project) throws IOException {
        File dir = new File(project, ".git").getCanonicalFile();
        if (!dir.getParentFile().equals(project.getCanonicalFile())) throw new IOException("Git metadata must be inside the project folder");
        if (!dir.isDirectory()) throw new IOException("This project is not a Git repository; initialize it first");
        File head = new File(dir, "HEAD");
        if (!head.isFile()) throw new IOException("Git repository metadata is incomplete");
        return dir;
    }

    private static String init(File project) throws IOException {
        File dir = new File(project, ".git");
        if (dir.exists()) {
            File canonical = dir.getCanonicalFile();
            if (!canonical.getParentFile().equals(project) || !canonical.isDirectory()) throw new IOException("Unsafe or invalid .git path");
            if (new File(canonical, "HEAD").isFile()) return "{\"ok\":true,\"action\":\"init\",\"message\":\"Git repository already exists\",\"branch\":" + quote(headBranch(canonical)) + "}";
            throw new IOException("A .git entry already exists but is not a supported repository");
        }
        mkdir(new File(dir, "objects"));
        mkdir(new File(dir, "refs/heads"));
        mkdir(new File(dir, "refs/tags"));
        writeText(new File(dir, "HEAD"), "ref: refs/heads/main\n");
        writeText(new File(dir, "config"), "[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n\tlogallrefupdates = true\n\n[init]\n\tdefaultbranch = main\n");
        writeText(new File(dir, "description"), "Unnamed project; created by Spck Reconstruction\n");
        writeIndex(dir, new TreeMap<String, IndexEntry>());
        return "{\"ok\":true,\"action\":\"init\",\"message\":\"Initialized local Git repository\",\"branch\":\"main\"}";
    }

    private static String status(File project) throws IOException {
        File git = gitDir(project);
        TreeMap<String, IndexEntry> index = readIndex(git);
        Map<String, IndexEntry> head = readHeadTree(git);
        TreeMap<String, File> working = scanFiles(project);
        List<String> stagedAdded = new ArrayList<>();
        List<String> stagedModified = new ArrayList<>();
        List<String> stagedDeleted = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> untracked = new ArrayList<>();

        for (Map.Entry<String, IndexEntry> item : index.entrySet()) {
            String path = item.getKey();
            IndexEntry inIndex = item.getValue();
            IndexEntry inHead = head.get(path);
            if (inHead == null) stagedAdded.add(path);
            else if (!inHead.objectId.equals(inIndex.objectId) || !inHead.mode.equals(inIndex.mode)) stagedModified.add(path);
            File file = working.get(path);
            if (file == null) {
                if (inHead == null) {
                    // Added and then deleted before committing: retain the staged-add state
                    // but mark the worktree deletion so the user can restage with git add -A.
                    deleted.add(path);
                } else {
                    deleted.add(path);
                }
            } else if (!hashBlob(file).equals(inIndex.objectId)) {
                modified.add(path);
            }
        }
        for (Map.Entry<String, IndexEntry> item : head.entrySet()) {
            if (!index.containsKey(item.getKey())) stagedDeleted.add(item.getKey());
        }
        for (String path : working.keySet()) {
            if (!index.containsKey(path)) untracked.add(path);
        }
        Collections.sort(stagedAdded);
        Collections.sort(stagedModified);
        Collections.sort(stagedDeleted);
        Collections.sort(modified);
        Collections.sort(deleted);
        Collections.sort(untracked);
        return "{\"ok\":true,\"action\":\"status\",\"initialized\":true,\"branch\":" + quote(headBranch(git))
                + ",\"hasCommit\":" + (headCommitId(git) != null)
                + ",\"staged\":{\"added\":" + stringArray(stagedAdded) + ",\"modified\":" + stringArray(stagedModified) + ",\"deleted\":" + stringArray(stagedDeleted) + "}"
                + ",\"working\":{\"modified\":" + stringArray(modified) + ",\"deleted\":" + stringArray(deleted) + ",\"untracked\":" + stringArray(untracked) + "}}";
    }

    /** Stage all safe regular project files and stage removals of previously tracked files. */
    private static String stageAll(File project) throws IOException {
        File git = gitDir(project);
        TreeMap<String, File> files = scanFiles(project);
        TreeMap<String, IndexEntry> staged = new TreeMap<>(gitPathComparator());
        for (Map.Entry<String, File> entry : files.entrySet()) {
            checkInterrupted();
            File file = entry.getValue();
            if (file.length() > MAX_FILE_BYTES) throw new IOException("File exceeds the 64 MiB per-file Git limit: " + entry.getKey());
            String objectId = writeBlob(project, file);
            staged.put(entry.getKey(), IndexEntry.fromFile(entry.getKey(), file, objectId));
        }
        writeIndex(git, staged);
        return "{\"ok\":true,\"action\":\"stageAll\",\"message\":\"Staged project changes\",\"fileCount\":" + staged.size() + "}";
    }

    /**
     * Stage a single file by path relative to the project root.
     * The path must already exist in the working tree; if not, it is treated as a
     * deletion and removed from the index (git add behaviour for deleted files).
     */
    private static String stageFile(File project, String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("File path is required for stageFile");
        validateGitPath(path);
        File git = gitDir(project);
        File file = safeWorktreeFile(project, path);
        TreeMap<String, IndexEntry> index = readIndex(git);
        if (!file.isFile()) {
            // File deleted in working tree: remove from index (equivalent to git rm --cached)
            if (!index.containsKey(path)) throw new IOException("File is not tracked: " + path);
            index.remove(path);
            writeIndex(git, index);
            return "{\"ok\":true,\"action\":\"stageFile\",\"message\":\"Staged deletion of " + path.replace("\\\"", "'") + "\",\"path\":" + quote(path) + "}";
        }
        if (file.length() > MAX_FILE_BYTES) throw new IOException("File exceeds the 64 MiB per-file Git limit: " + path);
        String objectId = writeBlob(project, file);
        index.put(path, IndexEntry.fromFile(path, file, objectId));
        writeIndex(git, index);
        return "{\"ok\":true,\"action\":\"stageFile\",\"message\":\"Staged changes to " + path.replace("\\\"", "'") + "\",\"path\":" + quote(path) + ",\"id\":" + quote(objectId) + "}";
    }

    /**
     * Unstage a single file — restore its index entry from HEAD without touching
     * the working tree (equivalent to `git restore --staged <path>`).
     */
    private static String unstageFile(File project, String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("File path is required for unstageFile");
        validateGitPath(path);
        File git = gitDir(project);
        TreeMap<String, IndexEntry> index = readIndex(git);
        Map<String, IndexEntry> head = readHeadTree(git);
        if (head.containsKey(path)) {
            // Restore the HEAD version of this entry in the index.
            index.put(path, head.get(path));
        } else {
            // The file was staged as a new addition — remove it from the index.
            index.remove(path);
        }
        writeIndex(git, index);
        return "{\"ok\":true,\"action\":\"unstageFile\",\"message\":\"Unstaged " + path.replace("\\\"", "'") + "\",\"path\":" + quote(path) + "}";
    }

    /**
     * Reset the entire index to the HEAD tree without touching the working tree
     * (equivalent to `git reset HEAD`).
     */
    private static String unstageAll(File project) throws IOException {
        File git = gitDir(project);
        Map<String, IndexEntry> head = readHeadTree(git);
        TreeMap<String, IndexEntry> newIndex = new TreeMap<>(gitPathComparator());
        for (Map.Entry<String, IndexEntry> entry : head.entrySet()) {
            File worktreeFile = safeWorktreeFile(project, entry.getKey());
            newIndex.put(entry.getKey(), IndexEntry.fromFileWithMode(
                    entry.getKey(), worktreeFile, entry.getValue().objectId, entry.getValue().mode));
        }
        writeIndex(git, newIndex);
        return "{\"ok\":true,\"action\":\"unstageAll\",\"message\":\"Reset index to HEAD\",\"fileCount\":" + newIndex.size() + "}";
    }

    private static String commit(File project, String message) throws IOException {
        String cleanMessage = message == null ? "" : message.replace("\r", "").trim();
        if (cleanMessage.isEmpty()) throw new IOException("Enter a commit message first");
        if (cleanMessage.length() > 10_000) throw new IOException("Commit message is too long");
        File git = gitDir(project);
        TreeMap<String, IndexEntry> index = readIndex(git);
        if (index.isEmpty()) throw new IOException("Nothing is staged; use Stage all changes first");
        Map<String, IndexEntry> headTree = readHeadTree(git);
        if (headCommitId(git) != null && sameTree(index, headTree)) throw new IOException("No staged changes to commit");
        String treeId = writeTree(git, index);
        String parent = headCommitId(git);
        long epoch = System.currentTimeMillis() / 1000L;
        String zone = currentTimezone();
        StringBuilder body = new StringBuilder();
        body.append("tree ").append(treeId).append('\n');
        if (parent != null) body.append("parent ").append(parent).append('\n');
        body.append("author ").append(DEFAULT_AUTHOR).append(' ').append(epoch).append(' ').append(zone).append('\n');
        body.append("committer ").append(DEFAULT_AUTHOR).append(' ').append(epoch).append(' ').append(zone).append("\n\n");
        body.append(cleanMessage).append('\n');
        String id = writeObject(git, "commit", body.toString().getBytes(StandardCharsets.UTF_8));
        String branch = headBranch(git);
        if (branch == null || branch.isEmpty()) throw new IOException("Detached HEAD commits are not supported");
        File ref = refFile(git, branch);
        mkdir(ref.getParentFile());
        writeText(ref, id + "\n");
        return "{\"ok\":true,\"action\":\"commit\",\"message\":\"Commit created\",\"id\":" + quote(id)
                + ",\"branch\":" + quote(branch) + ",\"subject\":" + quote(firstLine(cleanMessage)) + "}";
    }

    private static String log(File project) throws IOException {
        File git = gitDir(project);
        String current = headCommitId(git);
        StringBuilder entries = new StringBuilder();
        entries.append('[');
        int count = 0;
        Set<String> seen = new HashSet<>();
        while (current != null && count < MAX_LOG_COMMITS) {
            checkInterrupted();
            if (!current.matches("[0-9a-f]{40}") || !seen.add(current)) throw new IOException("Invalid or cyclic commit history");
            ObjectData commit = readObject(git, current);
            if (!"commit".equals(commit.type)) throw new IOException("Git history references a non-commit object");
            String text = new String(commit.data, StandardCharsets.UTF_8);
            String tree = "";
            String parent = null;
            String author = "";
            int blank = text.indexOf("\n\n");
            String headers = blank >= 0 ? text.substring(0, blank) : text;
            for (String line : headers.split("\n")) {
                if (line.startsWith("tree ")) tree = line.substring(5).trim();
                else if (line.startsWith("parent ") && parent == null) parent = line.substring(7).trim();
                else if (line.startsWith("author ")) author = line.substring(7).trim();
            }
            String commitMessage = blank >= 0 ? text.substring(blank + 2).trim() : "";
            long timestamp = parseTimestamp(author);
            if (count > 0) entries.append(',');
            entries.append("{\"id\":").append(quote(current)).append(",\"tree\":").append(quote(tree))
                    .append(",\"parent\":").append(parent == null ? "null" : quote(parent))
                    .append(",\"author\":").append(quote(author)).append(",\"timestamp\":").append(timestamp)
                    .append(",\"subject\":").append(quote(firstLine(commitMessage))).append('}');
            current = parent;
            count++;
        }
        entries.append(']');
        return "{\"ok\":true,\"action\":\"log\",\"branch\":" + quote(headBranch(git)) + ",\"commits\":" + entries + "}";
    }

    private static String branches(File project) throws IOException {
        File git = gitDir(project);
        String current = headBranch(git);
        List<String> names = new ArrayList<>();
        collectBranches(new File(git, "refs/heads"), "", names, 0);
        Collections.sort(names);
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) array.append(',');
            String name = names.get(i);
            String id = readRef(git, name);
            array.append("{\"name\":").append(quote(name)).append(",\"current\":").append(name.equals(current))
                    .append(",\"commit\":").append(id == null ? "null" : quote(id)).append('}');
        }
        array.append(']');
        return "{\"ok\":true,\"action\":\"branches\",\"current\":" + (current == null ? "null" : quote(current)) + ",\"branches\":" + array + "}";
    }

    private static String createBranch(File project, String branch) throws IOException {
        File git = gitDir(project);
        validateBranchName(branch);
        String head = headCommitId(git);
        if (head == null) throw new IOException("Create a commit before creating another branch");
        File target = refFile(git, branch);
        if (target.exists()) throw new IOException("Branch already exists: " + branch);
        mkdir(target.getParentFile());
        writeText(target, head + "\n");
        return "{\"ok\":true,\"action\":\"createBranch\",\"message\":\"Created branch\",\"branch\":" + quote(branch) + ",\"commit\":" + quote(head) + "}";
    }

    private static String checkout(File project, String branch) throws IOException {
        File git = gitDir(project);
        validateBranchName(branch);
        File targetRef = refFile(git, branch);
        if (!targetRef.isFile()) throw new IOException("Branch does not exist: " + branch);
        String targetCommit = readRef(git, branch);
        if (targetCommit == null) throw new IOException("Branch reference is invalid");
        StatusSnapshot snapshot = statusSnapshot(project, git);
        if (!snapshot.isClean()) throw new IOException("Working tree is not clean. Stage or commit changes before switching branches.");
        Map<String, IndexEntry> oldTree = readHeadTree(git);
        Map<String, IndexEntry> newTree = readTreeFromCommit(git, targetCommit);

        for (String oldPath : oldTree.keySet()) {
            if (!newTree.containsKey(oldPath)) {
                File toDelete = safeWorktreeFile(project, oldPath);
                if (toDelete.isFile() && !toDelete.delete()) throw new IOException("Could not remove tracked file during checkout: " + oldPath);
            }
        }
        for (Map.Entry<String, IndexEntry> entry : newTree.entrySet()) {
            checkInterrupted();
            File destination = safeWorktreeFile(project, entry.getKey());
            mkdir(destination.getParentFile());
            ObjectData blob = readObject(git, entry.getValue().objectId);
            if (!"blob".equals(blob.type)) throw new IOException("Tree entry does not reference a blob: " + entry.getKey());
            writeBytes(destination, blob.data);
        }
        TreeMap<String, IndexEntry> newIndex = new TreeMap<>(gitPathComparator());
        for (Map.Entry<String, IndexEntry> entry : newTree.entrySet()) {
            File f = safeWorktreeFile(project, entry.getKey());
            IndexEntry old = entry.getValue();
            newIndex.put(entry.getKey(), IndexEntry.fromFileWithMode(entry.getKey(), f, old.objectId, old.mode));
        }
        writeIndex(git, newIndex);
        writeText(new File(git, "HEAD"), "ref: refs/heads/" + branch + "\n");
        return "{\"ok\":true,\"action\":\"checkout\",\"message\":\"Switched branch\",\"branch\":" + quote(branch) + ",\"commit\":" + quote(targetCommit) + "}";
    }

    private static StatusSnapshot statusSnapshot(File project, File git) throws IOException {
        TreeMap<String, IndexEntry> index = readIndex(git);
        Map<String, IndexEntry> head = readHeadTree(git);
        TreeMap<String, File> working = scanFiles(project);
        StatusSnapshot snapshot = new StatusSnapshot();
        for (Map.Entry<String, IndexEntry> entry : index.entrySet()) {
            String path = entry.getKey();
            IndexEntry idx = entry.getValue();
            IndexEntry h = head.get(path);
            if (h == null) snapshot.stagedAdded.add(path);
            else if (!h.objectId.equals(idx.objectId) || !h.mode.equals(idx.mode)) snapshot.stagedModified.add(path);
            File f = working.get(path);
            if (f == null) snapshot.deleted.add(path);
            else if (!hashBlob(f).equals(idx.objectId)) snapshot.modified.add(path);
        }
        for (String path : head.keySet()) if (!index.containsKey(path)) snapshot.stagedDeleted.add(path);
        for (String path : working.keySet()) if (!index.containsKey(path)) snapshot.untracked.add(path);
        return snapshot;
    }

    private static boolean sameTree(Map<String, IndexEntry> left, Map<String, IndexEntry> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (String path : left.keySet()) {
            IndexEntry a = left.get(path), b = right.get(path);
            if (b == null || !a.objectId.equals(b.objectId) || !a.mode.equals(b.mode)) return false;
        }
        return true;
    }

    private static TreeMap<String, File> scanFiles(File project) throws IOException {
        TreeMap<String, File> result = new TreeMap<>(gitPathComparator());
        long[] total = {0L};
        Set<String> seenDirectories = new HashSet<>();
        scanDirectory(project, project, result, total, seenDirectories, 0);
        return result;
    }

    private static void scanDirectory(File project, File directory, TreeMap<String, File> files, long[] total,
                                      Set<String> seenDirectories, int depth) throws IOException {
        checkInterrupted();
        if (depth > MAX_TREE_DEPTH) throw new IOException("Project directory nesting exceeds the Git limit");
        File canonicalDirectory = directory.getCanonicalFile();
        String rootPath = project.getCanonicalPath();
        String canonicalPath = canonicalDirectory.getCanonicalPath();
        if (!canonicalPath.equals(rootPath) && !canonicalPath.startsWith(rootPath + File.separator)) return;
        if (!seenDirectories.add(canonicalPath)) return; // Avoid cycles from an in-root symbolic link.
        File[] children = canonicalDirectory.listFiles();
        if (children == null) throw new IOException("Could not read project directory: " + relativePath(project, canonicalDirectory));
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (".git".equals(child.getName())) continue;
            if (child.getName().isEmpty() || child.getName().equals(".") || child.getName().equals("..") || child.getName().indexOf('\0') >= 0) continue;
            File canonicalChild = child.getCanonicalFile();
            if (!canonicalChild.getPath().equals(rootPath) && !canonicalChild.getPath().startsWith(rootPath + File.separator)) continue;
            if (child.isDirectory()) {
                scanDirectory(project, child, files, total, seenDirectories, depth + 1);
            } else if (child.isFile()) {
                String relative = relativePath(project, child);
                validateGitPath(relative);
                if (child.length() > MAX_FILE_BYTES) throw new IOException("File exceeds the 64 MiB per-file Git limit: " + relative);
                total[0] += child.length();
                if (total[0] > MAX_PROJECT_BYTES) throw new IOException("Project exceeds the 256 MiB local Git limit");
                files.put(relative, child);
                if (files.size() > MAX_FILES) throw new IOException("Project exceeds the 20,000 file local Git limit");
            }
        }
    }

    private static void validateGitPath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || path.indexOf(':') >= 0) {
            throw new IOException("Unsupported file path in project");
        }
        String[] parts = path.split("/", -1);
        if (parts.length > MAX_TREE_DEPTH) throw new IOException("File path is too deeply nested");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals(".git")) throw new IOException("Unsafe project file path");
        }
        if (path.length() > 4096) throw new IOException("Project file path is too long");
    }

    private static File safeWorktreeFile(File project, String path) throws IOException {
        validateGitPath(path);
        File file = new File(project, path).getCanonicalFile();
        String root = project.getCanonicalPath();
        if (!file.getPath().startsWith(root + File.separator)) throw new IOException("Worktree path escapes project root");
        return file;
    }

    private static String relativePath(File root, File file) throws IOException {
        String r = root.getCanonicalPath();
        String f = file.getCanonicalPath();
        if (!f.startsWith(r + File.separator)) throw new IOException("Path is outside project root");
        return f.substring(r.length() + 1).replace(File.separatorChar, '/');
    }

    private static TreeMap<String, IndexEntry> readIndex(File git) throws IOException {
        File file = new File(git, "index");
        TreeMap<String, IndexEntry> result = new TreeMap<>(gitPathComparator());
        if (!file.exists()) return result;
        byte[] bytes = readLimited(file, MAX_OBJECT_BYTES);
        if (bytes.length < 32 || bytes[0] != 'D' || bytes[1] != 'I' || bytes[2] != 'R' || bytes[3] != 'C') throw new IOException("Invalid Git index signature");
        if (readInt(bytes, 4) != 2) throw new IOException("Only Git index version 2 is supported");
        byte[] expected = sha1(Arrays.copyOf(bytes, bytes.length - 20));
        if (!Arrays.equals(expected, Arrays.copyOfRange(bytes, bytes.length - 20, bytes.length))) throw new IOException("Git index checksum mismatch");
        int count = readInt(bytes, 8);
        if (count < 0 || count > MAX_FILES) throw new IOException("Git index entry count exceeds the limit");
        int pos = 12;
        int end = bytes.length - 20;
        for (int i = 0; i < count; i++) {
            int start = pos;
            if (pos + 62 > end) throw new IOException("Truncated Git index entry");
            String mode = Integer.toOctalString(readInt(bytes, pos + 24));
            byte[] oid = Arrays.copyOfRange(bytes, pos + 40, pos + 60);
            int flags = ((bytes[pos + 60] & 0xff) << 8) | (bytes[pos + 61] & 0xff);
            int nameStart = pos + 62;
            int nullAt = nameStart;
            while (nullAt < end && bytes[nullAt] != 0) nullAt++;
            if (nullAt >= end) throw new IOException("Unterminated Git index path");
            int nameLength = nullAt - nameStart;
            if (nameLength > 4095) throw new IOException("Git index path exceeds 4,095 bytes");
            String path = new String(bytes, nameStart, nameLength, StandardCharsets.UTF_8);
            validateGitPath(path);
            int stage = (flags >> 12) & 3;
            if (stage != 0) throw new IOException("Git conflict stages are not supported by the local workflow");
            String objectId = hex(oid);
            if (result.put(path, new IndexEntry(path, objectId, normalizeMode(mode), readInt(bytes, pos + 36))) != null) throw new IOException("Duplicate path in Git index");
            pos = nullAt + 1;
            pos += (8 - ((pos - start) % 8)) % 8;
            if (pos > end) throw new IOException("Invalid Git index padding");
        }
        if (pos != end) throw new IOException("Unexpected trailing data in Git index");
        validateNoPathConflicts(result);
        return result;
    }

    private static void writeIndex(File git, TreeMap<String, IndexEntry> entries) throws IOException {
        if (entries.size() > MAX_FILES) throw new IOException("Too many files in Git index");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[]{'D', 'I', 'R', 'C'});
        out.writeInt(2);
        out.writeInt(entries.size());
        for (Map.Entry<String, IndexEntry> item : entries.entrySet()) {
            String path = item.getKey();
            IndexEntry entry = item.getValue();
            validateGitPath(path);
            byte[] name = path.getBytes(StandardCharsets.UTF_8);
            if (name.length > 4095) throw new IOException("Git index path exceeds 4,095 bytes");
            ByteArrayOutputStream oneBytes = new ByteArrayOutputStream();
            DataOutputStream one = new DataOutputStream(oneBytes);
            File file = entry.file;
            long mtime = file != null && file.exists() ? file.lastModified() / 1000L : 0L;
            long size = file != null && file.exists() ? file.length() : entry.size;
            one.writeInt((int) mtime); one.writeInt(0); // ctime
            one.writeInt((int) mtime); one.writeInt(0); // mtime
            one.writeInt(0); one.writeInt(0); // dev, ino
            one.writeInt(parseMode(entry.mode));
            one.writeInt(0); one.writeInt(0); // uid, gid
            one.writeInt((int) Math.min(Integer.MAX_VALUE, size));
            one.write(unhex(entry.objectId));
            int flags = name.length & 0x0fff;
            one.writeShort(flags);
            one.write(name);
            one.writeByte(0);
            while (oneBytes.size() % 8 != 0) one.writeByte(0);
            one.flush();
            out.write(oneBytes.toByteArray());
        }
        out.flush();
        byte[] withoutChecksum = bytes.toByteArray();
        out.write(sha1(withoutChecksum));
        out.flush();
        writeBytes(new File(git, "index"), bytes.toByteArray());
    }

    private static Map<String, IndexEntry> readHeadTree(File git) throws IOException {
        String commitId = headCommitId(git);
        if (commitId == null) return new TreeMap<>(gitPathComparator());
        return readTreeFromCommit(git, commitId);
    }

    private static Map<String, IndexEntry> readTreeFromCommit(File git, String commitId) throws IOException {
        ObjectData commit = readObject(git, commitId);
        if (!"commit".equals(commit.type)) throw new IOException("HEAD does not point to a commit object");
        String headers = new String(commit.data, StandardCharsets.UTF_8).split("\n\n", 2)[0];
        for (String line : headers.split("\n")) {
            if (line.startsWith("tree ")) {
                Map<String, IndexEntry> tree = readTreeRecursive(git, line.substring(5).trim(), "", 0, new TreeMap<>(gitPathComparator()));
                validateNoPathConflicts(tree);
                return tree;
            }
        }
        throw new IOException("Commit object has no tree");
    }

    private static Map<String, IndexEntry> readTreeRecursive(File git, String treeId, String prefix, int depth,
                                                              TreeMap<String, IndexEntry> result) throws IOException {
        if (depth > MAX_TREE_DEPTH) throw new IOException("Git tree nesting exceeds the limit");
        ObjectData tree = readObject(git, treeId);
        if (!"tree".equals(tree.type)) throw new IOException("Git tree references an invalid object");
        byte[] data = tree.data;
        int pos = 0;
        while (pos < data.length) {
            int space = pos;
            while (space < data.length && data[space] != ' ') space++;
            if (space == data.length) throw new IOException("Malformed Git tree mode");
            String mode = new String(data, pos, space - pos, StandardCharsets.US_ASCII);
            int nul = space + 1;
            while (nul < data.length && data[nul] != 0) nul++;
            if (nul >= data.length || nul + 21 > data.length) throw new IOException("Malformed Git tree entry");
            String name = new String(data, space + 1, nul - space - 1, StandardCharsets.UTF_8);
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) throw new IOException("Unsafe name in Git tree");
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            byte[] idBytes = Arrays.copyOfRange(data, nul + 1, nul + 21);
            String id = hex(idBytes);
            if (mode.equals("40000") || mode.equals("040000") || mode.equals("0040000")) {
                readTreeRecursive(git, id, path, depth + 1, result);
            } else if (mode.equals("100644") || mode.equals("100755")) {
                validateGitPath(path);
                if (result.put(path, new IndexEntry(path, id, mode, 0)) != null) throw new IOException("Duplicate path in Git tree");
                if (result.size() > MAX_FILES) throw new IOException("Git tree exceeds file count limit");
            } else {
                throw new IOException("Git submodules and symlinks are not supported by this local workflow");
            }
            pos = nul + 21;
        }
        return result;
    }

    private static String writeTree(File git, Map<String, IndexEntry> entries) throws IOException {
        validateNoPathConflicts(entries);
        TreeNode root = new TreeNode();
        for (Map.Entry<String, IndexEntry> item : entries.entrySet()) {
            validateGitPath(item.getKey());
            String[] parts = item.getKey().split("/");
            TreeNode node = root;
            for (int i = 0; i < parts.length - 1; i++) node = node.directories.computeIfAbsent(parts[i], ignored -> new TreeNode());
            if (node.files.put(parts[parts.length - 1], item.getValue()) != null) throw new IOException("Duplicate Git tree entry");
        }
        return writeTreeNode(git, root, 0);
    }

    private static String writeTreeNode(File git, TreeNode node, int depth) throws IOException {
        if (depth > MAX_TREE_DEPTH) throw new IOException("Git tree depth exceeds the limit");
        List<TreeItem> items = new ArrayList<>();
        for (Map.Entry<String, IndexEntry> e : node.files.entrySet()) items.add(new TreeItem(e.getKey(), false, e.getValue().mode, e.getValue().objectId));
        for (Map.Entry<String, TreeNode> e : node.directories.entrySet()) items.add(new TreeItem(e.getKey(), true, "40000", writeTreeNode(git, e.getValue(), depth + 1)));
        items.sort((a, b) -> compareTreeNames(a.name, a.directory, b.name, b.directory));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (TreeItem item : items) {
            bytes.write(item.mode.getBytes(StandardCharsets.US_ASCII));
            bytes.write(' ');
            bytes.write(item.name.getBytes(StandardCharsets.UTF_8));
            bytes.write(0);
            bytes.write(unhex(item.objectId));
        }
        return writeObject(git, "tree", bytes.toByteArray());
    }

    private static int compareTreeNames(String a, boolean aDir, String b, boolean bDir) {
        byte[] left = (a + (aDir ? "/" : "\0")).getBytes(StandardCharsets.UTF_8);
        byte[] right = (b + (bDir ? "/" : "\0")).getBytes(StandardCharsets.UTF_8);
        int limit = Math.min(left.length, right.length);
        for (int i = 0; i < limit; i++) {
            int d = (left[i] & 0xff) - (right[i] & 0xff);
            if (d != 0) return d;
        }
        return left.length - right.length;
    }

    private static void validateNoPathConflicts(Map<String, IndexEntry> entries) throws IOException {
        for (String path : entries.keySet()) {
            String parent = path;
            while (parent.contains("/")) {
                parent = parent.substring(0, parent.lastIndexOf('/'));
                if (entries.containsKey(parent)) throw new IOException("Git index/tree has a file-directory path conflict: " + parent);
            }
        }
    }

    private static String headBranch(File git) throws IOException {
        String head = readText(new File(git, "HEAD")).trim();
        if (!head.startsWith("ref: refs/heads/")) return null;
        String branch = head.substring("ref: refs/heads/".length());
        validateBranchName(branch);
        return branch;
    }

    private static String headCommitId(File git) throws IOException {
        String branch = headBranch(git);
        if (branch == null) {
            String head = readText(new File(git, "HEAD")).trim();
            return head.matches("[0-9a-f]{40}") ? head : null;
        }
        return readRef(git, branch);
    }

    private static String readRef(File git, String branch) throws IOException {
        File ref = refFile(git, branch);
        if (!ref.isFile()) return null;
        String id = readText(ref).trim();
        if (!id.matches("[0-9a-f]{40}")) throw new IOException("Invalid Git branch reference");
        return id;
    }

    private static File refFile(File git, String branch) throws IOException {
        validateBranchName(branch);
        File refs = new File(git, "refs/heads").getCanonicalFile();
        File file = new File(refs, branch).getCanonicalFile();
        if (!file.getPath().startsWith(refs.getPath() + File.separator)) throw new IOException("Branch reference path escapes refs/heads");
        return file;
    }

    private static void collectBranches(File root, String prefix, List<String> names, int depth) throws IOException {
        if (depth > 16) throw new IOException("Branch hierarchy too deep");
        File[] children = root.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            String name = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) collectBranches(child, name, names, depth + 1);
            else if (child.isFile() && !child.getName().endsWith(".lock")) {
                validateBranchName(name);
                names.add(name);
                if (names.size() > 500) throw new IOException("Too many local branches");
            }
        }
    }

    private static void validateBranchName(String branch) throws IOException {
        if (branch == null || branch.isEmpty() || branch.length() > 100 || branch.startsWith("-") || branch.startsWith("/") || branch.endsWith("/") || branch.endsWith(".") || branch.contains("..") || branch.contains("//") || branch.contains("@{") || branch.contains("\\") || branch.contains(" ") || branch.contains("\t")) throw new IOException("Invalid local branch name");
        if (branch.equals("@") || branch.equals("HEAD")) throw new IOException("Reserved branch name");
        String[] parts = branch.split("/", -1);
        for (String part : parts) if (part.isEmpty() || part.startsWith(".") || part.endsWith(".lock")) throw new IOException("Invalid branch path component");
        for (int i = 0; i < branch.length(); i++) {
            char c = branch.charAt(i);
            if (c <= 0x20 || c == 0x7f || "~^:?*[".indexOf(c) >= 0) throw new IOException("Invalid character in branch name");
        }
    }

    private static String hashBlob(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Cannot hash a non-file");
        long expected = file.length();
        if (expected > MAX_FILE_BYTES) throw new IOException("File exceeds the 64 MiB per-file Git limit");
        MessageDigest digest = newDigest();
        digest.update(("blob " + expected + "\0").getBytes(StandardCharsets.US_ASCII));
        long total = 0;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES) throw new IOException("File exceeds the 64 MiB per-file Git limit");
                digest.update(buffer, 0, read);
            }
        }
        if (total != expected) throw new IOException("File changed while Git was hashing it");
        return hex(digest.digest());
    }

    private static String writeBlob(File project, File file) throws IOException {
        File git = gitDir(project);
        long expected = file.length();
        if (!file.isFile() || expected > MAX_FILE_BYTES) throw new IOException("File exceeds the 64 MiB per-file Git limit");
        byte[] header = ("blob " + expected + "\0").getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest = newDigest();
        digest.update(header);
        File objects = new File(git, "objects");
        File temp = new File(objects, "tmp-" + Long.toHexString(System.nanoTime()));
        long total = 0;
        try {
            try (OutputStream raw = new FileOutputStream(temp); DeflaterOutputStream compressed = new DeflaterOutputStream(raw); InputStream in = new FileInputStream(file)) {
                compressed.write(header);
                byte[] buffer = new byte[8192]; int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_FILE_BYTES) throw new IOException("File exceeds the 64 MiB per-file Git limit");
                    digest.update(buffer, 0, read);
                    compressed.write(buffer, 0, read);
                }
            }
            if (total != expected) throw new IOException("File changed while Git was staging it");
            String id = hex(digest.digest());
            File target = objectFile(git, id);
            if (target.isFile()) { temp.delete(); return id; }
            mkdir(target.getParentFile());
            if (!temp.renameTo(target)) {
                if (!target.isFile()) throw new IOException("Could not publish Git blob object");
                temp.delete();
            }
            return id;
        } finally { if (temp.exists()) temp.delete(); }
    }

    private static String writeObject(File git, String type, byte[] data) throws IOException {
        if (data.length > MAX_OBJECT_BYTES) throw new IOException("Git object exceeds size limit");
        byte[] header = (type + " " + data.length + "\0").getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest = newDigest();
        digest.update(header); digest.update(data);
        String id = hex(digest.digest());
        File target = objectFile(git, id);
        if (target.isFile()) return id;
        mkdir(target.getParentFile());
        File temp = new File(target.getParentFile(), target.getName() + ".tmp-" + Long.toHexString(System.nanoTime()));
        try {
            try (OutputStream raw = new FileOutputStream(temp); DeflaterOutputStream compressed = new DeflaterOutputStream(raw)) {
                compressed.write(header);
                compressed.write(data);
            }
            if (!temp.renameTo(target)) {
                if (!target.isFile()) throw new IOException("Could not publish Git object");
                temp.delete();
            }
        } finally {
            if (temp.exists()) temp.delete();
        }
        return id;
    }

    private static ObjectData readObject(File git, String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{40}")) throw new IOException("Invalid Git object ID");
        File file = objectFile(git, id);
        if (!file.isFile()) throw new IOException("Git object is missing: " + id);
        try (InputStream in = new InflaterInputStream(new FileInputStream(file)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_OBJECT_BYTES + 128) throw new IOException("Git object is too large");
            }
            byte[] object = out.toByteArray();
            int nul = -1;
            for (int i = 0; i < object.length; i++) if (object[i] == 0) { nul = i; break; }
            if (nul <= 0) throw new IOException("Malformed Git object header");
            String header = new String(object, 0, nul, StandardCharsets.US_ASCII);
            int space = header.indexOf(' ');
            if (space <= 0) throw new IOException("Malformed Git object header");
            String type = header.substring(0, space);
            long declared;
            try { declared = Long.parseLong(header.substring(space + 1)); } catch (NumberFormatException e) { throw new IOException("Malformed Git object size", e); }
            int payloadLength = object.length - nul - 1;
            if (declared != payloadLength) throw new IOException("Git object size mismatch");
            byte[] payload = Arrays.copyOfRange(object, nul + 1, object.length);
            MessageDigest digest = newDigest();
            digest.update(object, 0, object.length);
            if (!hex(digest.digest()).equals(id)) throw new IOException("Git object hash verification failed");
            return new ObjectData(type, payload);
        }
    }

    private static File objectFile(File git, String id) throws IOException {
        File objectRoot = new File(git, "objects").getCanonicalFile();
        File shard = new File(objectRoot, id.substring(0, 2)).getCanonicalFile();
        if (!shard.getParentFile().equals(objectRoot)) throw new IOException("Invalid Git object directory");
        File object = new File(shard, id.substring(2)).getCanonicalFile();
        if (!object.getParentFile().equals(shard)) throw new IOException("Invalid Git object path");
        return object;
    }

    private static byte[] readLimited(File file, long max) throws IOException {
        if (file.length() > max) throw new IOException("File exceeds size limit");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > max) throw new IOException("File exceeds size limit");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String currentTimezone() {
        int offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        char sign = offset < 0 ? '-' : '+';
        offset = Math.abs(offset);
        return String.format(Locale.ROOT, "%c%02d%02d", sign, offset / 60, offset % 60);
    }

    private static long parseTimestamp(String authorLine) {
        if (authorLine == null) return 0;
        String[] parts = authorLine.trim().split("\\s+");
        if (parts.length < 2) return 0;
        try { return Long.parseLong(parts[parts.length - 2]); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).trim();
    }

    private static String normalizeMode(String mode) throws IOException {
        if (mode == null) throw new IOException("Missing Git file mode");
        if (mode.equals("100644") || mode.equals("100755")) return mode;
        try {
            int parsed = Integer.parseInt(mode, 8);
            if (parsed == 33188) return "100644";
            if (parsed == 33261) return "100755";
        } catch (NumberFormatException ignored) { }
        throw new IOException("Unsupported Git file mode");
    }

    private static int parseMode(String mode) throws IOException {
        if ("100644".equals(mode)) return 0100644;
        if ("100755".equals(mode)) return 0100755;
        throw new IOException("Unsupported file mode");
    }

    private static Comparator<String> gitPathComparator() {
        return (a, b) -> compareBytes(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int limit = Math.min(a.length, b.length);
        for (int i = 0; i < limit; i++) {
            int c = (a[i] & 0xff) - (b[i] & 0xff);
            if (c != 0) return c;
        }
        return a.length - b.length;
    }

    private static byte[] sha1(byte[] bytes) { MessageDigest digest = newDigest(); return digest.digest(bytes); }
    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-1"); }
        catch (Exception e) { throw new IllegalStateException("SHA-1 is unavailable", e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
    private static byte[] unhex(String hex) throws IOException {
        if (hex == null || (hex.length() & 1) != 0) throw new IOException("Invalid hex value");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            int high = Character.digit(hex.charAt(i), 16), low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) throw new IOException("Invalid hex value");
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static int readInt(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 4 > bytes.length) throw new IOException("Truncated binary Git data");
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static String stringArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) { if (i > 0) out.append(','); out.append(quote(values.get(i))); }
        return out.append(']').toString();
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Git operation cancelled");
    }
    private static void mkdir(File dir) throws IOException { if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Could not create directory: " + dir.getName()); }
    private static void writeText(File file, String text) throws IOException { writeBytes(file, text.getBytes(StandardCharsets.UTF_8)); }
    private static String readText(File file) throws IOException { return new String(readLimited(file, 1024 * 1024), StandardCharsets.UTF_8); }
    private static void writeBytes(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile(); mkdir(parent);
        File temp = new File(parent, file.getName() + ".tmp-" + Long.toHexString(System.nanoTime()));
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.flush(); out.getFD().sync(); }
            if (!temp.renameTo(file)) {
                if (file.exists() && !file.delete()) throw new IOException("Could not replace file: " + file.getName());
                if (!temp.renameTo(file)) throw new IOException("Could not publish file: " + file.getName());
            }
        } finally { if (temp.exists()) temp.delete(); }
    }

    private static final class ObjectData {
        final String type; final byte[] data;
        ObjectData(String type, byte[] data) { this.type = type; this.data = data; }
    }
    private static final class IndexEntry {
        final String path, objectId, mode; final int size; final File file;
        IndexEntry(String path, String objectId, String mode, int size) { this(path, objectId, mode, size, null); }
        IndexEntry(String path, String objectId, String mode, int size, File file) { this.path = path; this.objectId = objectId; this.mode = mode; this.size = size; this.file = file; }
        static IndexEntry fromFile(String path, File file, String id) { return fromFileWithMode(path, file, id, "100644"); }
        static IndexEntry fromFileWithMode(String path, File file, String id, String mode) { return new IndexEntry(path, id, mode, (int) Math.min(Integer.MAX_VALUE, file.length()), file); }
    }
    private static final class TreeNode {
        final TreeMap<String, TreeNode> directories = new TreeMap<>();
        final TreeMap<String, IndexEntry> files = new TreeMap<>();
    }
    private static final class TreeItem {
        final String name; final boolean directory; final String mode, objectId;
        TreeItem(String name, boolean directory, String mode, String objectId) { this.name = name; this.directory = directory; this.mode = mode; this.objectId = objectId; }
    }
    private static final class StatusSnapshot {
        final List<String> stagedAdded = new ArrayList<>(), stagedModified = new ArrayList<>(), stagedDeleted = new ArrayList<>(), modified = new ArrayList<>(), deleted = new ArrayList<>(), untracked = new ArrayList<>();
        boolean isClean() { return stagedAdded.isEmpty() && stagedModified.isEmpty() && stagedDeleted.isEmpty() && modified.isEmpty() && deleted.isEmpty() && untracked.isEmpty(); }
    }
}
