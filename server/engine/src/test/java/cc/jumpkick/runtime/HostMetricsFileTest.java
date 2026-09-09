// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.base.HostLearnedRates;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

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
     * [mean] is co-owned by MetricsHarvest (run keys) and this writer (continuous rates), so a
     * calibration rewrite has to merge into the single table rather than append a second header.
     * Two [mean] tables are illegal TOML, and the file's two readers resolve the duplicate in
     * opposite directions — tomlj keeps the first copy of a key, MetricsHarvest keeps the last.
     */
    @Test
    void a_rewrite_emits_one_mean_table_and_parses_without_errors(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("host-metrics.toml");
        HostLearnedRates learned = new HostLearnedRates().withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 42, 0);
        Calibration c = Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW, learned, 200, 15, 20);

        HostMetricsFile.writeTo(f, c);
        HostMetricsFile.writeTo(f, c);
        HostMetricsFile.writeTo(f, c);

        String text = Files.readString(f);
        assertThat(text.lines().filter(l -> l.strip().equals("[mean]")).count())
                .as("one [mean] no matter how many rewrites")
                .isEqualTo(1);
        assertThat(Toml.parse(text).hasErrors()).as("valid TOML").isFalse();
    }

    @Test
    void a_fresh_continuous_mean_overwrites_the_one_already_on_disk(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("host-metrics.toml");
        Files.writeString(f, """
                [mean]
                task.compile-java.wall-ms = 1655.625
                run-tests-per-method-ms = 5000
                """);

        HostLearnedRates learned = new HostLearnedRates().withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 42, 0);
        HostMetricsFile.writeTo(
                f, Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW, learned, 200, 15, 20));

        assertThat(HostMetricsFile.readFrom(f, NOW).learned().meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS))
                .as("the value just written, not the 5000 it replaced")
                .hasValueCloseTo(42.0, within(1e-6));
        assertThat(Files.readString(f))
                .as("a run-harvest key is carried forward byte for byte")
                .contains("task.compile-java.wall-ms = 1655.625")
                .doesNotContain("5000");
    }

    @Test
    void a_legacy_duplicate_mean_collapses_and_the_later_copy_wins(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("host-metrics.toml");
        Files.writeString(f, """
                [mean]
                task.guard.wall-ms = 182.548
                native-image-ms-per-mib = 111

                [mean]
                native-image-ms-per-mib = 222

                [calibration]
                schema = 1
                """);

        HostMetricsFile.writeTo(f, Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW));

        String text = Files.readString(f);
        assertThat(text.lines().filter(l -> l.strip().equals("[mean]")).count()).isEqualTo(1);
        assertThat(Toml.parse(text).hasErrors()).isFalse();
        assertThat(text)
                .contains("task.guard.wall-ms = 182.548")
                .contains("native-image-ms-per-mib = 222")
                .doesNotContain("native-image-ms-per-mib = 111");
    }

    /**
     * The foreign-section list is {@link MetricsHarvest#FOREIGN_SECTIONS} and not a second copy of
     * it. The two lists diverged once — this writer's was missing {@code [probe]}, so a calibration
     * rewrite discarded a section the harvest writer and {@code AggregatedMetrics} both preserve.
     * Driven off the constant, so adding a section to it without teaching this writer fails here.
     */
    @Test
    void every_foreign_section_survives_a_calibration_rewrite(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("host-metrics.toml");
        StringBuilder fixture = new StringBuilder("[mean]\ntask.compile-java.wall-ms = 1234.5\n");
        for (String section : MetricsHarvest.FOREIGN_SECTIONS) {
            fixture.append("\n[").append(section).append("]\n").append(section).append("-marker = 7\n");
        }
        Files.writeString(f, fixture.toString());

        HostMetricsFile.writeTo(f, Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW));

        String rewritten = Files.readString(f);
        for (String section : MetricsHarvest.FOREIGN_SECTIONS) {
            assertThat(rewritten)
                    .as("[%s] must survive a calibration rewrite", section)
                    .contains("[" + section + "]")
                    .contains(section + "-marker = 7");
        }
        assertThat(Toml.parse(rewritten).hasErrors()).isFalse();
        // Named, not just iterated: the loop above shrinks with the constant, so it would follow
        // [probe] straight back out of the list. [probe] has no writer and three readers, so
        // nothing else would notice it going.
        assertThat(MetricsHarvest.FOREIGN_SECTIONS).contains("probe");
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
