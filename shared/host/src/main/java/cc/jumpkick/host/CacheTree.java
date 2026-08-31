// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every top-level entry jk writes under the cache root, named once.
 *
 * <p>The table is <strong>total</strong>: the engine's retention pass reclaims any top-level entry
 * no constant here names. That cuts both ways, and the second way is why this enum is in the host
 * leaf rather than beside the pass that reads it. A tier is written by one process and reclaimed,
 * measured and wiped by others — {@code base-jre} by the image-builder worker, {@code format-stamps}
 * by the formatter worker, {@code sha256} by the CAS, all three by {@code jk cache} in the native
 * client — so a name spelled at the producer and re-spelled at the reclaimer is a rename that
 * half-lands: the producer keeps filling a directory the sweep now calls residue, or the sweep
 * bounds a directory nothing writes. Both were live. Naming the entry here, where every jk process
 * can reach it, is what makes the two ends move together.
 *
 * <p>{@code CacheTier} in the engine says what <em>bounds</em> each of these, in an exhaustive
 * switch over this enum: adding a constant here is a compile error there until it gets an
 * instrument or an explicit unbounded reason. Which half a fact belongs to is decided by who needs
 * it — the CLI must know the name of every tier to wipe and measure one, and must not need to know
 * the retention window to do it.
 */
public enum CacheTree {

    /** Action index: key records, task pointers, and the incremental-compile analysis trees. */
    ACTIONS("actions"),

    /**
     * Cache CAS — action output blobs. The shard layout under this entry belongs to {@code Cas},
     * which builds it from the same name against both this root and the artifact store's.
     */
    CACHE_CAS("sha256"),

    /** One empty marker file per formatted source, written by the formatter worker. */
    FORMAT_STAMPS("format-stamps"),

    /** One index per formatter configuration, rewritten in place on every {@code jk format}. */
    FORMAT_FRESHNESS("format-freshness"),

    /** Content hashes keyed by absolute path, sharded two hex digits deep. */
    HASH_MEMO("hash-memo"),

    /** JVM ABI tokens keyed by classpath-entry content identity. */
    ABI_MEMO("abi-memo"),

    /** Kotlin ABI snapshots for the incremental classpath. */
    KOTLIN_CP_SNAPSHOTS("kotlin-cp-snapshots"),

    /** Hard-link aliases {@code jk jshell} builds a stable classpath out of. */
    JSHELL_CP("jshell-cp"),

    /** An extracted JRE per base image digest, written by the image-builder worker. */
    BASE_JRE("base-jre"),

    /** A materialized Maven repo per path-dependency, keyed by source path then by fingerprint. */
    PATH_ARTIFACTS("path-artifacts"),

    /** Annotation-processor output for test compiles, keyed by task id. */
    GENERATED("generated"),

    /** Per-project preflight memo, keyed by project identity. */
    PROJECTS("projects"),

    /** Tool scripts fetched from a URL, keyed by the hash of that URL. */
    TOOL_SRC("tool-src"),

    /** Cadence bookkeeping for the prune itself: {@code <millis> <finalActionBytes>}. */
    LAST_PRUNED(".last-pruned", "constant-size cadence sentinel"),

    /** Cross-process prune mutex, held for the length of a maintenance pass. */
    PRUNE_LOCK(".prune.lock", "zero-byte lock file held across the prune");

    private final String entry;
    private final String bookkeeping;

    CacheTree(String entry) {
        this(entry, "");
    }

    CacheTree(String entry, String bookkeeping) {
        this.entry = entry;
        this.bookkeeping = bookkeeping;
    }

    /** Name of this entry's directory or file directly under the cache root. */
    public String entry() {
        return entry;
    }

    /** This entry under {@code cacheRoot}. The one way to build the path; see the class javadoc. */
    public Path under(Path cacheRoot) {
        return cacheRoot.resolve(entry);
    }

    /**
     * Why this entry is jk's own bookkeeping rather than cached bytes, or {@code ""} when it holds
     * cached bytes. A cadence sentinel and a lock file are not cache: wiping them mid-prune breaks
     * the prune, and there is nothing there for a byte budget to bound. The engine turns a non-blank
     * reason into a deliberately unbounded {@code Bound}, so the reason is written once, here.
     */
    public String bookkeeping() {
        return bookkeeping;
    }

    /** Whether this entry holds rebuildable bytes — the ones a wipe takes and a budget measures. */
    public boolean holdsCachedBytes() {
        return bookkeeping.isEmpty();
    }

    /** Every entry name the retention pass recognises — the whitelist its residue sweep spares. */
    public static Set<String> entries() {
        return Arrays.stream(values()).map(CacheTree::entry).collect(Collectors.toUnmodifiableSet());
    }

    /** What {@code jk cache nuke} wipes and {@code jk cache usage} measures: everything jk caches. */
    public static List<CacheTree> cached() {
        return Arrays.stream(values()).filter(CacheTree::holdsCachedBytes).toList();
    }

    /** {@link #cached()} resolved under {@code cacheRoot}, in declaration order. */
    public static List<Path> cachedUnder(Path cacheRoot) {
        return cached().stream().map(t -> t.under(cacheRoot)).toList();
    }
}
