// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Machine-scoped {@code [cache]} policy from {@code ~/.config/jk/config.toml} (not project-overridable).
 * Precedence: {@code JK_*} env &gt; user file &gt; defaults. Malformed values fall back to defaults.
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
 * margin for the artifact store. Explicit file/env sizes are never disk-clamped.
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

    /** Total and usable bytes on a volume (for default clamp tests and probes). */
    public record DiskSpace(long totalBytes, long freeBytes) {
        public DiskSpace {
            if (totalBytes < 0) totalBytes = 0;
            if (freeBytes < 0) freeBytes = 0;
        }

        /** Probe the volume hosting {@code path} (or its nearest existing ancestor). */
        public static DiskSpace probe(Path path) {
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
        return resolve(JkDirs.userConfigFile(), System::getenv, () -> DiskSpace.probe(JkDirs.cache()));
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
    static JkCacheConfig resolve(Path userConfig, Function<String, String> env, DiskSpace disk) {
        return resolve(userConfig, env, () -> disk);
    }

    static JkCacheConfig resolve(Path userConfig, Function<String, String> env, Supplier<DiskSpace> cacheDisk) {
        Objects.requireNonNull(env, "env");
        Parsed p = parse(userConfig);
        OptionalDouble envCache = envPositiveDouble(env, "JK_MAX_CACHE_SIZE_GB");
        if (envCache.isEmpty()) {
            OptionalDouble mb = envPositiveDouble(env, "JK_MAX_CACHE_SIZE_MB");
            if (mb.isPresent()) {
                warnLegacyOnce("JK_MAX_CACHE_SIZE_MB is the pre-rename spelling — use JK_MAX_CACHE_SIZE_GB");
                envCache = OptionalDouble.of(mb.getAsDouble() / 1024.0);
            }
        }

        double logicalCache = isCi(env) ? CI_MAX_CACHE_SIZE_GB : DEFAULT_MAX_CACHE_SIZE_GB;
        DiskSpace cacheSpace = cacheDisk != null ? cacheDisk.get() : null;
        double defaultCache = clampDefaultGb(logicalCache, cacheSpace, () -> usedBytes(JkDirs.cache()));

        double cacheGb =
                envCache.isPresent() ? envCache.getAsDouble() : p.cacheGb().orElse(defaultCache);

        double defaultIncremental = Math.min(DEFAULT_INCREMENTAL_MAX_SIZE_GB, defaultCache * INCREMENTAL_DEFAULT_SHARE);
        OptionalDouble envIncremental = envPositiveDouble(env, "JK_INCREMENTAL_MAX_SIZE_GB");
        double incrementalGb = envIncremental.isPresent()
                ? envIncremental.getAsDouble()
                : p.incrementalGb().orElse(defaultIncremental);

        return new JkCacheConfig(
                EnvValues.bool(env, "JK_AUTO_PRUNE").orElse(p.autoPrune()),
                envNonNegativeInt(env, "JK_PRUNE_INTERVAL_DAYS").orElse(p.pruneIntervalDays()),
                cacheGb,
                incrementalGb);
    }

    /**
     * Machine defaults only (CI + disk clamp, no file/env size overrides). Used by the config
     * dashboard so {@code overridden} compares against what this host would pick if unset.
     */
    public static JkCacheConfig resolvedDefaults(Function<String, String> env) {
        return resolve(Path.of("/__jk_no_config__"), env, () -> DiskSpace.probe(JkDirs.cache()));
    }

    static JkCacheConfig resolvedDefaults(Function<String, String> env, DiskSpace disk) {
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
    static double clampDefaultGb(double logicalGb, DiskSpace disk, LongSupplier tierUsedBytes) {
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

    static boolean isCi(Function<String, String> env) {
        String ci = env.apply("CI");
        return "1".equals(ci) || (ci != null && "true".equalsIgnoreCase(ci));
    }

    private static Optional<Integer> envNonNegativeInt(Function<String, String> env, String name) {
        return EnvValues.intValue(env, name).filter(i -> i >= 0);
    }

    /** Size budgets: {@code 0} means unset (default applies), so only positive values count. */
    private static OptionalDouble envPositiveDouble(Function<String, String> env, String name) {
        return EnvValues.doubleValue(env, name)
                .filter(d -> d > 0)
                .map(OptionalDouble::of)
                .orElseGet(OptionalDouble::empty);
    }

    /**
     * Parse {@code [cache]} without CI/disk defaults — a missing size uses the {@link #DEFAULTS}
     * logical 4 GiB. Prefer {@link #resolve()} for the effective budget.
     */
    public static JkCacheConfig fromToml(Path file) {
        Parsed p = parse(file);
        return new JkCacheConfig(
                p.autoPrune(),
                p.pruneIntervalDays(),
                p.cacheGb().orElse(DEFAULT_MAX_CACHE_SIZE_GB),
                p.incrementalGb().orElse(DEFAULT_INCREMENTAL_MAX_SIZE_GB));
    }

    private record Parsed(
            boolean autoPrune, int pruneIntervalDays, OptionalDouble cacheGb, OptionalDouble incrementalGb) {}

    private static Parsed parse(Path file) {
        TomlScan scan = TomlScan.scan(
                file,
                "cache.auto-prune",
                "cache.prune-interval-days",
                "cache.max-cache-size-gb",
                "cache.max-cache-size-mb",
                "cache.incremental-max-size-gb");
        boolean autoPrune =
                switch (String.valueOf(scan.get("cache.auto-prune"))) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> DEFAULTS.autoPrune();
                };
        int interval = nonNegative(scanInt(scan, "cache.prune-interval-days")).orElse(DEFAULTS.pruneIntervalDays());
        OptionalDouble cacheGb = positiveDouble(scanDouble(scan, "cache.max-cache-size-gb"));
        // The pre-rename `-mb` key still pins the budget (converted) — ignoring it silently would
        // grow a deliberately small cache to the multi-GiB default on upgrade.
        if (cacheGb.isEmpty()) {
            cacheGb = legacyMbAsGb(scan, "cache.max-cache-size-mb");
        }
        OptionalDouble incrementalGb = positiveDouble(scanDouble(scan, "cache.incremental-max-size-gb"));
        return new Parsed(autoPrune, interval, cacheGb, incrementalGb);
    }

    private static OptionalDouble legacyMbAsGb(TomlScan scan, String key) {
        OptionalDouble mb = positiveDouble(scanDouble(scan, key));
        if (mb.isEmpty()) return OptionalDouble.empty();
        warnLegacyOnce(key + " is the pre-rename spelling — use " + key.replace("-mb", "-gb"));
        return OptionalDouble.of(mb.getAsDouble() / 1024.0);
    }

    private static final AtomicBoolean LEGACY_WARNED = new AtomicBoolean();

    private static void warnLegacyOnce(String message) {
        if (LEGACY_WARNED.compareAndSet(false, true)) {
            System.err.println("jk: warning: " + message);
        }
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

    private static OptionalDouble scanDouble(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return OptionalDouble.empty();
        try {
            return OptionalDouble.of(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static Optional<Integer> scanInt(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return Optional.empty();
        try {
            return Optional.of(Integer.valueOf(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> nonNegative(Optional<Integer> value) {
        return value.filter(i -> i >= 0);
    }

    private static OptionalDouble positiveDouble(OptionalDouble value) {
        if (value.isEmpty() || value.getAsDouble() <= 0) return OptionalDouble.empty();
        return value;
    }
}
