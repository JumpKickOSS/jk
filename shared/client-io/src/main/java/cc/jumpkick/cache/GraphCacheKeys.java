// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.OptionalInt;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The action-cache keys of the code graph's fragments, computed alike by the graph worker, which
 * reads and stores them, and by the engine, which skips indexing a class set whose compiled facts
 * the cache already holds. Each entry is a key record under {@code actions/keys/} with one output,
 * {@link #OUTPUT}, in the cache CAS.
 */
public final class GraphCacheKeys {

    private GraphCacheKeys() {}

    /**
     * Bumped whenever what makes a fragment changes (any extractor, the mapping of compiled facts),
     * so fragments an older version cached are not reused.
     */
    public static final String VERSION = "13";

    /** The task a declarations record names. */
    public static final String DECLARATIONS = "graph-declarations";

    /** The task a compiled-facts record names. */
    public static final String CLASSES = "graph-classes";

    /** The one output of a record: the fragment, or the class set's fragments by file. */
    public static final String OUTPUT = "fragment.json";

    /** The input of a compiled-facts record that holds the set's class count, which an answer tallies. */
    public static final String CLASS_COUNT = "classes";

    /**
     * What the graph worker answers when compiled facts the engine found cached are gone by the time
     * it reads them; the engine then indexes the classes and asks again.
     */
    public static final String GONE = "the cached compiled facts of a class set are gone";

    /** The key of {@code rel}'s declarations at content hash {@code sha}, read by the reader {@code flavor} names. */
    public static String declarations(String rel, String sha, String flavor) {
        return Hashing.sha256Hex(DECLARATIONS + "\n" + VERSION + flavor + "\n" + rel + "\n" + sha);
    }

    /**
     * The key of the compiled facts of one module's {@code sourceSet} classes ({@code main}, {@code
     * test} or {@code guard}), from the action keys of the compiles that wrote them, which cover its sources and
     * classpath; {@code null} when no compile stamped the set, which is then never cached.
     */
    public static @Nullable String classes(String modulePrefix, String sourceSet, Collection<String> compileKeys) {
        if (compileKeys.isEmpty()) return null;
        return Hashing.sha256Hex(CLASSES + "\n" + VERSION + "\n" + modulePrefix + "\n" + sourceSet + "\n"
                + String.join("\n", new TreeSet<>(compileKeys)));
    }

    /** Where the record of {@code key} lives under the cache root. */
    public static Path record(Path cacheRoot, String key) {
        return ActionTree.KEYS.under(CacheTree.ACTIONS.under(cacheRoot)).resolve(key);
    }

    /**
     * The class count the compiled-facts record under {@code key} holds, when the record is there and
     * the cache CAS still has its output; empty otherwise.
     */
    public static OptionalInt heldClasses(Path cacheRoot, String key) {
        try {
            ActionRecordText.Parsed record =
                    ActionRecordText.parse(Files.readString(record(cacheRoot, key), StandardCharsets.UTF_8));
            String sha = record.outputs().get(OUTPUT);
            String count = record.inputs().get(CLASS_COUNT);
            if (sha == null || count == null || !JkStores.cacheCas(cacheRoot).contains(sha)) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(Integer.parseInt(count));
        } catch (IOException | RuntimeException absentOrUnreadable) {
            return OptionalInt.empty();
        }
    }
}
