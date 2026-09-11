// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Cache-prune cadence: {@code .last-pruned} bookkeeping consulted by the engine's idle-boundary
 * prune.
 *
 * <p>The stamp records both <em>when</em> the last prune finished and <em>how full</em> it left the
 * action tier, because a fixed interval is the wrong cadence at both ends. A cache sitting at a
 * tenth of its budget gains nothing from a weekly pass; a cache that the last prune could only get
 * to 95 % full will be over budget again long before the week is out, and waiting for the interval
 * means running over budget for days. So pressure tightens the cadence rather than bypassing it —
 * bypassing it would re-fire on every idle boundary, since a prune that ends under pressure ends
 * under pressure no matter how often it runs.
 */
public final class CachePruneScheduler {

    /** Share of the action budget above which the last prune counts as leaving pressure behind. */
    static final double PRESSURE_FRACTION = 0.9;

    /** Cadence ceiling once the cache is under pressure — daily instead of the configured week. */
    static final int PRESSURE_INTERVAL_DAYS = 1;

    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private CachePruneScheduler() {}

    /**
     * What the last successful prune left behind.
     *
     * @param millis when it finished
     * @param finalActionBytes action-tier bytes remaining, or {@code -1} when the pass that wrote
     *     the stamp did not measure them (a wipe, a store-tier sweep)
     */
    public record Stamp(long millis, long finalActionBytes) {}

    /** True if the configured cadence — tightened by cache pressure — calls for a prune now. */
    public static boolean shouldRun(JkCacheConfig config, Path cacheRoot) throws IOException {
        Optional<Stamp> stamp = read(cacheRoot);
        if (stamp.isEmpty()) return true;
        long intervalDays = config.pruneIntervalDays();
        if (underPressure(config, stamp.get())) {
            intervalDays = Math.min(intervalDays, PRESSURE_INTERVAL_DAYS);
        }
        return (System.currentTimeMillis() - stamp.get().millis()) > intervalDays * DAY_MILLIS;
    }

    /** The last prune ended with the action tier at or above {@link #PRESSURE_FRACTION} of budget. */
    static boolean underPressure(JkCacheConfig config, Stamp stamp) {
        long budget = config.maxCacheSizeBytes();
        if (budget <= 0 || stamp.finalActionBytes() < 0) return false;
        return stamp.finalActionBytes() >= (long) (budget * PRESSURE_FRACTION);
    }

    /**
     * Read {@code .last-pruned}. Empty when absent or unparseable — both mean "no usable record",
     * and the caller's answer to that is to prune now and write a fresh one.
     */
    public static Optional<Stamp> read(Path cacheRoot) {
        Path stamp = CacheTree.LAST_PRUNED.under(cacheRoot);
        String[] fields;
        try {
            fields = Files.readString(stamp, StandardCharsets.UTF_8).trim().split("\\s+");
        } catch (IOException absentOrUnreadable) {
            return Optional.empty();
        }
        try {
            long millis = Long.parseLong(fields[0]);
            long bytes = fields.length > 1 ? Long.parseLong(fields[1]) : -1L;
            return Optional.of(new Stamp(millis, bytes));
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    /**
     * Record a completed prune. Best-effort: the maintenance itself succeeded, and a missing stamp
     * only re-runs it earlier.
     *
     * @param finalActionBytes action-tier bytes remaining, or {@code -1} when unmeasured
     */
    public static void write(Path cacheRoot, long nowMillis, long finalActionBytes) {
        try {
            Files.writeString(
                    CacheTree.LAST_PRUNED.under(cacheRoot),
                    nowMillis + " " + finalActionBytes + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // cadence hint only
        }
    }

    /**
     * Best-effort resolution of the absolute path to the running {@code jk} binary. Lives here so
     * engine doesn't depend on cli.
     *
     * <p>Order: {@code JK_EXE} override → {@code /proc/self/exe} / {@link ProcessHandle} when that
     * path is not a bare JVM launcher → Gradle/JVM installDist layout ({@code <app>/lib/*.jar} with
     * sibling {@code <app>/bin/jk}). A filename heuristic like {@code contains("jk")} is wrong in
     * both directions: it rejects a renamed native binary and accepts a java launcher installed
     * under a path that happens to contain "jk".
     */
    public static Optional<String> resolveJkExe() {
        String envOverride = System.getenv("JK_EXE");
        if (envOverride != null && !envOverride.isBlank()) {
            return Optional.of(envOverride);
        }
        Path candidate = null;
        try {
            // Authoritative on Linux, for native images and JVMs alike.
            candidate = Files.readSymbolicLink(Path.of("/proc/self/exe"));
        } catch (IOException | RuntimeException e) {
            // not Linux (or /proc unavailable) — fall through
            Log.debug("resolveJkExe: not Linux (or /proc unavailable)", e);
        }
        if (candidate == null) {
            try {
                candidate =
                        ProcessHandle.current().info().command().map(Path::of).orElse(null);
            } catch (RuntimeException e) {
                // fall through
                Log.debug("resolveJkExe: fall through", e);
            }
        }
        if (candidate != null && !isJavaLauncher(candidate)) {
            return Optional.of(candidate.toAbsolutePath().toString());
        }
        // JVM dist (installDist / application plugin): process is `java` with classpath under
        // <app>/lib/; the re-invokable launcher is <app>/bin/jk.
        return resolveFromJvmInstallLayout(System.getProperty("java.class.path", ""));
    }

    /**
     * When the process is a JVM launcher, recover the installDist/application script path from the
     * classpath: any {@code …/lib/<jar-or-dir>} entry implies {@code …/bin/jk} (or {@code jk.bat}).
     * Package-visible for tests.
     */
    static Optional<String> resolveFromJvmInstallLayout(String classPath) {
        for (Path entry : Classpaths.split(classPath)) {
            Path p;
            try {
                p = entry.toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                continue;
            }
            Path lib = libDirOf(p);
            if (lib == null) continue;
            Path home = lib.getParent();
            if (home == null) continue;
            for (String name : List.of("jk", "jk.bat", "jk.cmd")) {
                Path script = home.resolve("bin").resolve(name);
                if (Files.isRegularFile(script)) {
                    return Optional.of(script.toAbsolutePath().toString());
                }
            }
        }
        return Optional.empty();
    }

    /** {@code path} is a {@code lib/} directory, or a file directly under one. */
    private static @Nullable Path libDirOf(Path path) {
        if (Files.isDirectory(path) && "lib".equals(fileName(path))) return path;
        Path parent = path.getParent();
        if (parent != null && "lib".equals(fileName(parent))) return parent;
        return null;
    }

    private static String fileName(Path p) {
        Path name = p.getFileName();
        return name == null ? "" : name.toString();
    }

    private static boolean isJavaLauncher(Path p) {
        String name = fileName(p).toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }
}
