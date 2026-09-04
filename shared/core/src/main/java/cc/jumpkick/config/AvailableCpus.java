// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Effective CPU count for concurrency defaultscgroup CPU quota when present, else
 * {@link Runtime#availableProcessors}.
 *
 * <p>Modern JDKs often already clamp {@code availableProcessors} to the container quota; this
 * probe makes the rule <em>explicit and testable</em> so {@code jobs = 0} / default “all cores”
 * means <strong>quota cores</strong> (e.g. Docker/k8s), not the host’s physical count when the
 * JVM would otherwise over-report.
 *
 * <p>Order: optional {@code jk.available.cpus} system property (tests) → cgroup v2 {@code cpu.max}
 * → cgroup v1 {@code cpu.cfs_quota_us} / {@code cpu.cfs_period_us} → JVM processors.
 *
 * <p>Memory budgets remain free-RAM / {@code HeapPlan}; this class is CPU-only.
 */
public final class AvailableCpus {

    /** Test override: positive integer string. Empty / unset → normal probe. */
    public static final String PROP = "jk.available.cpus";

    private static final Path SYS_FS_CGROUP = Path.of("/sys/fs/cgroup");
    private static final Path PROC_SELF_CGROUP = Path.of("/proc/self/cgroup");

    private AvailableCpus() {}

    /** Positive core count for jobs default / {@code jobs = 0}. */
    public static int count() {
        return count(
                SYS_FS_CGROUP,
                PROC_SELF_CGROUP,
                () -> Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Injectable probe for unit tests (synthetic cgroup trees + JVM fallback).
     *
     * @param cgroupRoot typically {@code /sys/fs/cgroup}
     * @param procSelfCgroup typically {@code /proc/self/cgroup}; may be missing
     * @param jvmFallback usually {@code availableProcessors}
     */
    public static int count(Path cgroupRoot, Path procSelfCgroup, IntSupplier jvmFallback) {
        int jvm = Math.max(1, jvmFallback.getAsInt());
        String prop = System.getProperty(PROP);
        if (prop != null && !prop.isBlank()) {
            try {
                int n = Integer.parseInt(prop.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        int fromCgroup = fromCgroup(cgroupRoot, procSelfCgroup);
        if (fromCgroup > 0) return fromCgroup;
        return jvm;
    }

    /** Quota-derived cores, or {@code -1} if unlimited / unreadable. */
    static int fromCgroup(Path cgroupRoot, Path procSelfCgroup) {
        if (cgroupRoot == null || !Files.isDirectory(cgroupRoot)) return -1;
        // Unified hierarchy (v2): cpu.max at root or under the process cgroup path.
        int v2 = readCpuMax(cgroupRoot.resolve("cpu.max"));
        if (v2 > 0) return v2;
        String rel = processCgroupRelative(procSelfCgroup);
        if (rel != null && !rel.isEmpty() && !rel.equals("/")) {
            Path nested = cgroupRoot
                    .resolve(rel.startsWith("/") ? rel.substring(1) : rel)
                    .resolve("cpu.max");
            v2 = readCpuMax(nested);
            if (v2 > 0) return v2;
        }
        // Legacy v1 controllers.
        int v1 = readCfsQuota(cgroupRoot.resolve("cpu"));
        if (v1 > 0) return v1;
        v1 = readCfsQuota(cgroupRoot.resolve("cpu,cpuacct"));
        if (v1 > 0) return v1;
        return -1;
    }

    /**
     * Parse cgroup v2 {@code cpu.max}: {@code "max 100000"} (unlimited) or {@code "200000 100000"}
     * (quota period, microseconds). Cores = ceil(quota / period).
     */
    static int readCpuMax(Path cpuMax) {
        String line = readFirstLine(cpuMax);
        if (line == null) return -1;
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 1) return -1;
        if ("max".equalsIgnoreCase(parts[0])) return -1;
        try {
            long quota = Long.parseLong(parts[0]);
            long period = parts.length >= 2 ? Long.parseLong(parts[1]) : 100_000L;
            return coresFromQuota(quota, period);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int readCfsQuota(Path cpuControllerDir) {
        if (cpuControllerDir == null || !Files.isDirectory(cpuControllerDir)) return -1;
        String q = readFirstLine(cpuControllerDir.resolve("cpu.cfs_quota_us"));
        String p = readFirstLine(cpuControllerDir.resolve("cpu.cfs_period_us"));
        if (q == null || p == null) return -1;
        try {
            long quota = Long.parseLong(q.trim());
            long period = Long.parseLong(p.trim());
            // -1 quota = unlimited
            if (quota < 0) return -1;
            return coresFromQuota(quota, period);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** ceil(quota/period), at least 1 when quota is positive. */
    static int coresFromQuota(long quotaUs, long periodUs) {
        if (quotaUs <= 0 || periodUs <= 0) return -1;
        long cores = (quotaUs + periodUs - 1) / periodUs;
        if (cores > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return Math.max(1, (int) cores);
    }

    /**
     * Relative path from unified cgroup v2 line {@code 0::/foo/bar}, or null.
     */
    static @Nullable String processCgroupRelative(Path procSelfCgroup) {
        if (procSelfCgroup == null || !Files.isRegularFile(procSelfCgroup)) return null;
        try {
            List<String> lines = Files.readAllLines(procSelfCgroup, StandardCharsets.UTF_8);
            for (String line : lines) {
                // v2: "0::/system.slice/docker-….scope"
                if (line.startsWith("0::")) {
                    return line.substring(3).trim();
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static @Nullable String readFirstLine(Path f) {
        if (f == null || !Files.isRegularFile(f)) return null;
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            return lines.isEmpty() ? null : lines.getFirst();
        } catch (IOException e) {
            return null;
        }
    }
}
