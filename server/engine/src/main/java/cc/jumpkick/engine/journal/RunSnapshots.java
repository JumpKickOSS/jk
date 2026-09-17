// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.test.MarkdownTestReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The two per-run artifacts a {@link JobDelta} compares: every test's outcome and every project
 * file's content hash, each a small TSV beside {@code record.json}. Both are written at
 * journal-write and read back for the run before, so the delta never depends on state the next
 * build has already overwritten.
 *
 * <p>Test outcomes: one line per test, {@code P|F|S <tab> Class#display}. Sources: one line per
 * file, {@code hash <tab> size <tab> mtime <tab> path}, paths relative to the project root with
 * {@code /} separators. The walk covers the build's inputs only ({@link #scope}): the manifests,
 * the lock, the guard rules, the {@code .jk/} scripts and every module's source, test and resource
 * roots — never the rest of the checkout. Inside a root it skips the trees a build writes or a
 * VCS keeps ({@link #SKIPPED} and every hidden directory) and gives up past {@link #MAX_FILES},
 * which leaves the file comparison out rather than stalling the journal on a huge tree.
 */
public final class RunSnapshots {

    private RunSnapshots() {}

    public static final char PASS = 'P';
    public static final char FAIL = 'F';
    public static final char SKIP = 'S';

    /** Beyond this many files the snapshot is not taken. */
    public static final int MAX_FILES = 20_000;

    /** Directory names the source walk never enters, at any depth. */
    static final Set<String> SKIPPED = Set.of(BuildLayout.TARGET, "build", "node_modules", "out");

    /** Files beside the root manifest that the build reads: the lock and the guard rules. */
    static final List<String> ROOT_FILES = List.of(ManifestPaths.LOCK, "jk-guards.toml");

    /** The build scripts' directory beside the root manifest: hidden, so named here rather than walked into. */
    static final String SCRIPTS_DIR = ".jk";

    // ------------------------------------------------------------------ tests

    /** {@code Class#display -> P/F/S} for every test of every module; empty when none ran. */
    public static Map<String, Character> testOutcomes(List<MarkdownTestReport.ModuleRun> runs) {
        Map<String, Character> out = new TreeMap<>();
        for (MarkdownTestReport.ModuleRun run : runs) {
            for (MarkdownTestReport.Entry e : run.entries()) {
                char status = e.isFail() ? FAIL : e.isSkip() ? SKIP : PASS;
                String key = testKey(e.className(), e.displayName());
                // A parameterized test's rows share a key; one failure fails the key.
                Character prior = out.get(key);
                if (prior == null || prior != FAIL) out.put(key, status);
            }
        }
        return out;
    }

    static String testKey(String className, String displayName) {
        String cls = className == null ? "" : className;
        String name = displayName == null ? "" : displayName.replace('\t', ' ').replace('\n', ' ');
        return cls.isEmpty() ? name : cls + "#" + name;
    }

    /** The TSV for {@link #testOutcomes}. */
    public static String encodeTests(Map<String, Character> outcomes) {
        StringBuilder sb = new StringBuilder(outcomes.size() * 48);
        for (Map.Entry<String, Character> e : outcomes.entrySet()) {
            sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        }
        return sb.toString();
    }

    /** {@link #encodeTests} read back; a malformed line is skipped. */
    public static Map<String, Character> decodeTests(String tsv) {
        Map<String, Character> out = new TreeMap<>();
        for (String line : tsv.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab != 1 || line.length() <= 2) continue;
            char status = line.charAt(0);
            if (status != PASS && status != FAIL && status != SKIP) continue;
            out.put(line.substring(2), status);
        }
        return out;
    }

    // ---------------------------------------------------------------- sources

    /** One file's snapshot row: the content hash and the stat identity it was taken under. */
    public record FileRow(String hash, long size, long mtime) {}

    /**
     * Relative path to content hash for every file under {@code root}, or {@code null} when the
     * tree is unreadable or larger than {@link #MAX_FILES}. {@code previous} is the run before's
     * rows: a file whose size and mtime it already knows keeps its hash without a read.
     */
    public static @Nullable Map<String, String> takeSources(Path root, @Nullable Map<String, FileRow> previous) {
        Map<String, FileRow> rows = walk(root, previous == null ? Map.of() : previous);
        if (rows == null) return null;
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, FileRow> e : rows.entrySet())
            out.put(e.getKey(), e.getValue().hash());
        return out;
    }

    /** As {@link #takeSources} keeping the stat identity, for {@link #encodeSources}. */
    public static @Nullable Map<String, FileRow> walk(Path root, Map<String, FileRow> previous) {
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) return null;
        Scope scope = scope(base);
        Map<String, FileRow> out = new TreeMap<>();
        try {
            for (Path file : scope.files()) {
                if (Files.isRegularFile(file)) {
                    row(base, file, Files.readAttributes(file, BasicFileAttributes.class), previous, out);
                }
            }
            for (Path dir : scope.dirs()) {
                PathUtil.forEachRegularFile(dir, RunSnapshots::skipDirectory, (file, attrs) -> {
                    // Past the cap the walk finishes without hashing; the snapshot is then not taken.
                    if (out.size() > MAX_FILES) return;
                    row(base, file, attrs, previous, out);
                });
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return out.size() > MAX_FILES ? null : out;
    }

    /** One file's row into {@code out}: the previous run's hash when its size and mtime match, else a read. */
    private static void row(
            Path base, Path file, BasicFileAttributes attrs, Map<String, FileRow> previous, Map<String, FileRow> out)
            throws IOException {
        String rel = base.relativize(file).toString().replace('\\', '/');
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().toMillis();
        FileRow known = previous.get(rel);
        String hash = known != null && known.size() == size && known.mtime() == mtime
                ? known.hash()
                : FileHashMemo.contentHash(file, attrs);
        out.put(rel, new FileRow(hash, size, mtime));
    }

    /** The build's inputs under a root: files read as they are, and directories walked. */
    record Scope(List<Path> files, List<Path> dirs) {}

    /**
     * What the build reads under {@code root}: the root's {@link #ROOT_FILES} and {@code .jk/}
     * scripts, the root's own source roots, and for every {@code [workspace] modules} member its
     * manifest and the source, test and resource roots {@link ModuleLayout#fingerprintDirs} names.
     * A workspace root contributes only its main roots: the compact layout reads any sibling
     * directory holding {@code src/} as a test suite, which under a root is a member. Docs, scratch
     * trees and an IDE's files are not inputs, so they are not in scope.
     */
    static Scope scope(Path root) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        files.add(ManifestPaths.manifestIn(root));
        for (String name : ROOT_FILES) files.add(root.resolve(name));
        Path scripts = root.resolve(SCRIPTS_DIR);
        if (Files.isDirectory(scripts)) dirs.add(scripts);
        List<Path> members = memberDirs(root);
        addExisting(dirs, ModuleLayout.fingerprintDirs(root, !members.isEmpty()));
        for (Path member : members) {
            files.add(ManifestPaths.manifestIn(member));
            addExisting(dirs, ModuleLayout.fingerprintDirs(member, false));
        }
        return new Scope(List.copyOf(files), List.copyOf(dirs));
    }

    private static void addExisting(LinkedHashSet<Path> dirs, List<Path> candidates) {
        for (Path dir : candidates) {
            if (Files.isDirectory(dir)) dirs.add(dir);
        }
    }

    /**
     * The member directories the root manifest declares under {@code [workspace] modules}, globs
     * expanded; empty for a standalone project. A key scan of the manifest, not the full parser:
     * the list is literal text, the scan costs milliseconds where the parser's first read of a
     * workspace costs hundreds, and a manifest the parser would reject still names its members.
     */
    static List<Path> memberDirs(Path root) {
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return List.of();
        List<String> rels = TomlScan.scan(manifest, "workspace.modules").stringArray("workspace.modules");
        List<Path> out = new ArrayList<>();
        for (String rel : expandQuietly(root, rels)) {
            if (rel != null && !rel.isBlank()) out.add(root.resolve(rel).normalize());
        }
        return out;
    }

    /** Globs expanded; a pattern that matches nothing scopes no member rather than failing the snapshot. */
    private static List<String> expandQuietly(Path root, List<String> rels) {
        try {
            return WorkspaceModules.expand(root, rels);
        } catch (RuntimeException e) {
            return rels.stream()
                    .filter(r -> r != null && !WorkspaceModules.isGlob(r))
                    .toList();
        }
    }

    /** A hidden directory or one of {@link #SKIPPED}: a build output, a VCS tree, an IDE's state. */
    static boolean skipDirectory(Path dir) {
        String name = String.valueOf(dir.getFileName());
        return name.startsWith(".") || SKIPPED.contains(name);
    }

    /** The TSV for {@link #walk}. */
    public static String encodeSources(Map<String, FileRow> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 96);
        for (Map.Entry<String, FileRow> e : rows.entrySet()) {
            FileRow r = e.getValue();
            sb.append(r.hash())
                    .append('\t')
                    .append(r.size())
                    .append('\t')
                    .append(r.mtime())
                    .append('\t')
                    .append(e.getKey())
                    .append('\n');
        }
        return sb.toString();
    }

    /** {@link #encodeSources} read back; a malformed line is skipped. */
    public static Map<String, FileRow> decodeSources(String tsv) {
        Map<String, FileRow> out = new LinkedHashMap<>();
        for (String line : tsv.split("\n")) {
            String[] f = line.split("\t", 4);
            if (f.length != 4 || f[0].isEmpty() || f[3].isEmpty()) continue;
            long size = parseLong(f[1]);
            long mtime = parseLong(f[2]);
            if (size < 0 || mtime < 0) continue;
            out.put(f[3], new FileRow(f[0], size, mtime));
        }
        return out;
    }

    /** A non-negative decimal, or {@code -1} for anything else. */
    private static long parseLong(String s) {
        if (s.isEmpty() || s.length() > 18) return -1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return -1;
        return Long.parseLong(s);
    }

    /** Hashes only, for {@link JobDelta#compute}. */
    public static Map<String, String> hashes(Map<String, FileRow> rows) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, FileRow> e : rows.entrySet())
            out.put(e.getKey(), e.getValue().hash());
        return out;
    }

    /** {@code file}'s text, or {@code null} when it is absent or unreadable. */
    static @Nullable String readOrNull(@Nullable Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
