// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.base.HostLearnedRates;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A calibration in the host store: round-trip, staleness on read, and a rewrite that keeps the
 * store's other rates and sections.
 */
class HostMetricsFileTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void round_trips_through_toml(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("calibration.toml");
        Calibration written = Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW);
        HostMetricsFile.writeTo(f, written);
        Calibration read = HostMetricsFile.readFrom(f, NOW);
        assertThat(read.present()).isTrue();
        assertThat(read.measured()).isTrue();
        assertThat(read.msPerWeight()).isCloseTo(42.5, within(1e-3));
    }

    @Test
    void missing_file_is_absent_and_falls_back_to_the_constant() {
        Calibration absent = HostMetricsFile.readFrom(Path.of("/no/such/calibration.toml"), NOW);
        assertThat(absent.present()).isFalse();
        assertThat(absent.msPerWeight()).isEqualTo((double) EffortWeights.MS_PER_WEIGHT);
    }

    @Test
    void stale_file_reads_as_absent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("calibration.toml");
        HostMetricsFile.writeTo(f, Calibration.testInstance(42.5, true, "0.0.0-OLD", NOW));
        assertThat(HostMetricsFile.readFrom(f, NOW).present()).isFalse();
    }

    @Test
    void learned_rates_round_trip_toml(@TempDir Path dir) throws Exception {
        HostLearnedRates learned = new HostLearnedRates()
                .withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 42, 0)
                .withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 48, 0);
        Calibration written = Calibration.testInstance(100.0, true, JkVersion.VERSION, NOW, learned, 200, 15, 20);
        Path f = dir.resolve("calibration.toml");
        HostMetricsFile.writeTo(f, written);
        Calibration read = HostMetricsFile.readFrom(f, NOW);
        assertThat(read.present()).isTrue();
        // Scalars only on disk — trimmed mean persists as a single prior sample.
        assertThat(read.learned().sampleCount(HostLearnedRates.RUN_TESTS_PER_METHOD_MS))
                .isEqualTo(1);
        assertThat(read.learned().meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS))
                .hasValueCloseTo(45.0, within(1e-6));
        assertThat(read.probeTestMethodMs()).isEqualTo(15);
        assertThat(read.probeTestSuiteStartupMs()).isEqualTo(200);
        assertThat(read.testMethodMs()).isEqualTo(45); // learned wins over probe
    }

    @Test
    void a_fresh_continuous_mean_overwrites_the_one_already_on_disk(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(HostStore.FILE);
        Files.writeString(f, """
                [rates]
                package-jar-ms = 1655.625
                run-tests-per-method-ms = 5000
                [bias]
                CALIBRATION compile 0.1 1
                """);

        HostLearnedRates learned = new HostLearnedRates().withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 42, 0);
        HostMetricsFile.writeTo(f, Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW, learned, 200, 15, 20));

        assertThat(HostMetricsFile.readFrom(f, NOW).learned().meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS))
                .as("the value just written, not the 5000 it replaced")
                .hasValueCloseTo(42.0, within(1e-6));
        assertThat(Files.readString(f))
                .as("another rate and another section are carried forward byte for byte")
                .contains("package-jar-ms = 1655.625")
                .contains("CALIBRATION compile 0.1 1")
                .doesNotContain("5000");
    }

    /**
     * Two writers folding one file at once keep each other's rates: the read, the fold and the
     * replace happen under the ledger lock, so a writer never replaces the file with a fold of a
     * copy it read before the other wrote.
     */
    @Test
    void concurrent_folds_keep_both_writers_rates(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(HostStore.FILE);
        int rounds = 40;
        Thread a = writer(f, HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 42, rounds);
        Thread b = writer(f, HostLearnedRates.PACKAGE_JAR_MS, 300, rounds);
        a.start();
        b.start();
        a.join();
        b.join();
        String text = Files.readString(f);
        assertThat(text).contains(HostLearnedRates.RUN_TESTS_PER_METHOD_MS + " = ");
        assertThat(text).contains(HostLearnedRates.PACKAGE_JAR_MS + " = ");
    }

    private static Thread writer(Path f, String key, int ms, int rounds) {
        HostLearnedRates learned = new HostLearnedRates().withSample(key, ms, 0);
        Calibration c = Calibration.testInstance(100.0, true, JkVersion.VERSION, NOW, learned, 200, 15, 20);
        return new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                try {
                    HostMetricsFile.writeTo(f, c);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
