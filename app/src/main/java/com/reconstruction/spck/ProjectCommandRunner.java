package com.reconstruction.spck;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Restricted project console. It intentionally does NOT invoke /bin/sh or execute arbitrary
 * processes. Only the explicit built-in commands below can read or mutate a project.
 */
final class ProjectCommandRunner {
    private static final int MAX_COMMAND_CHARS = 1024;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final long MAX_COPY_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_FIND_RESULTS = 2000;
    private static final int MAX_DEPTH = 50;

    private ProjectCommandRunner() { }

    static Result execute(File projectDirectory, String commandLine) throws IOException {
        File project = projectDirectory.getCanonicalFile();
        if (!project.isDirectory() || project.getName().startsWith(".")) throw new IOException("Project folder is not available");
        String command = commandLine == null ? "" : commandLine.trim();
        if (command.length() > MAX_COMMAND_CHARS) throw new IOException("Command exceeds the 1,024 character limit");
        List<String> args = tokenize(command);
        if (args.isEmpty()) return new Result(true, "", "");
        String op = args.get(0).toLowerCase(Locale.ROOT);
        switch (op) {
            case "help": return new Result(true, op, "Available built-ins (not a general shell):\nhelp\npwd\nls [path]\ncat <file>\nhead <file> [lines]\nfind [name-fragment]\nfind <path> <name-fragment>\nmkdir <path>\ntouch <file>\ncp <source-file> <target-file>\nmv <source-file> <target-file>\nrm <file>\nclear\n\nNo pipes, redirections, shell variables, scripts, or arbitrary process execution are supported.");
            case "pwd": requireCount(args, 1, 1); return new Result(true, op, "/documents/" + project.getName());
            case "ls": return ls(project, args);
            case "cat": return cat(project, args);
            case "head": return head(project, args);
            case "find": return find(project, args);
            case "mkdir": return mkdir(project, args);
            case "touch": return touch(project, args);
            case "cp": return copy(project, args);
            case "mv": return move(project, args);
            case "rm": return remove(project, args);
            case "clear": requireCount(args, 1, 1); return new Result(true, op, "");
            default: throw new IOException("Command not allowed. Type 'help' to see supported project commands.");
        }
    }

    private static Result ls(File root, List<String> args) throws IOException {
        requireCount(args, 1, 2);
        File target = resolve(root, args.size() == 2 ? args.get(1) : ".", false);
        if (target.isFile()) return new Result(true, "ls", target.getName());
        if (!target.isDirectory()) throw new IOException("Directory does not exist");
        File[] children = target.listFiles();
        if (children == null) throw new IOException("Could not read directory");
        Arrays.sort(children, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
        StringBuilder output = new StringBuilder();
        int count = 0;
        for (File child : children) {
            if (isGitMetadata(root, child)) continue;
            if (count++ > 0) output.append('\n');
            output.append(child.getName());
            if (child.isDirectory()) output.append('/');
            if (count >= MAX_FIND_RESULTS) { output.append("\n[output limit reached]"); break; }
        }
        return new Result(true, "ls", output.toString());
    }

    private static Result cat(File root, List<String> args) throws IOException {
        requireCount(args, 2, 2);
        File file = resolve(root, args.get(1), true);
        if (!file.isFile()) throw new IOException("File does not exist");
        byte[] bytes = readLimited(file, MAX_TEXT_BYTES);
        if (containsNul(bytes)) return new Result(true, "cat", "[binary file omitted: " + bytes.length + " bytes]");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > 40_000) text = text.substring(0, 40_000) + "\n[output truncated]";
        return new Result(true, "cat", text);
    }

    private static Result head(File root, List<String> args) throws IOException {
        requireCount(args, 2, 3);
        int lines = 10;
        if (args.size() == 3) {
            try { lines = Integer.parseInt(args.get(2)); } catch (NumberFormatException e) { throw new IOException("Line count must be an integer"); }
            if (lines < 1 || lines > 200) throw new IOException("Line count must be between 1 and 200");
        }
        File file = resolve(root, args.get(1), true);
        if (!file.isFile()) throw new IOException("File does not exist");
        byte[] bytes = readLimited(file, MAX_TEXT_BYTES);
        if (containsNul(bytes)) throw new IOException("head does not print binary files");
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = text.split("\n", -1);
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < Math.min(lines, parts.length); i++) {
            if (i > 0) output.append('\n');
            output.append(parts[i]);
        }
        return new Result(true, "head", output.toString());
    }

    private static Result find(File root, List<String> args) throws IOException {
        requireCount(args, 1, 3);
        String path = ".";
        String filter = "";
        if (args.size() == 2) filter = args.get(1);
        else if (args.size() == 3) { path = args.get(1); filter = args.get(2); }
        File start = resolve(root, path, false);
        if (!start.exists()) throw new IOException("Start path does not exist");
        List<String> matches = new ArrayList<>();
        collectMatches(root, start, filter.toLowerCase(Locale.ROOT), matches, 0);
        Collections.sort(matches);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) { if (i > 0) out.append('\n'); out.append(matches.get(i)); }
        if (matches.size() >= MAX_FIND_RESULTS) out.append("\n[output limit reached]");
        return new Result(true, "find", out.toString());
    }

    private static void collectMatches(File root, File directory, String filter, List<String> matches, int depth) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Command cancelled");
        if (depth > MAX_DEPTH || matches.size() >= MAX_FIND_RESULTS) return;
        if (isGitMetadata(root, directory)) return;
        if (directory.isFile()) {
            addMatch(root, directory, filter, matches);
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Could not read directory");
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (isGitMetadata(root, child)) continue;
            File canonical = child.getCanonicalFile();
            String base = root.getCanonicalPath();
            if (!canonical.getPath().equals(base) && !canonical.getPath().startsWith(base + File.separator)) continue;
            addMatch(root, child, filter, matches);
            if (child.isDirectory()) collectMatches(root, child, filter, matches, depth + 1);
            if (matches.size() >= MAX_FIND_RESULTS) return;
        }
    }

    private static void addMatch(File root, File file, String filter, List<String> matches) throws IOException {
        String name = file.getName();
        if (filter.isEmpty() || name.toLowerCase(Locale.ROOT).contains(filter)) {
            String relative = relativePath(root, file);
            if (!relative.isEmpty()) matches.add(relative + (file.isDirectory() ? "/" : ""));
        }
    }

    private static Result mkdir(File root, List<String> args) throws IOException {
        requireCount(args, 2, 2);
        File directory = resolve(root, args.get(1), true);
        if (directory.exists()) throw new IOException("Path already exists");
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Could not create directory");
        return new Result(true, "mkdir", "Created directory " + relativePath(root, directory));
    }

    private static Result touch(File root, List<String> args) throws IOException {
        requireCount(args, 2, 2);
        File file = resolve(root, args.get(1), true);
        if (file.isDirectory()) throw new IOException("Path is a directory");
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent == null || !parent.isDirectory()) throw new IOException("Parent directory does not exist; use mkdir first");
            try (FileOutputStream created = new FileOutputStream(file)) { created.getFD().sync(); }
        } else if (!file.setLastModified(System.currentTimeMillis())) {
            throw new IOException("Could not update file timestamp");
        }
        return new Result(true, "touch", "Touched " + relativePath(root, file));
    }

    private static Result copy(File root, List<String> args) throws IOException {
        requireCount(args, 3, 3);
        File source = resolve(root, args.get(1), true);
        File destination = resolve(root, args.get(2), true);
        if (!source.isFile()) throw new IOException("Source file does not exist");
        if (destination.exists()) throw new IOException("Destination already exists");
        if (source.length() > MAX_COPY_BYTES) throw new IOException("File exceeds the 64 MiB copy limit");
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Destination parent does not exist");
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192]; long total = 0; int count;
            while ((count = in.read(buffer)) != -1) { total += count; if (total > MAX_COPY_BYTES) throw new IOException("File exceeds the 64 MiB copy limit"); out.write(buffer, 0, count); }
            out.getFD().sync();
        } catch (IOException e) { destination.delete(); throw e; }
        return new Result(true, "cp", "Copied " + relativePath(root, source) + " to " + relativePath(root, destination));
    }

    private static Result move(File root, List<String> args) throws IOException {
        requireCount(args, 3, 3);
        File source = resolve(root, args.get(1), true);
        File destination = resolve(root, args.get(2), true);
        if (!source.isFile()) throw new IOException("Source file does not exist");
        if (destination.exists()) throw new IOException("Destination already exists");
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Destination parent does not exist");
        if (!source.renameTo(destination)) {
            copy(root, Arrays.asList("cp", args.get(1), args.get(2)));
            if (!source.delete()) { destination.delete(); throw new IOException("Copied file but could not remove original"); }
        }
        return new Result(true, "mv", "Moved " + args.get(1) + " to " + args.get(2));
    }

    private static Result remove(File root, List<String> args) throws IOException {
        requireCount(args, 2, 2);
        File file = resolve(root, args.get(1), true);
        if (!file.exists()) throw new IOException("File does not exist");
        if (!file.isFile()) throw new IOException("rm only removes a single file; folders are protected");
        if (!file.delete()) throw new IOException("Could not delete file");
        return new Result(true, "rm", "Removed " + args.get(1));
    }

    private static File resolve(File root, String rawPath, boolean allowCreateLeaf) throws IOException {
        if (rawPath == null || rawPath.isEmpty()) throw new IOException("Path is required");
        if (rawPath.startsWith("/") || rawPath.startsWith("~") || rawPath.indexOf('\\') >= 0 || rawPath.indexOf(':') >= 0 || rawPath.indexOf('\0') >= 0) throw new IOException("Use a relative path inside the selected project");
        String[] parts = rawPath.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                if (part.equals(".") && rawPath.equals(".")) continue;
                throw new IOException("Unsafe path component");
            }
            if (part.equalsIgnoreCase(".git")) throw new IOException("Direct .git metadata access is blocked");
        }
        File target = new File(root, rawPath).getCanonicalFile();
        String rootPath = root.getCanonicalPath();
        if (!target.equals(root) && !target.getPath().startsWith(rootPath + File.separator)) throw new IOException("Path escapes project root");
        if (!target.exists() && !allowCreateLeaf) throw new IOException("Path does not exist");
        File current = target;
        while (current != null && !current.equals(root)) {
            String canonical = current.getCanonicalPath();
            if (!canonical.equals(rootPath) && !canonical.startsWith(rootPath + File.separator)) throw new IOException("Path escapes project root");
            current = current.getParentFile();
        }
        return target;
    }

    private static boolean isGitMetadata(File root, File candidate) throws IOException {
        return ".git".equals(candidate.getName())
                || candidate.getCanonicalFile().equals(new File(root, ".git").getCanonicalFile());
    }

    private static String relativePath(File root, File target) throws IOException {
        String rootPath = root.getCanonicalPath(), targetPath = target.getCanonicalPath();
        if (targetPath.equals(rootPath)) return "";
        if (!targetPath.startsWith(rootPath + File.separator)) throw new IOException("Path escapes project root");
        return targetPath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static List<String> tokenize(String input) throws IOException {
        if (input.isEmpty()) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean tokenStarted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote == 0 && (c == ';' || c == '|' || c == '&' || c == '>' || c == '<' || c == '$' || c == '`')) {
                throw new IOException("Shell operators and expansions are not supported");
            }
            if (quote == 0 && (c == '\'' || c == '"')) { quote = c; tokenStarted = true; continue; }
            if (quote != 0 && c == quote) { quote = 0; tokenStarted = true; continue; }
            if (quote == 0 && Character.isWhitespace(c)) {
                if (tokenStarted) { result.add(token.toString()); token.setLength(0); tokenStarted = false; }
            } else {
                if (c == '\0') throw new IOException("NUL is not allowed in commands");
                token.append(c); tokenStarted = true;
            }
        }
        if (quote != 0) throw new IOException("Unterminated quoted argument");
        if (tokenStarted) result.add(token.toString());
        return result;
    }

    private static void requireCount(List<String> args, int min, int max) throws IOException {
        if (args.size() < min || args.size() > max) throw new IOException("Wrong number of arguments. Type 'help' for command usage.");
    }

    private static byte[] readLimited(File file, int max) throws IOException {
        if (file.length() > max) throw new IOException("Text file exceeds the 1 MiB output limit");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int read; long total = 0;
            while ((read = in.read(buffer)) != -1) { total += read; if (total > max) throw new IOException("Text file exceeds the 1 MiB output limit"); out.write(buffer, 0, read); }
            return out.toByteArray();
        }
    }

    private static boolean containsNul(byte[] bytes) { for (byte b : bytes) if (b == 0) return true; return false; }

    static final class Result {
        final boolean ok; final String command; final String output;
        Result(boolean ok, String command, String output) { this.ok = ok; this.command = command; this.output = output; }
        String toJson() { return "{\"ok\":" + ok + ",\"command\":" + quote(command) + ",\"output\":" + quote(output) + "}"; }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
