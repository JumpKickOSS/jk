// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The processor count of the machine a test is running on, read from the operating system rather
 * than from the JVM. {@link Runtime#availableProcessors()} answers with what the launcher pinned
 * ({@code -XX:ActiveProcessorCount}, which {@code jk test} sets to the host's share per test JVM),
 * so a wall-clock budget that compares the load average against it is comparing the machine's
 * load against one JVM's slice. On Linux the kernel publishes the online set as ranges in {@code
 * /sys/devices/system/cpu/online} ({@code 0-23}, {@code 0-3,8-11}); elsewhere the JVM's count is the
 * best available answer.
 */
public final class HostProcessors {

    static final Path ONLINE = Path.of("/sys/devices/system/cpu/online");

    private HostProcessors() {}

    /** Online processors of the host; never below the JVM's own count. */
    public static int count() {
        int jvm = Runtime.getRuntime().availableProcessors();
        try {
            if (Files.isRegularFile(ONLINE)) return Math.max(jvm, parseOnline(Files.readString(ONLINE)));
        } catch (IOException | IllegalArgumentException ignored) {
            // an unreadable or unexpected sysfs file leaves the JVM's count standing
        }
        return jvm;
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
