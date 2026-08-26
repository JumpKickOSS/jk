// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Every entry the action index writes under {@link CacheTree#ACTIONS}, named once.
 *
 * <p>{@link CacheTree} owns the cache root's top-level vocabulary and stops there, so the tree
 * <em>inside</em> a tier was six bare literals with no owner — and the number a user reads paid for
 * it. {@code jk status}'s "Actions Cached" counted every file under {@code actions/}, which on the
 * live dogfood cache was 315 where 123 actions were cached: one file per action in {@link #KEYS},
 * plus a task pointer in {@link #TASKS} and a whole analysis tree per task in
 * {@link #INCREMENTAL_JAVA}. Three populations under one heading add up to a number that describes
 * none of them.
 *
 * <p>The split matters beyond the count, because the four populations are reclaimed on different
 * terms: {@code ActionCachePrune} evicts a key record and its pointer together against the action
 * budget, sheds the incremental trees against a <em>separate</em> Zinc budget, and lets
 * {@link #SYNCED} age out on the record TTL. A directory that is bounded on its own denominator has
 * to be nameable on its own, and it was not.
 *
 * <p>Paths compose from the two owners — {@code ActionTree.KEYS.under(CacheTree.ACTIONS.under(root))}
 * — rather than this enum reaching for the cache root itself. One way to spell each half, and the
 * spelling says which owner it came from.
 */
public enum ActionTree {

    /** One key record per cached action, flat, named by the action key. The count of cached actions. */
    KEYS("keys"),

    /** One pointer per qualified task id at the action key it last produced. */
    TASKS("tasks"),

    /** {@code jk sync} reachability manifests, one per project fingerprint. */
    SYNCED("synced"),

    /** Java incremental-compile analysis, one tree per task id. Bounded apart from the action budget. */
    INCREMENTAL_JAVA("incremental-java"),

    /** Kotlin incremental-compile analysis, one tree per task id. Shares the incremental budget. */
    INCREMENTAL_KOTLIN("incremental-kotlin");

    private final String entry;

    ActionTree(String entry) {
        this.entry = entry;
    }

    /** Name of this entry's directory directly under the action-index root. */
    public String entry() {
        return entry;
    }

    /**
     * This entry under {@code actionsDir}, which is {@code CacheTree.ACTIONS.under(cacheRoot)}. The
     * one way to build the path; see the class javadoc on why the two halves stay separate.
     */
    public Path under(Path actionsDir) {
        return actionsDir.resolve(entry);
    }

    /**
     * The analysis trees the incremental budget bounds — the ones every report subtracts from the
     * action index so each bar measures its own tier. Three copies of this pair were typed out by
     * hand ({@code CacheSnapshot}, {@code CacheInventoryOps}, {@code ActionCachePrune}); adding a
     * third language now reaches all of them.
     */
    public static List<ActionTree> incremental() {
        return List.of(INCREMENTAL_JAVA, INCREMENTAL_KOTLIN);
    }

    /** {@link #incremental()} resolved under {@code actionsDir}, in declaration order. */
    public static List<Path> incrementalUnder(Path actionsDir) {
        return incremental().stream().map(t -> t.under(actionsDir)).toList();
    }

    /** Every entry name the action index writes — the vocabulary this enum closes. */
    public static List<String> entries() {
        return Arrays.stream(values()).map(ActionTree::entry).toList();
    }
}
