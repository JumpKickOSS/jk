// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The engine's own short-lived children are its own work: a build that forks many processes that
 * each live under a second reads as no more external load than one whose children live long.
 * Measured on the real host, so run it on an otherwise idle machine.
 */
// A live measurement of the host: another build on the machine moves the reading between its two
// samples, so it runs with the bench tier, not in a gate.
@Tag("bench")
class HostBusyShortChildrenTest {

    private static final int LANES = 6;
    private static final long PHASE_MS = 8_000;

    @Test
    @Timeout(120)
    void short_lived_children_are_not_external_load() throws Exception {
        Assumptions.assumeTrue(Files.isReadable(Path.of("/proc/stat")), "Linux only");
        HostBusy.forTests(null);
        HostBusy.warm();
        Thread.sleep(5_000);
        double idle = HostBusy.external();

        double longLived = busyWhile(() -> {
            List<Process> ps = new ArrayList<>();
            for (int i = 0; i < LANES; i++) ps.add(spin(PHASE_MS));
            for (Process p : ps) p.waitFor();
        });
        Thread.sleep(5_000);
        double shortLived = busyWhile(() -> {
            List<Thread> lanes = new ArrayList<>();
            for (int i = 0; i < LANES; i++) {
                lanes.add(Thread.ofPlatform().start(() -> {
                    long until = System.currentTimeMillis() + PHASE_MS;
                    try {
                        while (System.currentTimeMillis() < until) spin(300).waitFor();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            for (Thread t : lanes) t.join();
        });
        System.out.printf(
                "external load: idle %.3f, %d long-lived children %.3f, %d lanes of 300 ms children %.3f%n",
                idle, LANES, longLived, LANES, shortLived);
        assertThat(shortLived - idle)
                .as("short-lived children of this process read as external load")
                .isLessThan(0.05);
    }

    private interface Work {
        void run() throws Exception;
    }

    /** The external share read near the end of {@code work}, sampled while it runs. */
    private static double busyWhile(Work work) throws Exception {
        double[] read = {-1};
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(PHASE_MS - 1_500);
                read[0] = HostBusy.external();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        work.run();
        reader.join();
        return read[0];
    }

    /** One child spinning a core for {@code ms}. */
    private static Process spin(long ms) throws Exception {
        return new ProcessBuilder("timeout", String.format("%.3f", ms / 1000.0), "sha256sum", "/dev/zero")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
