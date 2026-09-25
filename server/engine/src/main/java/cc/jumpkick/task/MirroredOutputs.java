// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Paths a resource mirror recorded into a classes tree. A compile stores every file it finds in
 * its output directory; these paths are not its outputs, and a restore of a record that contains
 * them lays a deleted resource back down.
 */
public final class MirroredOutputs {

    /** Ledger under {@code <module>/incremental/} for {@code classes/main}. */
    public static final String MAIN_LEDGER = "copied-resources.txt";

    /** Ledger under {@code <module>/incremental/} for {@code classes/test}. */
    public static final String TEST_LEDGER = "copied-test-resources.txt";

    private MirroredOutputs() {}

    /**
     * {@code outputs} without the paths the ledger for {@code outputDir} records. {@code
     * classes/main} and {@code classes/test} are the trees that share a directory with a mirror;
     * any other directory is returned unchanged.
     */
    public static Map<String, String> without(Path outputDir, Map<String, String> outputs) throws IOException {
        Set<String> mirrored = recorded(outputDir);
        if (mirrored.isEmpty() || outputs.isEmpty()) return outputs;
        Map<String, String> kept = new TreeMap<>();
        for (var entry : outputs.entrySet()) {
            if (!mirrored.contains(entry.getKey())) kept.put(entry.getKey(), entry.getValue());
        }
        return kept;
    }

    /**
     * {@code record} with mirrored paths removed from its outputs. The same path set {@link
     * ActionCache#restore} refuses to overwrite, so a hit does not lay a deleted resource back down.
     */
    public static ActionCache.ActionRecord omitting(Path outputDir, ActionCache.ActionRecord record)
            throws IOException {
        Map<String, String> kept = without(outputDir, record.outputs());
        if (kept == record.outputs() || kept.size() == record.outputs().size()) return record;
        Set<String> exec = new TreeSet<>();
        Set<String> mirrored = recorded(outputDir);
        for (String rel : record.executables()) {
            if (!mirrored.contains(rel)) exec.add(rel);
        }
        return new ActionCache.ActionRecord(record.taskId(), record.actionKey(), record.inputs(), kept, exec);
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
        cache.storeWithOutputs(task, key, record.inputs(), kept);
    }

    static Set<String> recorded(Path outputDir) throws IOException {
        Path name = outputDir.getFileName();
        if (name == null) return Set.of();
        String ledgerName =
                switch (name.toString()) {
                    case "main" -> MAIN_LEDGER;
                    case "test" -> TEST_LEDGER;
                    default -> null;
                };
        if (ledgerName == null) return Set.of();
        Path classes = outputDir.getParent();
        if (classes == null || !"classes".equals(String.valueOf(classes.getFileName()))) return Set.of();
        Path target = classes.getParent();
        if (target == null) return Set.of();
        return read(target.resolve("incremental").resolve(ledgerName));
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
