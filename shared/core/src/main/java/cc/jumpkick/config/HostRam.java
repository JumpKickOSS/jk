// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Log;
import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The host's RAM, for the defaults that scale with it (the engine's heap, {@link
 * JkEngineConfig#defaultMaxHeapMb}): {@code MemTotal} from {@code /proc/meminfo} on Linux, a smaller
 * cgroup v2 {@code memory.max} when the process runs inside one, else the JVM's own reading of the
 * machine. {@code -1} when nothing says.
 */
public final class HostRam {

    private static final Path MEMINFO = Path.of("/proc/meminfo");
    private static final Path CGROUP_MAX = Path.of("/sys/fs/cgroup/memory.max");

    private HostRam() {}

    /** The host's RAM in bytes, or {@code -1}. */
    public static long totalBytes() {
        long total = meminfoTotal(MEMINFO);
        long cgroup = cgroupMax(CGROUP_MAX);
        if (cgroup > 0 && (total <= 0 || cgroup < total)) total = cgroup;
        if (total > 0) return total;
        return fromJvm();
    }

    /** {@code MemTotal} of a {@code /proc/meminfo}, in bytes; {@code -1} when absent or unreadable. */
    static long meminfoTotal(Path meminfo) {
        if (!Files.isReadable(meminfo)) return -1;
        try (BufferedReader in = Files.newBufferedReader(meminfo, StandardCharsets.US_ASCII)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (!line.startsWith("MemTotal:")) continue;
                String[] words = line.substring("MemTotal:".length()).trim().split("\\s+");
                long kib = Long.parseLong(words[0]);
                return kib * 1024L;
            }
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("host ram: " + meminfo + " unreadable", unreadable);
        }
        return -1;
    }

    /** A cgroup v2 {@code memory.max} in bytes; {@code -1} when absent, unreadable or {@code max}. */
    static long cgroupMax(Path file) {
        if (!Files.isReadable(file)) return -1;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
            String line = in.readLine();
            if (line == null || line.isBlank() || line.strip().equals("max")) return -1;
            return Long.parseLong(line.strip());
        } catch (IOException | RuntimeException unreadable) {
            return -1;
        }
    }

    private static long fromJvm() {
        try {
            @Nullable Object os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof OperatingSystemMXBean bean) return bean.getTotalMemorySize();
        } catch (RuntimeException | LinkageError unavailable) {
            Log.debug("host ram: the JVM does not say", unavailable);
        }
        return -1;
    }
}
