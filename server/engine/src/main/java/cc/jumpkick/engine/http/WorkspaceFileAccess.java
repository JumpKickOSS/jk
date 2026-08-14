// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.builds.ProjectIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Identity-scoped source-tree access for the dashboard {@code #project/<id>/files} viewer. List
 * and read share one {@link #servable} predicate; the sandbox root is
 * {@link ProjectIdentity#pathForId} only.
 */
final class WorkspaceFileAccess {

    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_LIST_FILES = 2000;
    static final int MAX_WALK_DEPTH = 32;
    static final int BINARY_PROBE_BYTES = 8192;

    private static final Map<String, String> LANG_BY_EXT = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".kts", "kotlin"),
            Map.entry(".groovy", "groovy"),
            Map.entry(".toml", "toml"),
            Map.entry(".json", "json"),
            Map.entry(".jsonl", "json"),
            Map.entry(".md", "markdown"),
            Map.entry(".markdown", "markdown"));

    record ListedFile(String path, String lang) {}

    record FileList(Path root, List<ListedFile> files, boolean truncated) {}

    record FileBody(Path root, String path, String lang, long bytes, int lines, String content) {}

    sealed interface ReadResult {
        record Ok(FileBody body) implements ReadResult {}

        record BadRequest(String error) implements ReadResult {}

        record NotFound() implements ReadResult {}

        record TooLarge(long bytes, int maxBytes) implements ReadResult {}

        record Binary() implements ReadResult {}
    }

    private WorkspaceFileAccess() {}

    static Optional<Path> resolveRoot(@Nullable String projectId) {
        if (projectId == null || projectId.isBlank()) return Optional.empty();
        String id = projectId.trim();
        if (!ProjectIdentity.isValidId(id)) return Optional.empty();
        return ProjectIdentity.pathForId(id).filter(Files::isDirectory);
    }

    /**
     * Workspace-relative POSIX path, or {@code null} when the string is a traversal / absolute /
     * NUL / backslash. Blank is {@code null} too — the handler maps that to 400 missing path.
     */
    static @Nullable String normalizeRel(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0) return null;
        if (raw.startsWith("/") || Path.of(raw).isAbsolute()) return null;
        String[] segs = raw.split("/", -1);
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) return null;
        }
        return String.join("/", segs);
    }

    static @Nullable String langOf(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return null;
        return LANG_BY_EXT.get(lower.substring(dot));
    }

    static boolean isModuleRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("jk.toml"))
                || Files.isRegularFile(dir.resolve("pom.xml"))
                || Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    /**
     * Output dir of a module root — not a reserved path segment. A workspace member named
     * {@code build/} that contains {@code jk.toml} is itself a module root and is not skipped.
     */
    static boolean isSkippedOutputDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null) return false;
        String n = name.toString();
        if (!n.equals("target") && !n.equals("build") && !n.equals("out")) return false;
        Path parent = dir.getParent();
        if (parent == null) return false;
        return isModuleRoot(parent) && !isModuleRoot(dir);
    }

    /** Shared by list and read — hidden / output / unknown extension are not distinguishable. */
    static boolean servable(Path root, String rel) {
        String[] segs = rel.split("/", -1);
        if (segs.length == 0) return false;
        for (String s : segs) {
            if (s.startsWith(".")) return false;
            if (s.equals("node_modules")) return false;
        }
        if (langOf(segs[segs.length - 1]) == null) return false;
        Path ancestor = root;
        for (int i = 0; i < segs.length - 1; i++) {
            ancestor = ancestor.resolve(segs[i]);
            if (isSkippedOutputDir(ancestor)) return false;
        }
        return true;
    }

    static FileList list(Path root) throws IOException {
        Path absRoot = root.toAbsolutePath().normalize();
        List<ListedFile> collected = new ArrayList<>();
        Files.walkFileTree(absRoot, EnumSet.noneOf(FileVisitOption.class), MAX_WALK_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(absRoot)) return FileVisitResult.CONTINUE;
                Path name = dir.getFileName();
                String n = name == null ? "" : name.toString();
                if (n.startsWith(".") || n.equals("node_modules") || isSkippedOutputDir(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel;
                try {
                    rel = absRoot.relativize(file);
                } catch (IllegalArgumentException e) {
                    return FileVisitResult.CONTINUE;
                }
                String posix = rel.toString().replace('\\', '/');
                String lang = langOf(file.getFileName().toString());
                if (lang != null && servable(absRoot, posix)) {
                    collected.add(new ListedFile(posix, lang));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        collected.sort(Comparator.comparing(ListedFile::path));
        boolean truncated = collected.size() > MAX_LIST_FILES;
        List<ListedFile> files = truncated ? List.copyOf(collected.subList(0, MAX_LIST_FILES)) : List.copyOf(collected);
        return new FileList(absRoot, files, truncated);
    }

    static ReadResult read(Path root, @Nullable String rawRel) {
        if (rawRel == null || rawRel.isBlank()) return new ReadResult.BadRequest("missing \"path\"");
        String rel = normalizeRel(rawRel);
        if (rel == null) return new ReadResult.BadRequest("illegal path");
        Path absRoot = root.toAbsolutePath().normalize();
        if (!servable(absRoot, rel)) return new ReadResult.NotFound();
        Path file = absRoot.resolve(rel).normalize();
        if (!file.startsWith(absRoot)) return new ReadResult.BadRequest("illegal path");
        if (!Files.isRegularFile(file)) return new ReadResult.NotFound();
        Path realFile;
        Path realRoot;
        try {
            realFile = file.toRealPath();
            realRoot = absRoot.toRealPath();
        } catch (IOException e) {
            return new ReadResult.NotFound();
        }
        if (!realFile.startsWith(realRoot)) return new ReadResult.NotFound();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new ReadResult.NotFound();
        }
        if (size > MAX_FILE_BYTES) return new ReadResult.TooLarge(size, MAX_FILE_BYTES);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            return new ReadResult.NotFound();
        }
        if (bytes.length > MAX_FILE_BYTES) return new ReadResult.TooLarge(bytes.length, MAX_FILE_BYTES);
        int probe = Math.min(BINARY_PROBE_BYTES, bytes.length);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) return new ReadResult.Binary();
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String name = file.getFileName().toString();
        String lang = langOf(name);
        if (lang == null) return new ReadResult.NotFound();
        return new ReadResult.Ok(new FileBody(absRoot, rel, lang, bytes.length, countLines(content), content));
    }

    /** Split on {@code \n}; drop the last empty segment from a trailing newline. */
    static int countLines(String content) {
        if (content.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') n++;
        }
        if (content.charAt(content.length() - 1) == '\n') n--;
        return Math.max(0, n);
    }
}
