// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The processor count of the machine a test is running on, read from the operating system rather
 * than from the JVM. {@link Runtime#availableProcessors()} answers with what the launcher pinned
 * ({@code -XX:ActiveProcessorCount}, which a module's {@code [test] jvm-args} may set), so a
 * wall-clock budget that compares the load average against it is comparing the machine's
 * load against one JVM's slice. On Linux the kernel publishes the online set as ranges in {@code
 * /sys/devices/system/cpu/online} ({@code 0-23}, {@code 0-3,8-11}); elsewhere the JVM's count is the
 * best available answer.
 *
 * <p>A CPU quota on the root of the cgroup mount caps every process on the host, so it narrows the
 * online count: a WSL or container host that lists 20 processors online under a 7-CPU root quota
 * runs on 7. The quota of the process's own cgroup is left out, since that is one slice of the
 * host, as the JVM's count is.
 */
public final class HostProcessors {

    static final Path ONLINE = Path.of("/sys/devices/system/cpu/online");
    static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");

    private HostProcessors() {}

    /** Online processors of the host, narrowed to a root cgroup CPU quota; never below the JVM's own count. */
    public static int count() {
        return count(ONLINE, CGROUP_ROOT, Runtime.getRuntime().availableProcessors());
    }

    /** {@link #count()} read from the given sysfs files, for a JVM that sees {@code jvm} processors. */
    static int count(Path online, Path cgroupRoot, int jvm) {
        int host;
        try {
            if (!Files.isRegularFile(online)) return jvm;
            host = parseOnline(Files.readString(online));
        } catch (IOException | IllegalArgumentException ignored) {
            // an unreadable or unexpected sysfs file leaves the JVM's count standing
            return jvm;
        }
        int quota = rootQuota(cgroupRoot);
        if (quota > 0) host = Math.min(host, quota);
        return Math.max(jvm, host);
    }

    /**
     * Whole processors the root cgroup's CPU quota allows, rounded up as the JVM rounds its own, or
     * {@code -1} when there is no quota or it cannot be read: cgroup v2's {@code cpu.max} ({@code
     * "700000 100000"}, or {@code "max 100000"} for none), else cgroup v1's {@code
     * cpu.cfs_quota_us} over {@code cpu.cfs_period_us} ({@code -1} for none).
     */
    static int rootQuota(Path cgroupRoot) {
        try {
            Path cpuMax = cgroupRoot.resolve("cpu.max");
            if (Files.isRegularFile(cpuMax)) {
                String[] parts = Files.readString(cpuMax).strip().split("\\s+");
                if (parts[0].equals("max")) return -1;
                return cores(Long.parseLong(parts[0]), parts.length > 1 ? Long.parseLong(parts[1]) : 100_000L);
            }
            for (String controller : new String[] {"cpu", "cpu,cpuacct"}) {
                Path quota = cgroupRoot.resolve(controller).resolve("cpu.cfs_quota_us");
                Path period = cgroupRoot.resolve(controller).resolve("cpu.cfs_period_us");
                if (Files.isRegularFile(quota) && Files.isRegularFile(period)) {
                    return cores(
                            Long.parseLong(Files.readString(quota).strip()),
                            Long.parseLong(Files.readString(period).strip()));
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // an unreadable or unexpected cgroup file is no quota
        }
        return -1;
    }

    private static int cores(long quotaUs, long periodUs) {
        if (quotaUs <= 0 || periodUs <= 0) return -1;
        return (int) Math.min(Integer.MAX_VALUE, (quotaUs + periodUs - 1) / periodUs);
    }

    /** How many processors a Linux {@code online} list names: comma-separated {@code a-b} ranges or single ids. */
    static int parseOnline(String online) {
        int total = 0;
        for (String part : online.strip().split(",")) {
            if (part.isBlank()) continue;
            int dash = part.indexOf('-');
            if (dash < 0) {
                Integer.parseInt(part.strip());
                total++;
            } else {
                int from = Integer.parseInt(part.substring(0, dash).strip());
                int to = Integer.parseInt(part.substring(dash + 1).strip());
                if (to < from) throw new IllegalArgumentException("descending range " + part);
                total += to - from + 1;
            }
        }
        if (total == 0) throw new IllegalArgumentException("no processors listed in " + online.strip());
        return total;
    }
}
