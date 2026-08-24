// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Identity-scoped source-tree access for the dashboard {@code #project/<id>/files} viewer. List
 * and read share one {@link #servable} predicate; the sandbox root is
 * {@link ProjectIdentity#pathForId} only.
 */
final class WorkspaceFileAccess {

    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_LIST_FILES = 5000;
    static final int MAX_WALK_DEPTH = 32;

    private static final Map<String, String> LANG_BY_EXT = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".kts", "kotlin"),
            Map.entry(".groovy", "groovy"),
            Map.entry(".scala", "scala"),
            Map.entry(".sc", "scala"),
            Map.entry(".toml", "toml"),
            Map.entry(".xml", "xml"),
            Map.entry(".yaml", "yaml"),
            Map.entry(".yml", "yaml"),
            Map.entry(".json", "json"),
            Map.entry(".jsonl", "json"),
            Map.entry(".sql", "sql"),
            Map.entry(".properties", "properties"),
            Map.entry(".sh", "shell"),
            Map.entry(".bash", "shell"),
            Map.entry(".zsh", "shell"),
            Map.entry(".md", "markdown"),
            Map.entry(".markdown", "markdown"),
            // Preview-oriented text (dashboard Preview pane; still UTF-8 sources).
            Map.entry(".mmd", "mermaid"),
            Map.entry(".mermaid", "mermaid"),
            Map.entry(".dot", "graphviz"),
            Map.entry(".gv", "graphviz"),
            Map.entry(".adoc", "asciidoc"),
            Map.entry(".asciidoc", "asciidoc"),
            Map.entry(".d2", "d2"),
            // Binary image kinds — listable + raw-readable; not text-writable.
            Map.entry(".png", "image"),
            Map.entry(".jpg", "image"),
            Map.entry(".jpeg", "image"),
            Map.entry(".gif", "image"),
            Map.entry(".webp", "image"),
            Map.entry(".ico", "image"),
            Map.entry(".svg", "image"),
            Map.entry(".bmp", "image"),
            Map.entry(".avif", "image"));

    private static final Map<String, String> IMAGE_CONTENT_TYPE = Map.ofEntries(
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".ico", "image/x-icon"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".bmp", "image/bmp"),
            Map.entry(".avif", "image/avif"));

    record ListedFile(String path, String lang) {}

    record FileList(Path root, List<ListedFile> files, boolean truncated) {}

    /**
     * {@code encoding} is {@code utf-8}, or {@code iso-8859-1} when the bytes were not valid UTF-8
     *. {@code etag} is the SHA-256 hex of the on-disk bytes (optimistic concurrency for
     * PUT).
     */
    record FileBody(
            Path root, String path, String lang, long bytes, int lines, String content, String encoding, String etag) {}

    record RawBody(Path root, String path, String lang, String contentType, byte[] bytes) {}

    record WrittenBody(Path root, String path, String lang, long bytes, int lines, String etag) {}

    sealed interface ReadResult {
        record Ok(FileBody body) implements ReadResult {}

        record BadRequest(String error) implements ReadResult {}

        record NotFound() implements ReadResult {}

        record TooLarge(long bytes, int maxBytes) implements ReadResult {}

        record Binary() implements ReadResult {}
    }

    sealed interface RawResult {
        record Ok(RawBody body) implements RawResult {}

        record BadRequest(String error) implements RawResult {}

        record NotFound() implements RawResult {}

        record TooLarge(long bytes, int maxBytes) implements RawResult {}
    }

    sealed interface WriteResult {
        record Ok(WrittenBody body) implements WriteResult {}

        record BadRequest(String error) implements WriteResult {}

        record NotFound() implements WriteResult {}

        record TooLarge(long bytes, int maxBytes) implements WriteResult {}

        record NotWritable(String error) implements WriteResult {}

        /** On-disk bytes no longer match the client's {@code etag} (multi-tab / external edit). */
        record Conflict(String currentEtag) implements WriteResult {}

        record Failed(String error) implements WriteResult {}
    }

    /** SHA-256 hex of file bytes — the JSON {@code etag} field and PUT concurrency token. */
    static String etagOf(byte[] bytes) {
        return Hashing.sha256Hex(bytes == null ? new byte[0] : bytes);
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

    static boolean isImageLang(@Nullable String lang) {
        return "image".equals(lang);
    }

    /** Text sources the dashboard may write back via {@code PUT /api/project/file}. */
    static boolean isTextWritable(@Nullable String lang) {
        return lang != null && !isImageLang(lang);
    }

    static @Nullable String imageContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return null;
        return IMAGE_CONTENT_TYPE.get(lower.substring(dot));
    }

    static boolean isModuleRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("jk.toml"))
                || Files.isRegularFile(dir.resolve("pom.xml"))
                || Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    /**
     * Gradle/Mill-style output dir of a module root — not a reserved path segment. JumpKick's
     * own {@code target/} is listable (reports, diagrams, and other allow-listed artifacts). A
     * workspace member named {@code build/} that contains {@code jk.toml} is itself a module root
     * and is not skipped.
     */
    static boolean isSkippedOutputDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null) return false;
        String n = name.toString();
        // target/ is JumpKick's module output and often holds viewable .md / .mmd / etc.
        if (!n.equals("build") && !n.equals("out")) return false;
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

    /**
     * Breadth-first listing bounded at {@link #MAX_LIST_FILES} entries. Truncation trims the
     * deepest leaves, and {@code target/} output before any of them: generated files (test
     * reports, generated sources) fill only the capacity hand-written sources leave over, so a
     * report-heavy workspace cannot crowd them out of the cap. The workspace-root {@code jk.toml}
     * is pre-seeded so the default-open contract survives any truncation. Prune rules match
     * {@link #servable}; ancestors are pruned before descent.
     */
    static FileList list(Path root) throws IOException {
        record Dir(Path path, boolean output) {}
        Path absRoot = root.toAbsolutePath().normalize();
        Path realRoot;
        try {
            realRoot = absRoot.toRealPath();
        } catch (IOException e) {
            realRoot = absRoot;
        }
        List<ListedFile> collected = new ArrayList<>();
        List<ListedFile> outputFiles = new ArrayList<>();
        boolean rootManifest = containedRegularFile(absRoot.resolve("jk.toml"), realRoot);
        if (rootManifest) collected.add(new ListedFile("jk.toml", langOf("jk.toml")));
        boolean truncated = false;
        // Output overflow must not stop the walk — sources found later still list.
        boolean outputOverflow = false;
        // Real-path visited set: in-root directory symlinks are walked (list/read parity,
        // ), and a link pointing at an ancestor would otherwise cycle the BFS.
        Set<Path> visited = new HashSet<>();
        visited.add(realRoot);
        List<Dir> level = List.of(new Dir(absRoot, false));
        for (int depth = 0; depth < MAX_WALK_DEPTH && !level.isEmpty() && !truncated; depth++) {
            List<Dir> next = new ArrayList<>();
            for (Dir dir : level) {
                if (truncated) break;
                try (var entries = Files.newDirectoryStream(dir.path())) {
                    for (Path entry : entries) {
                        Path name = entry.getFileName();
                        String n = name == null ? "" : name.toString();
                        if (n.startsWith(".") || n.equals("node_modules")) continue;
                        BasicFileAttributes attrs;
                        try {
                            attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        } catch (IOException unreadable) {
                            continue;
                        }
                        if (attrs.isDirectory() || (attrs.isSymbolicLink() && Files.isDirectory(entry))) {
                            // Symlinked dirs descend only when their target stays in root
                            // (read() would reject their files otherwise) and only once.
                            if (!isSkippedOutputDir(entry) && descendOnce(entry, realRoot, visited)) {
                                next.add(new Dir(entry, dir.output() || isTargetOutputDir(entry)));
                            }
                            continue;
                        }
                        String lang = langOf(n);
                        if (lang == null) continue;
                        // list/read parity: read() rejects symlinks whose real path
                        // escapes the root, so an escaping link must not appear in the tree only
                        // to 404 on click. Only symlinks pay the real-path check.
                        if (attrs.isSymbolicLink()) {
                            try {
                                if (!Files.isRegularFile(entry)
                                        || !entry.toRealPath().startsWith(absRoot.toRealPath())) {
                                    continue;
                                }
                            } catch (IOException broken) {
                                continue;
                            }
                        }
                        String posix = absRoot.relativize(entry).toString().replace('\\', '/');
                        if (rootManifest && posix.equals("jk.toml")) continue; // pre-seeded
                        if (dir.output()) {
                            // Overflow past the cap can never be listed — drop, and flag.
                            if (outputFiles.size() < MAX_LIST_FILES) {
                                outputFiles.add(new ListedFile(posix, lang));
                            } else {
                                outputOverflow = true;
                            }
                            continue;
                        }
                        if (collected.size() >= MAX_LIST_FILES) {
                            truncated = true;
                            break;
                        }
                        collected.add(new ListedFile(posix, lang));
                    }
                } catch (IOException unreadable) {
                    // skip unreadable dirs, keep walking the rest of the level
                }
            }
            level = next;
        }
        // The depth cap is truncation too: files below it are readable via deep link but
        // invisible here, so the UI must get its hint. Conservative — the unvisited
        // dirs may hold nothing servable.
        if (!level.isEmpty()) truncated = true;
        int leftover = MAX_LIST_FILES - collected.size();
        if (outputOverflow || outputFiles.size() > leftover) truncated = true;
        // BFS order — the leftover slice keeps the shallowest output files, like the main trim.
        collected.addAll(outputFiles.subList(0, Math.min(leftover, outputFiles.size())));
        collected.sort(Comparator.comparing(ListedFile::path));
        return new FileList(absRoot, List.copyOf(collected), truncated);
    }

    /**
     * Module {@code target/} output — listable, but it fills the cap only after every non-output
     * file. A workspace member named {@code target/} that is itself a module root is source, not
     * output.
     */
    private static boolean isTargetOutputDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null || !name.toString().equals("target")) return false;
        Path parent = dir.getParent();
        if (parent == null) return false;
        return isModuleRoot(parent) && !isModuleRoot(dir);
    }

    /**
     * Pre-seed containment: the root manifest gets the same NOFOLLOW + real-path check the BFS
     * applies to every other entry, so a symlinked-out {@code jk.toml} is not listed only to 404
     * on read. An in-root symlinked manifest still pre-seeds.
     */
    private static boolean containedRegularFile(Path file, Path realRoot) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException absent) {
            return false;
        }
        if (attrs.isRegularFile()) return true;
        if (!attrs.isSymbolicLink()) return false;
        try {
            return Files.isRegularFile(file) && file.toRealPath().startsWith(realRoot);
        } catch (IOException broken) {
            return false;
        }
    }

    /**
     * True when {@code dir} should be entered: its real path stays under {@code realRoot} and has
     * not been walked yet this listing (symlink cycles / diamonds visit a tree once).
     */
    private static boolean descendOnce(Path dir, Path realRoot, Set<Path> visited) {
        try {
            Path real = dir.toRealPath();
            return real.startsWith(realRoot) && visited.add(real);
        } catch (IOException broken) {
            return false;
        }
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
        String name = file.getFileName().toString();
        String lang = langOf(name);
        if (lang == null) return new ReadResult.NotFound();
        // Image allow-list entries are binary by nature — JSON body endpoint rejects them; use raw.
        if (isImageLang(lang)) return new ReadResult.Binary();
        // Whole-buffer NUL scan: the file is already in memory, and a late NUL means binary
        // content that would otherwise fail strict UTF-8 and render as Latin-1 mojibake.
        for (byte b : bytes) {
            if (b == 0) return new ReadResult.Binary();
        }
        // Strict decode first: new String(bytes, UTF_8) silently swaps every bad byte
        // for U+FFFD, so a Latin-1 source rendered as mojibake presented as the file's true text.
        // Non-UTF-8 files fall back to ISO-8859-1 (every byte maps) with the encoding flagged so
        // the pane can say so.
        String content;
        String encoding = "utf-8";
        try {
            content = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            content = new String(bytes, StandardCharsets.ISO_8859_1);
            encoding = "iso-8859-1";
        }
        return new ReadResult.Ok(
                new FileBody(absRoot, rel, lang, bytes.length, countLines(content), content, encoding, etagOf(bytes)));
    }

    /**
     * Raw bytes for any servable path (images and text). Same sandbox as {@link #read}; no UTF-8
     * decode. Used by {@code GET /api/project/file/raw} for the Preview image path (token-bearing
     * fetch → blob URL).
     */
    static RawResult readRaw(Path root, @Nullable String rawRel) {
        if (rawRel == null || rawRel.isBlank()) return new RawResult.BadRequest("missing \"path\"");
        String rel = normalizeRel(rawRel);
        if (rel == null) return new RawResult.BadRequest("illegal path");
        Path absRoot = root.toAbsolutePath().normalize();
        if (!servable(absRoot, rel)) return new RawResult.NotFound();
        Path file = absRoot.resolve(rel).normalize();
        if (!file.startsWith(absRoot)) return new RawResult.BadRequest("illegal path");
        if (!Files.isRegularFile(file)) return new RawResult.NotFound();
        Path realFile;
        Path realRoot;
        try {
            realFile = file.toRealPath();
            realRoot = absRoot.toRealPath();
        } catch (IOException e) {
            return new RawResult.NotFound();
        }
        if (!realFile.startsWith(realRoot)) return new RawResult.NotFound();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new RawResult.NotFound();
        }
        if (size > MAX_FILE_BYTES) return new RawResult.TooLarge(size, MAX_FILE_BYTES);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            return new RawResult.NotFound();
        }
        if (bytes.length > MAX_FILE_BYTES) return new RawResult.TooLarge(bytes.length, MAX_FILE_BYTES);
        String name = file.getFileName().toString();
        String lang = langOf(name);
        if (lang == null) return new RawResult.NotFound();
        String contentType = imageContentType(name);
        if (contentType == null) contentType = "application/octet-stream";
        return new RawResult.Ok(new RawBody(absRoot, rel, lang, contentType, bytes));
    }

    /**
     * Replace a text-servable file's contents. Atomic temp+move in the same directory; does not
     * create missing parents. Images and non-servable paths are not writable.
     *
     * <p>When {@code expectedEtag} is non-blank, the current on-disk SHA-256 must match or the
     * write is rejected as {@link WriteResult.Conflict} (last-write-wins is opt-in by omitting
     * etag).
     *
     * <p>{@code encoding} is the charset the client read the file under ({@link ReadResult.Ok}'s
     * {@code encoding} field): null/blank/{@code utf-8} writes UTF-8; {@code iso-8859-1}
     * re-encodes to the original bytes so a save cannot silently transcode a Latin-1 file.
     * Content that no longer fits the declared charset is rejected rather than transcoded.
     */
    static WriteResult write(
            Path root,
            @Nullable String rawRel,
            @Nullable String content,
            @Nullable String expectedEtag,
            @Nullable String encoding) {
        if (rawRel == null || rawRel.isBlank()) return new WriteResult.BadRequest("missing \"path\"");
        if (content == null) return new WriteResult.BadRequest("missing \"content\"");
        String rel = normalizeRel(rawRel);
        if (rel == null) return new WriteResult.BadRequest("illegal path");
        Path absRoot = root.toAbsolutePath().normalize();
        if (!servable(absRoot, rel)) return new WriteResult.NotFound();
        String name = Path.of(rel).getFileName().toString();
        String lang = langOf(name);
        if (!isTextWritable(lang)) {
            return new WriteResult.NotWritable("file type is not text-writable");
        }
        Path file = absRoot.resolve(rel).normalize();
        if (!file.startsWith(absRoot)) return new WriteResult.BadRequest("illegal path");
        if (!Files.isRegularFile(file)) return new WriteResult.NotFound();
        Path realFile;
        Path realRoot;
        try {
            realFile = file.toRealPath();
            realRoot = absRoot.toRealPath();
        } catch (IOException e) {
            return new WriteResult.NotFound();
        }
        if (!realFile.startsWith(realRoot)) return new WriteResult.NotFound();
        if (expectedEtag != null && !expectedEtag.isBlank()) {
            long curSize;
            try {
                curSize = Files.size(file);
            } catch (IOException e) {
                return new WriteResult.NotFound();
            }
            if (curSize > MAX_FILE_BYTES) {
                return new WriteResult.TooLarge(curSize, MAX_FILE_BYTES);
            }
            byte[] current;
            try (InputStream in = Files.newInputStream(file)) {
                current = in.readNBytes(MAX_FILE_BYTES + 1);
            } catch (IOException e) {
                return new WriteResult.NotFound();
            }
            if (current.length > MAX_FILE_BYTES) {
                return new WriteResult.TooLarge(current.length, MAX_FILE_BYTES);
            }
            String disk = etagOf(current);
            if (!disk.equalsIgnoreCase(expectedEtag.trim())) {
                return new WriteResult.Conflict(disk);
            }
        }
        String charset = (encoding == null || encoding.isBlank())
                ? "utf-8"
                : encoding.trim().toLowerCase(Locale.ROOT);
        byte[] bytes;
        switch (charset) {
            case "utf-8" -> bytes = content.getBytes(StandardCharsets.UTF_8);
            case "iso-8859-1" -> {
                try {
                    ByteBuffer encoded = StandardCharsets.ISO_8859_1
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(content));
                    bytes = new byte[encoded.remaining()];
                    encoded.get(bytes);
                } catch (CharacterCodingException e) {
                    return new WriteResult.BadRequest("content contains characters not representable in iso-8859-1");
                }
            }
            default -> {
                return new WriteResult.BadRequest("unsupported encoding: " + charset);
            }
        }
        if (bytes.length > MAX_FILE_BYTES) {
            return new WriteResult.TooLarge(bytes.length, MAX_FILE_BYTES);
        }
        Path dir = file.getParent();
        if (dir == null) return new WriteResult.Failed("no parent directory");
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, ".jk-write-", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            return new WriteResult.Failed(e.getMessage() == null ? "write failed" : e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
        return new WriteResult.Ok(
                new WrittenBody(absRoot, rel, lang, bytes.length, countLines(content), etagOf(bytes)));
    }

    /** Convenience overload — UTF-8, no concurrency token (last-write-wins). */
    static WriteResult write(Path root, @Nullable String rawRel, @Nullable String content) {
        return write(root, rawRel, content, null, null);
    }

    /** Convenience overload — UTF-8. */
    static WriteResult write(
            Path root, @Nullable String rawRel, @Nullable String content, @Nullable String expectedEtag) {
        return write(root, rawRel, content, expectedEtag, null);
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
