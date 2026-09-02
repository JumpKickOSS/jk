// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.task.RunNotices;
import cc.jumpkick.util.JkDirs;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped {@code [cache]} policy from {@code ~/.jk/config.toml} (not project-overridable).
 * Precedence: {@code JK_*} env &gt; user file &gt; defaults, ranked by {@link MachineConfig}.
 * Malformed values fall back to defaults.
 *
 * <p>{@link #maxCacheSizeGb} is the <strong>action cache</strong> budget ({@code jk cache usage}:
 * key records + cache CAS) and {@link #incrementalMaxSizeGb} bounds the Zinc analysis trees
 * ({@code actions/incremental-*}) separately — one denominator each, so a large workspace's
 * incremental state cannot push the action cache over a line no key eviction could bring back.
 * Those are the only size budgets jk enforces — the artifact store and the Maven local repository
 * are never size-pruned. {@code 0} (and negatives) mean unset: the documented default applies.
 *
 * <p>Sizes are in <strong>GiB</strong> ({@code max-cache-size-gb} / {@code JK_MAX_CACHE_SIZE_GB});
 * fractional values are allowed ({@code 0.5} = 512 MiB). The logical default is 4 GiB; when {@code
 * CI=1} or {@code CI=true}, 8 GiB. On volumes with total capacity under 10 GiB, that default is
 * replaced by {@code (free × 0.8) / 2} — 40 % of free space, leaving the other half of the 80 %
 * margin for the artifact store. Explicit file/env sizes are never disk-clamped: the clamp moves
 * the floor {@link MachineConfig#layerOver} falls back to, not the precedence above it.
 */
public record JkCacheConfig(
        boolean autoPrune, int pruneIntervalDays, double maxCacheSizeGb, double incrementalMaxSizeGb) {

    static final long GIB = 1024L * 1024L * 1024L;

    /** Total volume size below which the default budget uses the free-space formula. */
    public static final long SMALL_DISK_THRESHOLD_BYTES = 10L * GIB;

    /** Logical default cache budget (non-CI), before small-disk clamp. */
    public static final double DEFAULT_MAX_CACHE_SIZE_GB = 4.0;

    /** Logical cache budget when {@code CI=1} or {@code CI=true}. */
    public static final double CI_MAX_CACHE_SIZE_GB = 8.0;

    /**
     * Default budget for Zinc analysis state (512 MiB). Small next to the action budget on purpose:
     * incremental state is pure speed inside an edit loop, and losing it costs one full compile.
     */
    public static final double DEFAULT_INCREMENTAL_MAX_SIZE_GB = 0.5;

    /**
     * Share of the action budget the incremental default may claim. At the 4 GiB default this lands
     * exactly on {@link #DEFAULT_INCREMENTAL_MAX_SIZE_GB}; on a disk-clamped budget it shrinks with
     * it, so a 64 MiB machine does not reserve half a gigabyte for analysis files.
     */
    private static final double INCREMENTAL_DEFAULT_SHARE = 1.0 / 8.0;

    /** Floor for disk-clamped defaults (64 MiB) so a near-full volume never yields a zero budget. */
    static final double MIN_CLAMPED_GB = 64.0 / 1024.0;

    /**
     * Logical non-CI defaults (4 GiB) with no disk probe — used as parse fallbacks and when a probe
     * is unavailable. Prefer {@link #resolve()} for the effective machine budget.
     */
    public static final JkCacheConfig DEFAULTS =
            new JkCacheConfig(true, 7, DEFAULT_MAX_CACHE_SIZE_GB, DEFAULT_INCREMENTAL_MAX_SIZE_GB);

    private static final MachineConfig<Boolean> AUTO_PRUNE = MachineConfig.of(DEFAULTS.autoPrune());

    private static final MachineConfig<Integer> PRUNE_INTERVAL_DAYS =
            MachineConfig.of(DEFAULTS.pruneIntervalDays(), days -> days >= 0);

    /** A size budget of {@code 0} or less means unset, so only a positive value is a value. */
    private static final MachineConfig<Double> MAX_CACHE_SIZE_GB =
            MachineConfig.of(DEFAULT_MAX_CACHE_SIZE_GB, gb -> gb > 0);

    private static final MachineConfig<Double> INCREMENTAL_MAX_SIZE_GB =
            MachineConfig.of(DEFAULT_INCREMENTAL_MAX_SIZE_GB, gb -> gb > 0);

    /** Total and usable bytes on a volume (for default clamp tests and probes). */
    public record DiskSpace(long totalBytes, long freeBytes) {
        public DiskSpace {
            if (totalBytes < 0) totalBytes = 0;
            if (freeBytes < 0) freeBytes = 0;
        }

        /** Probe the volume hosting {@code path} (or its nearest existing ancestor). */
        public static @Nullable DiskSpace probe(Path path) {
            if (path == null) return null;
            try {
                Path p = path.toAbsolutePath().normalize();
                while (p != null && !Files.exists(p)) {
                    p = p.getParent();
                }
                if (p == null) return null;
                FileStore store = Files.getFileStore(p);
                return new DiskSpace(store.getTotalSpace(), store.getUsableSpace());
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Effective machine config: user-global file + env overrides + CI/disk defaults. */
    public static JkCacheConfig resolve() {
        return resolve(JkDirs.userConfigFile(), JkDirs::env, () -> DiskSpace.probe(JkDirs.cache()));
    }

    /** As {@link #resolve()} but against an explicit config file + env — probes {@link JkDirs#cache()}. */
    static JkCacheConfig resolve(Path userConfig, Function<String, String> env) {
        Objects.requireNonNull(env, "env");
        return resolve(userConfig, env, () -> DiskSpace.probe(JkDirs.cache()));
    }

    /**
     * Fully injectable resolve for tests: file + env + optional disk snapshot ({@code null} disk
     * skips the small-volume clamp).
     */
    static JkCacheConfig resolve(Path userConfig, Function<String, String> env, @Nullable DiskSpace disk) {
        return resolve(userConfig, env, () -> disk);
    }

    static JkCacheConfig resolve(
            Path userConfig, Function<String, String> env, @Nullable Supplier<DiskSpace> cacheDisk) {
        Objects.requireNonNull(env, "env");
        TomlScan scan = scan(userConfig);

        double logicalCache = EnvValues.isCi(env) ? CI_MAX_CACHE_SIZE_GB : DEFAULT_MAX_CACHE_SIZE_GB;
        DiskSpace cacheSpace = cacheDisk != null ? cacheDisk.get() : null;
        double defaultCache = clampDefaultGb(logicalCache, cacheSpace, () -> usedBytes(JkDirs.cache()));
        double defaultIncremental = Math.min(DEFAULT_INCREMENTAL_MAX_SIZE_GB, defaultCache * INCREMENTAL_DEFAULT_SHARE);

        return new JkCacheConfig(
                AUTO_PRUNE.layer(EnvValues.bool(env, "JK_AUTO_PRUNE").orElse(null), tomlBool(scan, "cache.auto-prune")),
                PRUNE_INTERVAL_DAYS.layer(
                        EnvValues.intValue(env, "JK_PRUNE_INTERVAL_DAYS").orElse(null),
                        scanInt(scan, "cache.prune-interval-days")),
                MAX_CACHE_SIZE_GB.layerOver(defaultCache, envCacheGb(env), fileCacheGb(scan)),
                INCREMENTAL_MAX_SIZE_GB.layerOver(
                        defaultIncremental,
                        EnvValues.doubleValue(env, "JK_INCREMENTAL_MAX_SIZE_GB").orElse(null),
                        scanDouble(scan, "cache.incremental-max-size-gb")));
    }

    /**
     * Machine defaults only (CI + disk clamp, no file/env size overrides). Used by the config
     * dashboard so {@code overridden} compares against what this host would pick if unset.
     */
    public static JkCacheConfig resolvedDefaults(Function<String, String> env) {
        return resolve(Path.of("/__jk_no_config__"), env, () -> DiskSpace.probe(JkDirs.cache()));
    }

    static JkCacheConfig resolvedDefaults(Function<String, String> env, @Nullable DiskSpace disk) {
        return resolve(Path.of("/__jk_no_config__"), env, disk);
    }

    /**
     * When total capacity is under 10 GiB, the default budget becomes {@code (free × 0.8) / 2} GiB.
     * Otherwise the logical default is kept. Explicit config never goes through this path.
     *
     * <p>The halving reserves the other half of the margin for the artifact store, which has no
     * budget and is never pruned: the cache budget is the only lever left on a small volume, so
     * claiming the whole margin for the one tier we can bound would starve the one we cannot.
     */
    static double clampDefaultGb(double logicalGb, @Nullable DiskSpace disk, LongSupplier tierUsedBytes) {
        if (disk == null || disk.totalBytes() >= SMALL_DISK_THRESHOLD_BYTES) {
            return logicalGb;
        }
        // The cache's own footprint counts as reclaimable headroom — clamping on raw free makes
        // the budget shrink as the cache fills (evict → free rises → budget grows → refill) and
        // converge far below the 80%-of-free intent. The usage walk runs only on
        // small volumes, where the cache is small by construction.
        long own = tierUsedBytes == null ? 0L : Math.max(0L, tierUsedBytes.getAsLong());
        double shareGb = ((disk.freeBytes() + own) * 0.8) / 2.0 / (double) GIB;
        if (shareGb < MIN_CLAMPED_GB) return MIN_CLAMPED_GB;
        return shareGb;
    }

    /** Best-effort recursive size of {@code root}; 0 when absent or unreadable. */
    static long usedBytes(Path root) {
        if (root == null || !Files.isDirectory(root)) return 0L;
        long[] total = {0L};
        try (var walk = Files.walk(root)) {
            walk.forEach(f -> {
                try {
                    if (Files.isRegularFile(f)) total[0] += Files.size(f);
                } catch (Exception ignored) {
                    // vanished mid-walk
                }
            });
        } catch (Exception ignored) {
            // unreadable tree — treat as empty
        }
        return total[0];
    }

    /**
     * Parse {@code [cache]} without CI/disk defaults — a missing size uses the {@link #DEFAULTS}
     * logical 4 GiB. Prefer {@link #resolve()} for the effective budget.
     */
    public static JkCacheConfig fromToml(Path file) {
        TomlScan scan = scan(file);
        return new JkCacheConfig(
                AUTO_PRUNE.layer(tomlBool(scan, "cache.auto-prune")),
                PRUNE_INTERVAL_DAYS.layer(scanInt(scan, "cache.prune-interval-days")),
                MAX_CACHE_SIZE_GB.layer(fileCacheGb(scan)),
                INCREMENTAL_MAX_SIZE_GB.layer(scanDouble(scan, "cache.incremental-max-size-gb")));
    }

    private static TomlScan scan(Path file) {
        return TomlScan.scan(
                file,
                "cache.auto-prune",
                "cache.prune-interval-days",
                "cache.max-cache-size-gb",
                "cache.max-cache-size-mb",
                "cache.incremental-max-size-gb");
    }

    /**
     * The env cache budget. The pre-rename {@code -MB} spelling ranks directly under the {@code -GB}
     * one on the same substrate, so a machine that still sets it is not overridden by the file —
     * {@link MachineConfig#accept} judges the preferred key first so an out-of-range value there
     * falls through to the legacy key rather than suppressing it.
     */
    private static @Nullable Double envCacheGb(Function<String, String> env) {
        Double gb = MAX_CACHE_SIZE_GB.accept(
                EnvValues.doubleValue(env, "JK_MAX_CACHE_SIZE_GB").orElse(null));
        if (gb != null) return gb;
        return legacyMbAsGb(
                MAX_CACHE_SIZE_GB.accept(
                        EnvValues.doubleValue(env, "JK_MAX_CACHE_SIZE_MB").orElse(null)),
                "JK_MAX_CACHE_SIZE_MB is the pre-rename spelling — use JK_MAX_CACHE_SIZE_GB");
    }

    /**
     * The file cache budget. Ignoring the pre-rename {@code -mb} key silently would grow a
     * deliberately small cache to the multi-GiB default on upgrade.
     */
    private static @Nullable Double fileCacheGb(TomlScan scan) {
        Double gb = MAX_CACHE_SIZE_GB.accept(scanDouble(scan, "cache.max-cache-size-gb"));
        if (gb != null) return gb;
        return legacyMbAsGb(
                MAX_CACHE_SIZE_GB.accept(scanDouble(scan, "cache.max-cache-size-mb")),
                "cache.max-cache-size-mb is the pre-rename spelling — use cache.max-cache-size-gb");
    }

    /**
     * The warning text is also the once-per-run key: the env and file spellings are two facts,
     * and each is said on its own — one shared flag once let whichever legacy key was read first
     * mute the other's warning.
     */
    private static @Nullable Double legacyMbAsGb(@Nullable Double mb, String warning) {
        if (mb == null) return null;
        RunNotices.warnOnce(warning, () -> "jk: warning: " + warning);
        return mb / 1024.0;
    }

    /** Action-cache budget in bytes ({@link #maxCacheSizeGb}). */
    public long maxCacheSizeBytes() {
        return gbToBytes(maxCacheSizeGb);
    }

    /** Zinc analysis budget in bytes ({@link #incrementalMaxSizeGb}). */
    public long incrementalMaxSizeBytes() {
        return gbToBytes(incrementalMaxSizeGb);
    }

    static long gbToBytes(double gb) {
        if (gb <= 0 || Double.isNaN(gb) || Double.isInfinite(gb)) return 0L;
        return Math.round(gb * (double) GIB);
    }

    /** Compact GiB label for config rows / doctor ({@code 4}, {@code 0.5}, {@code 0.8}). */
    public static String formatGb(double gb) {
        if (Double.isNaN(gb) || Double.isInfinite(gb)) return "0";
        long asLong = Math.round(gb);
        if (Math.abs(gb - asLong) < 1e-9) return Long.toString(asLong);
        String s = String.format(Locale.ROOT, "%.4f", gb);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * TOML's booleans, not jk's env truth set: a config file is parsed by the spec it is written to,
     * so {@code yes} / {@code on} are not booleans here and read as absent.
     */
    private static @Nullable Boolean tomlBool(TomlScan scan, String key) {
        return switch (String.valueOf(scan.get(key))) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static @Nullable Double scanDouble(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return null;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Integer scanInt(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
