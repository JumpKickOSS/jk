// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.lock.ProjectIds;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Identity-scoped source-tree access for the dashboard {@code #project/<id>/files} viewer: one
 * file read, raw read and write. {@link WorkspaceTree} lists directories and finds paths over the
 * same {@link #servable} predicate. The sandbox root is one of the checkouts {@code identity.toml}
 * records for the id ({@link #resolveRoot}), never a caller-supplied tree.
 *
 * <p>Every regular file is servable except hidden entries, {@code node_modules} and key material
 * ({@link #isKeyMaterial}). The extension map names a language for highlighting and Preview, and
 * says which files are images or known binaries; it does not decide what is served. A read decides
 * text from the bytes: a NUL is {@link ReadResult.Binary}.
 */
final class WorkspaceFileAccess {

    static final int MAX_FILE_BYTES = 1024 * 1024;

    /** The language of a file nothing names: highlighted as plain text, written as text. */
    static final String TEXT = "text";

    /** A known binary format: listed, never read as text or written. */
    static final String BINARY = "binary";

    private static final Map<String, String> LANG_BY_EXT = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".kts", "kotlin"),
            Map.entry(".groovy", "groovy"),
            Map.entry(".gradle", "groovy"),
            Map.entry(".scala", "scala"),
            Map.entry(".sc", "scala"),
            Map.entry(".toml", "toml"),
            Map.entry(".xml", "xml"),
            Map.entry(".xsd", "xml"),
            Map.entry(".xsl", "xml"),
            Map.entry(".xslt", "xml"),
            Map.entry(".wsdl", "xml"),
            Map.entry(".yaml", "yaml"),
            Map.entry(".yml", "yaml"),
            Map.entry(".json", "json"),
            Map.entry(".jsonl", "json"),
            Map.entry(".sql", "sql"),
            Map.entry(".properties", "properties"),
            Map.entry(".ini", "ini"),
            Map.entry(".cfg", "ini"),
            Map.entry(".conf", "ini"),
            Map.entry(".sh", "shell"),
            Map.entry(".bash", "shell"),
            Map.entry(".zsh", "shell"),
            Map.entry(".bat", "bat"),
            Map.entry(".cmd", "bat"),
            Map.entry(".ps1", "powershell"),
            Map.entry(".js", "javascript"),
            Map.entry(".mjs", "javascript"),
            Map.entry(".cjs", "javascript"),
            Map.entry(".jsx", "javascript"),
            Map.entry(".ts", "typescript"),
            Map.entry(".mts", "typescript"),
            Map.entry(".cts", "typescript"),
            Map.entry(".tsx", "typescript"),
            Map.entry(".css", "css"),
            Map.entry(".scss", "scss"),
            Map.entry(".less", "less"),
            Map.entry(".html", "html"),
            Map.entry(".htm", "html"),
            Map.entry(".proto", "protobuf"),
            Map.entry(".graphql", "graphql"),
            Map.entry(".gql", "graphql"),
            Map.entry(".py", "python"),
            Map.entry(".rb", "ruby"),
            Map.entry(".go", "go"),
            Map.entry(".rs", "rust"),
            Map.entry(".c", "c"),
            Map.entry(".h", "c"),
            Map.entry(".cpp", "cpp"),
            Map.entry(".hpp", "cpp"),
            Map.entry(".cs", "csharp"),
            Map.entry(".swift", "swift"),
            Map.entry(".csv", "csv"),
            Map.entry(".tsv", "tsv"),
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
            // Images: listed, raw-readable for Preview, not text.
            Map.entry(".png", "image"),
            Map.entry(".jpg", "image"),
            Map.entry(".jpeg", "image"),
            Map.entry(".gif", "image"),
            Map.entry(".webp", "image"),
            Map.entry(".ico", "image"),
            Map.entry(".svg", "image"),
            Map.entry(".bmp", "image"),
            Map.entry(".avif", "image"));

    /** Files named for their role rather than their format. Lower-cased. */
    private static final Map<String, String> LANG_BY_NAME = Map.of(
            "dockerfile", "dockerfile",
            "containerfile", "dockerfile",
            "jenkinsfile", "groovy",
            "gemfile", "ruby",
            "vagrantfile", "ruby");

    /** Formats that are never text: listed so the tree is complete, refused as text before a read. */
    private static final Set<String> BINARY_EXT = Set.of(
            ".class", ".jar", ".war", ".ear", ".jmod", ".zip", ".gz", ".tgz", ".bz2", ".xz", ".zst", ".7z", ".tar",
            ".so", ".dylib", ".dll", ".exe", ".o", ".a", ".lib", ".obj", ".bin", ".pdf", ".woff", ".woff2", ".ttf",
            ".otf", ".eot", ".mp3", ".mp4", ".mov", ".wav", ".ogg", ".webm", ".db", ".sqlite", ".hprof", ".jfr", ".pyc",
            ".dat");

    /**
     * Key material: private keys and keystores. Never listed, read or written, whatever the token
     * may otherwise reach; a key file has no business on a dashboard.
     */
    private static final Set<String> KEY_EXT =
            Set.of(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx", ".ppk");

    private static final List<String> KEY_NAME_PREFIXES = List.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");

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

    /**
     * {@code encoding} is {@code utf-8}, or {@code iso-8859-1} when the bytes were not valid UTF-8
     *. {@code etag} is the SHA-256 hex of the on-disk bytes (optimistic concurrency for
     * PUT).
     */
    record FileBody(
            Path root, String path, String lang, long bytes, int lines, String content, String encoding, String etag) {}

    record RawBody(Path root, String path, String lang, String contentType, byte[] bytes) {}

    record WrittenBody(Path root, String path, @Nullable String lang, long bytes, int lines, String etag) {}

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

    /** Which checkout of a project id a file request works in. */
    sealed interface Root {
        record Ok(Path root) implements Root {}

        /** The id is malformed, unknown, or records no checkout that still exists. */
        record Unknown() implements Root {}

        /** Several live checkouts and no {@code dir} to pick one. */
        record Ambiguous(List<ProjectIdentity.Checkout> checkouts) implements Root {}

        /** {@code dir} is none of the id's live checkouts. */
        record NotACheckout(String dir, List<ProjectIdentity.Checkout> checkouts) implements Root {}
    }

    /**
     * The sandbox root for {@code projectId}: its one live checkout, or the one {@code dir} names
     * among several (real-path compared). Never a directory the id does not record — a tree that
     * merely contains a {@code jk.toml} is not enough.
     */
    static Root resolveRoot(@Nullable String projectId, @Nullable String dir) {
        if (projectId == null || projectId.isBlank()) return new Root.Unknown();
        String id = projectId.trim();
        if (!ProjectIds.isValid(id)) return new Root.Unknown();
        List<ProjectIdentity.Checkout> checkouts = ProjectIdentity.checkoutsForId(id);
        if (checkouts.isEmpty()) return new Root.Unknown();
        if (dir != null && !dir.isBlank()) {
            return ProjectIdentity.selectCheckout(checkouts, dir)
                    .<Root>map(c -> new Root.Ok(c.path()))
                    .orElseGet(() -> new Root.NotACheckout(dir, checkouts));
        }
        if (checkouts.size() == 1) return new Root.Ok(checkouts.getFirst().path());
        return new Root.Ambiguous(checkouts);
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

    /**
     * The language a file is shown in: by name for role-named files ({@code Dockerfile}), else by
     * extension, else {@link #TEXT}; {@link #BINARY} for a known binary format. Never null.
     */
    static String langOf(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        String byName = LANG_BY_NAME.get(lower);
        if (byName != null) return byName;
        String ext = extension(lower);
        if (ext == null) return TEXT;
        if (BINARY_EXT.contains(ext)) return BINARY;
        return LANG_BY_EXT.getOrDefault(ext, TEXT);
    }

    private static @Nullable String extension(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        return dot <= 0 ? null : lowerName.substring(dot);
    }

    static boolean isImageLang(@Nullable String lang) {
        return "image".equals(lang);
    }

    /** Whether a file of this language can be text: not an image, not a known binary. */
    static boolean isTextLang(@Nullable String lang) {
        return lang != null && !isImageLang(lang) && !BINARY.equals(lang);
    }

    /** Text sources the dashboard may write back via {@code PUT /api/project/file}. */
    static boolean isTextWritable(@Nullable String lang) {
        return isTextLang(lang);
    }

    /** A private key or keystore, by extension or by the SSH key names. */
    static boolean isKeyMaterial(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        String ext = extension(lower);
        if (ext != null && KEY_EXT.contains(ext)) return true;
        for (String prefix : KEY_NAME_PREFIXES) {
            if (lower.startsWith(prefix) && !lower.endsWith(".pub")) return true;
        }
        return false;
    }

    static @Nullable String imageContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return null;
        return IMAGE_CONTENT_TYPE.get(lower.substring(dot));
    }

    static boolean isModuleRoot(Path dir) {
        return Files.isRegularFile(dir.resolve(ManifestPaths.MANIFEST))
                || Files.isRegularFile(dir.resolve("pom.xml"))
                || Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    /**
     * Gradle/Mill-style output dir of a module root — not a reserved path segment. Listed and
     * read like any other directory; the tree marks it as output. A workspace member named {@code
     * build/} that contains {@code jk.toml} is itself a module root, not output.
     */
    static boolean isBuildOutputDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null) return false;
        String n = name.toString();
        if (!n.equals("build") && !n.equals("out")) return false;
        Path parent = dir.getParent();
        if (parent == null) return false;
        return isModuleRoot(parent) && !isModuleRoot(dir);
    }

    /**
     * Module {@code target/} output. A workspace member named {@code target/} that is itself a
     * module root is source, not output.
     */
    static boolean isTargetOutputDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null || !name.toString().equals(BuildLayout.TARGET)) return false;
        Path parent = dir.getParent();
        if (parent == null) return false;
        return isModuleRoot(parent) && !isModuleRoot(dir);
    }

    /** A module's build output, JumpKick's {@code target/} or a Gradle-style {@code build/} / {@code out/}. */
    static boolean isOutputDir(Path dir) {
        return isTargetOutputDir(dir) || isBuildOutputDir(dir);
    }

    /** Whether a directory entry's name is ever shown: not hidden, not {@code node_modules}. */
    static boolean visibleName(String name) {
        return !name.isEmpty() && !name.startsWith(".") && !name.equals("node_modules");
    }

    /**
     * Shared by the tree, find, read and write: no hidden segment, no {@code node_modules}, and the
     * file is not key material. A refused path reads as absent.
     */
    static boolean servable(Path root, String rel) {
        String[] segs = rel.split("/", -1);
        if (segs.length == 0) return false;
        for (String s : segs) {
            if (!visibleName(s)) return false;
        }
        return !isKeyMaterial(segs[segs.length - 1]);
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
        String lang = langOf(file.getFileName().toString());
        // Images and known binaries are not text whatever their bytes; images read through raw.
        if (!isTextLang(lang)) return new ReadResult.Binary();
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
     * Raw bytes for any servable path. Same sandbox as {@link #read}; no UTF-8
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
        // Compare-then-write is atomic per real path: the handlers run one virtual thread per
        // request, so two tabs (or a tab plus an agent) PUTting the same file with the same etag
        // could both pass the comparison and the second silently clobbered the first — the exact
        // lost update the etag exists to prevent. Striped monitors (never a clear-on-overflow
        // map): a shared stripe only over-serializes distinct files.
        synchronized (WRITE_LOCKS[Math.floorMod(realFile.hashCode(), WRITE_LOCKS.length)]) {
            return compareAndWrite(realFile, expectedEtag, content, encoding, absRoot, rel, lang);
        }
    }

    private static final Object[] WRITE_LOCKS = new Object[64];

    static {
        for (int i = 0; i < WRITE_LOCKS.length; i++) WRITE_LOCKS[i] = new Object();
    }

    private static WriteResult compareAndWrite(
            Path file,
            @Nullable String expectedEtag,
            String content,
            @Nullable String encoding,
            Path absRoot,
            String rel,
            @Nullable String lang) {
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
        try {
            // Was a hand-rolled copy of AtomicWrites.replace — temp sibling, atomic move, cleanup in
            // a finally — and it carried that pattern's permission bug: the JDK creates a
            // temp file 0600, the rename hands those bits to the target, and this target is the
            // user's own source file. An agent editing through the API left the file readable by
            // nobody but its owner. The owner also gets the Windows retry it never had.
            AtomicWrites.replace(file, bytes);
        } catch (IOException e) {
            return new WriteResult.Failed(e.getMessage() == null ? "write failed" : e.getMessage());
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
