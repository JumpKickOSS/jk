// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The host's stdout reader holds no virtual-thread carrier. A pipe read is a native read that
 * blocks the thread making it; a reader on a virtual thread keeps its carrier for as long as the
 * host is silent — between two scripts, that is until the idle reaper — and relies on the scheduler
 * lending a spare carrier for the time, which is denied when the JVM cannot start one. A test fork
 * runs with one carrier ({@code -XX:ActiveProcessorCount=1}), so one held carrier is every carrier:
 * a plan's step estimates queue behind the reader for as long as the host lives, and the host's own
 * reaper, a virtual thread, cannot wake to end it.
 */
class KtsSessionCarrierTest {

    /**
     * Under one carrier with no spare to lend, a virtual thread started beside a reader on a silent
     * host still runs. {@link KtsReaderCarrierProbe} attaches a session to a child that writes
     * nothing and reports whether the thread it started after that ran within five seconds.
     */
    @Test
    void a_virtual_thread_runs_beside_the_reader_of_a_silent_host_under_one_carrier() throws Exception {
        String java = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        Process probe = new ProcessBuilder(
                        java,
                        "-XX:ActiveProcessorCount=1",
                        "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                        "-cp",
                        classpath,
                        KtsReaderCarrierProbe.class.getName(),
                        "probe",
                        java,
                        classpath)
                .redirectErrorStream(true)
                .start();
        String said = read(probe);
        assertThat(probe.waitFor(30, TimeUnit.SECONDS)).as("the probe exited").isTrue();
        assertThat(said.strip()).as("the probe's verdict").endsWith("vthread-ran");
    }

    private static String read(Process p) throws IOException {
        try (var in = p.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
