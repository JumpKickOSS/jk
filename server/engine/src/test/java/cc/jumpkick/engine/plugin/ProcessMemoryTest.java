// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Resident and peak memory read from the OS for this process and a child, on Linux, Windows and macOS. */
class ProcessMemoryTest {

    @Test
    void this_process_has_a_resident_set_no_larger_than_its_peak() {
        Assumptions.assumeTrue(Os.isLinux() || Os.isWindows() || Os.isDarwin());
        ProcessMemory.Usage self = ProcessMemory.self();
        assertThat(self.residentBytes()).as("resident").isGreaterThan(8L << 20);
        assertThat(self.peakBytes()).as("peak").isGreaterThanOrEqualTo(self.residentBytes());
    }

    @Test
    void a_child_jvm_is_read_by_pid_and_its_peak_covers_what_it_touched() throws Exception {
        Assumptions.assumeTrue(Os.isLinux() || Os.isWindows() || Os.isDarwin());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // A JVM that pre-touches a 256 MiB heap, then sleeps until destroyed.
        Process child = new ProcessBuilder(
                        java,
                        "-Xmx512m",
                        "-XX:+AlwaysPreTouch",
                        "-Xms256m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Sleeper.class.getName())
                .redirectErrorStream(true)
                .start();
        try {
            assertThat(child.getInputStream().read()).as("child ready").isEqualTo('R');
            ProcessMemory.Usage usage = ProcessMemory.of(child.pid());
            assertThat(usage.residentBytes()).as("resident").isGreaterThan(200L << 20);
            assertThat(usage.peakBytes()).as("peak").isGreaterThanOrEqualTo(usage.residentBytes());
            long self = ProcessMemory.self().residentBytes();
            ProcessMemory.Usage tree =
                    new ProcessMemory.Trees().read(ProcessHandle.current().pid());
            assertThat(tree.residentBytes()).as("this process and its child").isGreaterThan(self + (200L << 20));
        } finally {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void a_pid_that_is_gone_or_invalid_is_unknown() {
        assertThat(ProcessMemory.of(0).known()).isFalse();
        assertThat(ProcessMemory.of(-5)).isEqualTo(ProcessMemory.Usage.UNKNOWN);
    }

    /** A child that pre-touched its heap says {@code R} and waits. */
    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            System.out.print('R');
            System.out.flush();
            Thread.sleep(60_000);
        }
    }
}
