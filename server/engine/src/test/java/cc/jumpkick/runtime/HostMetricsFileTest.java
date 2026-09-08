// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.model.JkVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code host-metrics.toml} format: calibration round-trip, staleness on read, language-bucket
 * folding, and the section-preserving merge that keeps the other writers' tables intact.
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
    void language_buckets_seed_compile_priors_when_mean_is_cold(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("host-metrics.toml");
        Files.writeString(f, """
                # host-metrics
                [calibration]
                schema = 1
                ms-per-weight = 150
                measured = true
                jk-version = "%s"
                updated = %d

                [mean.by_language.java]
                fixture_wall_ms = 2000
                compile_per_source_ms = 22
                [mean.by_language.kotlin]
                fixture_wall_ms = 4000
                compile_per_source_ms = 40
                """.formatted(JkVersion.VERSION, NOW));
        Calibration read = HostMetricsFile.readFrom(f, NOW);
        assertThat(read.compilePerSourceMs("compile-java")).isEqualTo(22L);
        assertThat(read.compilePerSourceMs("compile-kotlin")).isEqualTo(40L);
    }

    @Test
    void language_bucket_poison_values_are_rejected(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("host-metrics.toml");
        Files.writeString(f, """
                [calibration]
                schema = 1
                ms-per-weight = 150
                measured = true
                jk-version = "%s"
                updated = %d

                [mean.by_language.java]
                compile_per_source_ms = 5291
                """.formatted(JkVersion.VERSION, NOW));
        Calibration read = HostMetricsFile.readFrom(f, NOW);
        // Falls back to product baseline × scale (not the multi-second poison).
        assertThat(read.compilePerSourceMs("compile-java")).isLessThan(500L);
    }

    @Test
    void language_bucket_type_mismatch_never_poisons_the_whole_read(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("host-metrics.toml");
        // Float and string values in by_language buckets (tomlj getLong throws on both):
        // the bad bucket is skipped, the float bucket folds, and calibration stays present.
        Files.writeString(f, """
                [calibration]
                schema = 1
                ms-per-weight = 150
                measured = true
                jk-version = "%s"
                updated = %d

                [mean.by_language.java]
                compile_per_source_ms = 22.5
                [mean.by_language.kotlin]
                compile_per_source_ms = "oops"
                """.formatted(JkVersion.VERSION, NOW));
        Calibration read = HostMetricsFile.readFrom(f, NOW);
        assertThat(read.present()).isTrue();
        assertThat(read.measured()).isTrue();
        assertThat(read.compilePerSourceMs("compile-java")).isEqualTo(23L); // 22.5 rounded up
        assertThat(read.compilePerSourceMs("compile-kotlin")).isLessThan(500L); // baseline fallback
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

    /**
     * The merge in {@link HostMetricsFile#writeTo} walks the existing file by section header:
     * every table another writer owns — the harvest's {@code [mean]}, {@code [lock]},
     * {@code [fetch]}, {@code [bootstrap]}, and {@code jk optimize}'s
     * {@code [mean.by_language.*]} — must survive a calibration rewrite verbatim.
     */
    @Test
    void a_calibration_rewrite_preserves_every_foreign_section(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("host-metrics.toml");
        Files.writeString(f, """
                # host-metrics — probe + continuous means
                [mean]
                task.compile-java.wall_ms = 1234.5

                [lock]
                resolve_wall_ms = 210

                [fetch]
                central_get_ms = 95

                [bootstrap]
                probe_wall_ms = 5150

                [calibration]
                schema = 1
                ms-per-weight = 150
                measured = true
                jk-version = "%s"
                updated = %d

                [mean.by_language.java]
                fixture_wall_ms = 2000
                compile_per_source_ms = 22
                """.formatted(JkVersion.VERSION, NOW));

        HostMetricsFile.writeTo(f, Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW));

        String rewritten = Files.readString(f);
        assertThat(rewritten)
                .contains("task.compile-java.wall_ms = 1234.5")
                .contains("[lock]")
                .contains("resolve_wall_ms = 210")
                .contains("[fetch]")
                .contains("central_get_ms = 95")
                .contains("[bootstrap]")
                .contains("probe_wall_ms = 5150")
                .contains("[mean.by_language.java]")
                .contains("compile_per_source_ms = 22")
                .contains("ms-per-weight        = 42.5");
        // Preserved in the writer's fixed order: foreign sections first, calibration last.
        assertThat(rewritten.indexOf("[bootstrap]")).isLessThan(rewritten.indexOf("[calibration]"));
    }
}
