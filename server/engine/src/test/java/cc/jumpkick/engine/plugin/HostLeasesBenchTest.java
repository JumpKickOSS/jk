// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What one {@link HostLeases} exchange costs: the file lock, the read, the liveness check of the
 * other lines and the atomic rewrite. Timed alone, and against a second process that exchanges on
 * the same file in a tight loop, the worst contention two engines can make. Prints a table; asserts
 * only that every exchange returned.
 */
@Tag("bench")
class HostLeasesBenchTest {

    private static final int ROUNDS = 20_000;

    @TempDir
    Path engineDir;

    @Test
    void exchange_alone_and_against_a_second_process() throws Exception {
        report("alone, rewrite", timeExchanges(true));
        report("alone, unchanged", timeExchanges(false));
        Process rival = startRival();
        try {
            awaitLine(rival);
            report("contended, rewrite", timeExchanges(true));
            report("contended, unchanged", timeExchanges(false));
        } finally {
            rival.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private long[] timeExchanges(boolean rewrite) {
        HostLeases share = HostLeases.forThisProcess(engineDir);
        long[] nanos = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            long bytes = rewrite ? 1L + i : 1L;
            long start = System.nanoTime();
            share.publish(new HostLeases.Line(1L << 30, bytes, 1, List.of()));
            share.fresh();
            nanos[i] = System.nanoTime() - start;
        }
        return nanos;
    }

    private Process startRival() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        return new ProcessBuilder(
                        java, "-cp", System.getProperty("java.class.path"), Rival.class.getName(), engineDir.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** The rival's line is in the file: it is exchanging. */
    private void awaitLine(Process rival) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Path file = engineDir.resolve(HostLeases.FILE);
        while (!Files.exists(file) || !Files.readString(file).contains(rival.pid() + " ")) {
            assertThat(rival.isAlive()).isTrue();
            if (System.nanoTime() > deadline) throw new AssertionError("rival never wrote its line");
            Thread.sleep(20);
        }
    }

    private static void report(String what, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0);
        System.out.println(String.format(
                Locale.ROOT,
                "host-leases %-22s n=%d mean=%.1fus p50=%.1fus p90=%.1fus p99=%.1fus max=%.1fus",
                what,
                sorted.length,
                mean / 1e3,
                sorted[sorted.length / 2] / 1e3,
                sorted[(int) (sorted.length * 0.9)] / 1e3,
                sorted[(int) (sorted.length * 0.99)] / 1e3,
                sorted[sorted.length - 1] / 1e3));
        assertThat(sorted.length).isEqualTo(ROUNDS);
    }

    /** The second engine: exchanges a changing line on the same file until it is killed. */
    static final class Rival {
        public static void main(String[] args) {
            HostLeases share = HostLeases.forThisProcess(Path.of(args[0]));
            for (long i = 1; ; i++) {
                share.publish(new HostLeases.Line(1L << 30, i, 1, List.of()));
            }
        }
    }
}
