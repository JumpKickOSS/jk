// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The learned per-step timing ledger: cold fallback, EWMA, TOML round-trip, GC. */
class StepTimingsTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L; // a fixed "now" for determinism

    private static void record(Path cache, long now, StepTimings.Sample... s) {
        StepTimings.clearMemo();
        StepTimings.record(cache, List.of(s), 0.4, now);
    }

    @Test
    void cold_ledger_has_no_entries(@TempDir Path cache) {
        StepTimings.clearMemo();
        assertThat(StepTimings.load(cache).perUnit("/m/engine", "run-tests")).isEmpty();
    }

    @Test
    void a_new_phase_seeds_at_its_observed_rate(@TempDir Path cache) {
        record(cache, NOW, new StepTimings.Sample("/m/engine", "run-tests", 40.0));
        assertThat(StepTimings.load(cache).perUnit("/m/engine", "run-tests")).hasValue(40.0);
    }

    @Test
    void repeated_records_ewma_toward_the_latest(@TempDir Path cache) {
        record(cache, NOW, new StepTimings.Sample("/m/io", "compile-java", 100.0));
        record(cache, NOW + 1, new StepTimings.Sample("/m/io", "compile-java", 200.0));
        var v = StepTimings.load(cache).perUnit("/m/io", "compile-java");
        assertThat(v).isPresent();
        assertThat(v.getAsDouble()).isCloseTo(140.0, within(1e-6)); // 0.4*200 + 0.6*100
    }

    @Test
    void survives_a_toml_round_trip_with_pathy_keys(@TempDir Path cache) {
        String dir = "/home/me/src/oss/jk/kernel/engine";
        record(cache, NOW, new StepTimings.Sample(dir, "run-tests", 31.4));
        StepTimings.clearMemo(); // force a real re-read from disk
        var v = StepTimings.load(cache).perUnit(dir, "run-tests");
        assertThat(v).isPresent();
        assertThat(v.getAsDouble()).isCloseTo(31.4, within(1e-3));
    }

    @Test
    void negative_observations_are_ignored(@TempDir Path cache) {
        record(cache, NOW, new StepTimings.Sample("/m/x", "run-tests", -5.0));
        assertThat(StepTimings.load(cache).perUnit("/m/x", "run-tests")).isEmpty();
    }

    @Test
    void near_zero_observations_do_not_poison_learned_rates(@TempDir Path cache) {
        // cache-hit / empty work residuals must not drag rates toward zero.
        record(cache, NOW, new StepTimings.Sample("/m/x", "compile-java", 50.0));
        record(cache, NOW + 1, new StepTimings.Sample("/m/x", "compile-java", 0.0));
        assertThat(StepTimings.load(cache).perUnit("/m/x", "compile-java")).hasValue(50.0);
    }

    @Test
    void median_per_unit_is_empty_when_no_module_has_recorded_the_phase(@TempDir Path cache) {
        record(cache, NOW, new StepTimings.Sample("/m/a", "compile-java", 7.0));
        assertThat(StepTimings.load(cache).medianPerUnit("run-tests")).isEmpty();
    }

    @Test
    void median_per_unit_takes_the_middle_rate_across_modules(@TempDir Path cache) {
        record(cache, NOW, new StepTimings.Sample("/m/a", "run-tests", 1.0));
        record(cache, NOW, new StepTimings.Sample("/m/b", "run-tests", 9.0));
        record(cache, NOW, new StepTimings.Sample("/m/c", "run-tests", 2.0));
        // odd count → middle value (1, 2, 9 → 2); compile-java entries are ignored.
        record(cache, NOW, new StepTimings.Sample("/m/a", "compile-java", 100.0));
        var v = StepTimings.load(cache).medianPerUnit("run-tests");
        assertThat(v).isPresent();
        assertThat(v.getAsDouble()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void isolated_root_keeps_ledger_next_to_fixture(@TempDir Path root) {
        assertThat(StepTimings.file(root)).isEqualTo(root.resolve("timings.toml"));
    }
}
