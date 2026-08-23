// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every top-level entry jk writes under the cache root, and what bounds it.
 *
 * <p>The table is <strong>total</strong>: {@link CacheRetention} deletes any top-level entry no
 * constant here names. That is deliberate. Twice now a tier has grown without limit because its
 * bound lived somewhere nobody called — {@code AccessLedger.compactIfLarge()} with zero callers
 * (the file reached 3.1 GB), and {@code RunLogGc} with one caller on a path only a human types (its
 * 7-day TTL had not fired in 18 days). A tier that is merely *absent* from this enum would be a
 * third instance. Being absent now means being reclaimed, so the only way to keep a new tier is to
 * add it here — and adding it forces either an instrument or an explicit {@link Bound#unbounded}
 * reason.
 *
 * <p>Clocks are <strong>mtime</strong>. {@code atime} would be a truer last-use signal on the
 * derived tiers, but it is off on many filesystems and jk cannot tell which it got, so the design
 * does not depend on it. Where mtime is not a use clock, the instrument does not rank: it caps by
 * count, or resets the tier wholesale.
 */
public enum CacheTier {

    /** Action index. Bounded with the cache CAS by {@link ActionCachePrune}; see its Policy. */
    ACTIONS("actions", Bound.delegated()),

    /** Cache CAS — action outputs. Same budget and the same pass as {@link #ACTIONS}. */
    CACHE_CAS("sha256", Bound.delegated()),

    /**
     * Empty marker files, one per formatted source. Zero data bytes and {@code Blocks: 0} — the
     * cost is inodes and dirents, invisible to both {@code du} and {@code stat}, so a byte budget
     * could not see this tier at all.
     */
    FORMAT_STAMPS("format-stamps", Bound.files(Duration.ofDays(7), Bound.countCap(FormatStamps.maxFiles()))),

    /**
     * One index per formatter configuration. {@code save()} rewrites the live index on every {@code
     * jk format}, so mtime is an exact use clock here and the window costs nothing to maintain.
     */
    FORMAT_FRESHNESS("format-freshness", Bound.files(Duration.ofDays(7), Bound.countCap(64))),

    /**
     * Content hashes keyed by absolute path. Capped by count, never by bytes: entries are ~100 B,
     * which is one inline extent on btrfs and one 4 KiB block on ext4 — three different "sizes"
     * for the same tier, none of which a budget could be set against honestly.
     */
    HASH_MEMO("hash-memo", Bound.files(null, Bound.countCap(32_768))),

    /** GraalVM reachability metadata, one tree per bundle version. */
    GRAAL_REACHABILITY("graal-reachability", Bound.subtrees(Duration.ofDays(90), Bound.countCap(2))),

    /** Kotlin ABI snapshots. Written once and never rewritten on reuse, so mtime cannot rank them. */
    KOTLIN_CP_SNAPSHOTS("kotlin-cp-snapshots", Bound.files(null, Bound.resetOverBytes(128L * 1024 * 1024))),

    /** Hard-link aliases for {@code jk jshell}. Near-zero real bytes; recreated on demand. */
    JSHELL_CP("jshell-cp", Bound.files(null, Bound.resetAlways())),

    /** An extracted JRE per base image, 50–200 MB each — the only tier where a count cap is a byte bound. */
    BASE_JRE("base-jre", Bound.subtrees(Duration.ofDays(30), Bound.countCap(2))),

    /** A materialized Maven repo per path-dependency fingerprint. Every source edit mints one. */
    PATH_ARTIFACTS("path-artifacts", Bound.nestedSubtrees(Duration.ofDays(7), Bound.countCap(2))),

    /** Annotation-processor output for test compiles, keyed by task id. A dead task id freezes. */
    GENERATED("generated", Bound.subtrees(Duration.ofDays(7), Bound.none())),

    /** Preflight memo per project. {@code storeDirty} rewrites it every build, so mtime is exact. */
    PROJECTS("projects", Bound.subtrees(Duration.ofDays(90), Bound.countCap(512))),

    /** Tool scripts fetched from a URL, keyed by its hash. Losing one costs a re-download. */
    TOOL_SRC("tool-src", Bound.subtrees(Duration.ofDays(30), Bound.countCap(256))),

    /** Cadence bookkeeping: {@code <millis> <finalActionBytes>}. 13 bytes. */
    LAST_PRUNED(CachePruneScheduler.LAST_PRUNED_FILE, Bound.unbounded("constant-size cadence sentinel")),

    /** Cross-process prune mutex. Zero bytes, and deleting it mid-prune would defeat its purpose. */
    PRUNE_LOCK(".prune.lock", Bound.unbounded("zero-byte lock file held across the prune"));

    private final String entry;
    private final Bound bound;

    CacheTier(String entry, Bound bound) {
        this.entry = entry;
        this.bound = bound;
    }

    /** Name of this tier's directory or file directly under the cache root. */
    public String entry() {
        return entry;
    }

    public Bound bound() {
        return bound;
    }

    /** Every entry name the retention pass recognises — the whitelist the sweep spares. */
    public static Set<String> knownEntries() {
        return Arrays.stream(values()).map(CacheTier::entry).collect(Collectors.toUnmodifiableSet());
    }

    /** Tiers {@code jk cache nuke} wipes: everything jk owns and bounds. */
    public static List<CacheTier> purgeable() {
        return Arrays.stream(values())
                .filter(t -> t.bound().kind() != Bound.Kind.UNBOUNDED)
                .toList();
    }
}
