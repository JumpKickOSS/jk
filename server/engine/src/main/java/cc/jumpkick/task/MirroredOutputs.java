// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Paths another step laid into a classes tree: the resource mirror's copies and the classes each
 * other compiler merged in. A compile stores every file it finds in its output directory; these
 * paths are not its outputs, and a restore of a record that contains them lays a deleted resource
 * or a dropped class back down.
 *
 * <p>One exception: main resources are copied before the compile, so an annotation processor can
 * read a copy through the Filer and write that same path back, as Maven lets it. Such a file is
 * the compile's output, not a copy. {@link #MAIN_SOURCES} names each copy's resource, so a copy
 * whose bytes are no longer its resource's is kept in the record and laid down again by a restore.
 */
public final class MirroredOutputs {

    /** Resource ledger under {@code target/incremental/} for {@code target/classes}. */
    public static final String MAIN_LEDGER = "copied-resources.txt";

    /** Resource ledger under {@code target/incremental/} for {@code target/test-classes}. */
    public static final String TEST_LEDGER = "copied-test-resources.txt";

    /**
     * Beside {@link #MAIN_LEDGER}: each main copy's tree-relative path and the resource file it was
     * copied from, tab-separated. The Java compiler worker reads it too, to tell a processor's read
     * of a copied resource from a read of its own last output.
     */
    public static final String MAIN_SOURCES = "copied-resource-sources.tsv";

    /**
     * Beside {@link #MAIN_LEDGER}: the copies a processor rewrote in the last compile, each with the
     * content sha of the resource it was seeded from. The resource copy leaves such a file alone
     * while that resource is unchanged.
     */
    public static final String PROCESSED_LEDGER = "processed-resources.txt";

    private static final String MERGED_PREFIX = "merged-";
    private static final String MERGED_SUFFIX = ".txt";
    private static final String TEST_MERGE = "-test";

    private MirroredOutputs() {}

    /**
     * The ledger a compiler merge keeps under {@code target/incremental/}: {@code
     * merged-<name>.txt}. A name ending in {@code -test} is a merge into {@code target/test-classes};
     * every other name is a merge into {@code target/classes}.
     */
    public static String mergedLedger(String name) {
        return MERGED_PREFIX + name + MERGED_SUFFIX;
    }

    /**
     * {@code outputs} without the paths the ledgers for {@code outputDir} record. {@code
     * target/classes} and {@code target/test-classes} are the trees that share a directory with a
     * mirror or a merge; any other directory is returned unchanged. A main resource copy whose
     * captured bytes are not its resource's stays: a processor rewrote it, so it is the compile's.
     */
    public static Map<String, String> without(Path outputDir, Map<String, String> outputs) throws IOException {
        Set<String> merged = mergedPaths(outputDir);
        Set<String> copied = copiedPaths(outputDir);
        if ((merged.isEmpty() && copied.isEmpty()) || outputs.isEmpty()) return outputs;
        Map<String, Path> sources = sourcesOf(outputDir);
        Map<String, String> kept = new TreeMap<>();
        for (var entry : outputs.entrySet()) {
            String rel = entry.getKey();
            boolean ours =
                    !merged.contains(rel) && (!copied.contains(rel) || rewritten(sources.get(rel), entry.getValue()));
            if (ours) kept.put(rel, entry.getValue());
        }
        return kept;
    }

    /** Whether a copy captured as {@code captured} differs from {@code source}, the resource it was copied from. */
    private static boolean rewritten(@Nullable Path source, String captured) throws IOException {
        if (source == null || !Files.isRegularFile(source)) return false;
        return !FileHashMemo.contentHash(source).equals(captured);
    }

    /**
     * {@code record} with the paths a restore must not rewrite removed: every merged path, and every
     * copy whose resource is not known. A main copy a record holds is one a processor rewrote
     * ({@link #without}), so it stays. The same path set {@link ActionCache#restore} refuses to
     * overwrite, so a hit does not lay a deleted resource back down.
     */
    public static ActionCache.ActionRecord omitting(Path outputDir, ActionCache.ActionRecord record)
            throws IOException {
        Set<String> skipped = notRestored(outputDir);
        if (skipped.isEmpty() || record.outputs().isEmpty()) return record;
        Map<String, String> kept = new TreeMap<>();
        for (var entry : record.outputs().entrySet()) {
            if (!skipped.contains(entry.getKey())) kept.put(entry.getKey(), entry.getValue());
        }
        if (kept.size() == record.outputs().size()) return record;
        Set<String> exec = new TreeSet<>();
        for (String rel : record.executables()) {
            if (!skipped.contains(rel)) exec.add(rel);
        }
        return new ActionCache.ActionRecord(
                record.taskId(), record.actionKey(), record.inputs(), kept, exec, record.said());
    }

    /**
     * The recorded paths a restore leaves as they are: the merge ledgers' paths, and the resource
     * ledger's paths that {@link #MAIN_SOURCES} does not name.
     */
    static Set<String> notRestored(Path outputDir) throws IOException {
        Set<String> out = new TreeSet<>(mergedPaths(outputDir));
        Map<String, Path> sources = sourcesOf(outputDir);
        for (String rel : copiedPaths(outputDir)) {
            if (!sources.containsKey(rel)) out.add(rel);
        }
        return out;
    }

    /**
     * Rewrite the record at {@code key} so it no longer names a mirrored path or a non-class file
     * the mirror has already removed. The next restore then cannot put that file back.
     */
    public static void release(ActionCache cache, @Nullable String key, Path outputDir, Set<String> mirrored)
            throws IOException {
        if (key == null || key.isBlank()) return;
        var found = cache.lookup(key);
        if (found.isEmpty()) return;
        ActionCache.ActionRecord record = found.get();
        Map<String, String> kept = new TreeMap<>();
        boolean changed = false;
        for (var entry : record.outputs().entrySet()) {
            String rel = entry.getKey();
            boolean drop = mirrored.contains(rel) || (!rel.endsWith(".class") && !Files.exists(outputDir.resolve(rel)));
            if (drop) {
                changed = true;
                continue;
            }
            kept.put(rel, entry.getValue());
        }
        // An empty record restores as a wipe. A compile that produced classes must keep them.
        String task = record.taskId();
        if (!changed || kept.isEmpty() || task == null) return;
        cache.storeWithOutputs(task, key, record.inputs(), kept, record.said());
    }

    /** Every path the resource ledger and the merge ledgers for {@code outputDir} record. */
    static Set<String> recorded(Path outputDir) throws IOException {
        Set<String> out = new TreeSet<>(copiedPaths(outputDir));
        out.addAll(mergedPaths(outputDir));
        return out;
    }

    /**
     * Each main copy's resource file, by tree-relative path, as {@link #writeSources} last wrote it
     * under {@code incremental}; empty when the file is absent.
     */
    public static Map<String, Path> sources(Path incremental) throws IOException {
        Path file = incremental.resolve(MAIN_SOURCES);
        if (!Files.isRegularFile(file)) return Map.of();
        Map<String, Path> out = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            out.put(unescape(line.substring(0, tab)), Path.of(unescape(line.substring(tab + 1))));
        }
        return out;
    }

    /** Record {@code sources} (tree-relative path to resource file) under {@code incremental}, when it changed. */
    public static void writeSources(Path incremental, Map<String, Path> sources) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var e : new TreeMap<>(sources).entrySet()) {
            String source = e.getValue().toAbsolutePath().normalize().toString();
            sb.append(escape(e.getKey())).append('\t').append(escape(source)).append('\n');
        }
        Files.createDirectories(incremental);
        AtomicWrites.replaceIfChanged(incremental.resolve(MAIN_SOURCES), sb.toString());
    }

    /** Tabs, newlines and backslashes escaped, as the compiler worker's ledgers spell paths. */
    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            out.append(
                    switch (next) {
                        case 't' -> '\t';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        default -> next;
                    });
        }
        return out.toString();
    }

    /** {@code target/incremental} for {@code target/classes} or {@code target/test-classes}; null for any other tree. */
    private static @Nullable Path incrementalOf(Path outputDir) {
        Path name = outputDir.getFileName();
        if (name == null) return null;
        if (!name.toString().equals("test-classes") && !name.toString().equals("classes")) return null;
        Path target = outputDir.getParent();
        if (target == null || !BuildLayout.TARGET.equals(String.valueOf(target.getFileName()))) return null;
        return target.resolve("incremental");
    }

    private static boolean isTest(Path outputDir) {
        return String.valueOf(outputDir.getFileName()).equals("test-classes");
    }

    private static Set<String> copiedPaths(Path outputDir) throws IOException {
        Path incremental = incrementalOf(outputDir);
        if (incremental == null) return Set.of();
        return read(incremental.resolve(isTest(outputDir) ? TEST_LEDGER : MAIN_LEDGER));
    }

    /** Main copies' resources; the test tree has none on record. */
    private static Map<String, Path> sourcesOf(Path outputDir) throws IOException {
        Path incremental = incrementalOf(outputDir);
        if (incremental == null || isTest(outputDir)) return Map.of();
        return sources(incremental);
    }

    private static Set<String> mergedPaths(Path outputDir) throws IOException {
        Path incremental = incrementalOf(outputDir);
        if (incremental == null) return Set.of();
        boolean test = isTest(outputDir);
        Set<String> out = new TreeSet<>();
        List<Path> merges = new ArrayList<>();
        PathUtil.forEachChild(incremental, (file, attrs) -> {
            String ledger = file.getFileName().toString();
            if (attrs.isRegularFile()
                    && ledger.startsWith(MERGED_PREFIX)
                    && ledger.endsWith(MERGED_SUFFIX)
                    && ledger.endsWith(TEST_MERGE + MERGED_SUFFIX) == test) {
                merges.add(file);
            }
            return true;
        });
        for (Path merge : merges) out.addAll(read(merge));
        return out;
    }

    private static Set<String> read(Path ledger) throws IOException {
        if (!Files.isRegularFile(ledger)) return Set.of();
        Set<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) out.add(line.strip());
        }
        return out;
    }
}
