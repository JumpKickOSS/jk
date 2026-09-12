// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.host.CodeText;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.WalkSkip;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/**
 * The corpus a text rule can see: every regular file under the root a reader could read, minus
 * build output, VCS metadata and binaries. Pruned by name <em>and position</em>: {@code build} beside
 * a Gradle script is output, {@code cc/jumpkick/plugin/build} under {@code src/} is a package. A nested
 * checkout (a worktree, recognised by its {@code .git}) is another branch's tree.
 *
 * <p>Binaries are recognised three ways, cheapest first: by extension, by size — a file over
 * {@link #MAX_BYTES} is a model, an archive or a dump, not source, and is never read — and by a
 * NUL byte in the first {@link #SNIFF_BYTES}, checked before the whole file is read or decoded.
 */
final class TextFiles {

    /** What the blanker knows how to lex, and what it does not. */
    enum Language {
        JAVA(true, true),
        KOTLIN(true, true),
        JS(true, true),
        GROOVY(false, true),
        SCALA(false, true),
        /** Markdown, text, AsciiDoc: no code, scanned whole under every mode. */
        PROSE(false, false),
        /** TOML, YAML, JSON, XML, properties, shell: not lexed; scanned whole under every mode. */
        CONFIG(false, false),
        UNKNOWN(false, false);

        final boolean lexable;
        final boolean code;

        Language(boolean lexable, boolean code) {
            this.lexable = lexable;
            this.code = code;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Set<String> SKIP_DIRS =
            Set.of(BuildLayout.TARGET, ".git", ".gradle", ".idea", ".kotlin", "node_modules", ".board", ".firebase");
    private static final List<String> BINARY_EXT = List.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".jar", ".zip", ".xz", ".gz", ".tar", ".class", ".aot",
            ".woff", ".woff2", ".ttf", ".pdf", ".so", ".dylib", ".exe", ".dll", ".bin", ".idx", ".lock");

    /** The largest file a text rule reads; source files are kilobytes, anything past this is data. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    /** How much of a file's head is checked for a NUL byte before the rest is read. */
    static final int SNIFF_BYTES = 8192;

    /** Files opened by {@link #read} so far: the seam that proves a batch reads each file once. */
    static final LongAdder READS = new LongAdder();

    private TextFiles() {}

    record Entry(Path file, String rel, Language language) {}

    /** Every readable text file under {@code root}, sorted by relative path. */
    static List<Entry> corpus(Path root) throws IOException {
        Path r = root.toAbsolutePath().normalize();
        List<Entry> out = new ArrayList<>();
        PathUtil.forEachRegularFile(r, d -> skip(r, d), (p, attrs) -> {
            if (attrs.size() > MAX_BYTES) return;
            String name = p.getFileName().toString();
            for (String ext : BINARY_EXT) if (name.endsWith(ext)) return;
            String rel = r.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
            out.add(new Entry(p, rel, languageOf(name)));
        });
        out.sort((a, b) -> a.rel.compareTo(b.rel));
        return out;
    }

    static boolean skip(Path root, Path dir) {
        if (dir.equals(root)) return false;
        Path name = dir.getFileName();
        if (name == null) return false;
        if (WalkSkip.nestedCheckout(dir)) return true;
        String n = name.toString();
        // Gradle's build/ is the one beside a Gradle script; a package named build is a package.
        if (n.equals("build")) return OutputDirs.isGradleBuildDir(dir);
        if (!SKIP_DIRS.contains(n)) return false;
        String rel = root.relativize(dir).toString().replace('\\', '/');
        // `target` under a source root is a package, not output.
        return !rel.contains("/src/");
    }

    static Language languageOf(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "java" -> Language.JAVA;
            case "kt", "kts" -> Language.KOTLIN;
            case "js", "mjs", "cjs", "ts" -> Language.JS;
            case "groovy", "gradle" -> Language.GROOVY;
            case "scala", "sc" -> Language.SCALA;
            case "md", "txt", "adoc", "rst" -> Language.PROSE;
            case "toml",
                    "yml",
                    "yaml",
                    "json",
                    "xml",
                    "properties",
                    "sh",
                    "bash",
                    "zsh",
                    "ps1",
                    "cmd",
                    "bat",
                    "html",
                    "css",
                    "svg",
                    "sql",
                    "proto",
                    "g8",
                    "gitignore",
                    "editorconfig",
                    "nvmrc" -> Language.CONFIG;
            default -> n.equals("makefile") || n.equals("dockerfile") || dot < 0 ? Language.CONFIG : Language.UNKNOWN;
        };
    }

    /**
     * UTF-8 text, or {@code null} for a binary by another name: a NUL in the head, or bytes that
     * are not valid UTF-8.
     */
    static @Nullable String read(Path file) throws IOException {
        READS.increment();
        try (InputStream in = Files.newInputStream(file)) {
            if (!sniff(in.readNBytes(SNIFF_BYTES))) return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Whether {@code head} looks like text: no NUL byte, which no text encoding a rule reads emits. */
    static boolean sniff(byte[] head) {
        for (byte b : head) if (b == 0) return false;
        return true;
    }

    /** The {@code blank} spelling → the projection. */
    static CodeText.Blank blankMode(@Nullable String spelling) {
        if (spelling == null) return CodeText.Blank.COMMENTS;
        return switch (spelling) {
            case "comments+strings" -> CodeText.Blank.COMMENTS_AND_STRINGS;
            case "none" -> CodeText.Blank.NONE;
            case "code" -> CodeText.Blank.CODE;
            default -> CodeText.Blank.COMMENTS;
        };
    }
}
