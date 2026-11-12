// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.StepCost.Rung;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A cold rung's next prices move toward how far it missed on this host (JK-626GS). */
class RungBiasTest {

    @TempDir
    Path tmp;

    private Path file() {
        return tmp.resolve(RungBias.FILE);
    }

    @Test
    void a_cold_rung_that_priced_compiles_at_half_their_wall_prices_the_next_at_twice() {
        RungBias.learn(
                file(),
                List.of(
                        new RungBias.Pair(Rung.CALIBRATION, "compile-java", 2_000, 4_000),
                        new RungBias.Pair(Rung.CALIBRATION, "compile-java", 3_000, 6_000),
                        new RungBias.Pair(Rung.CALIBRATION, "compile-test", 1_000, 2_000)));

        RungBias bias = RungBias.load(file());
        assertThat(bias.factor(Rung.CALIBRATION, "compile-kotlin"))
                .as("a key's first run sets it outright")
                .isCloseTo(2.0, within(1e-3));
        assertThat(bias.factor(Rung.CALIBRATION, "run-tests"))
                .as("another kind is not taught")
                .isEqualTo(1.0);
        assertThat(bias.factor(Rung.REPLAY, "compile-java"))
                .as("a step's own walls are no prior")
                .isEqualTo(1.0);
    }

    @Test
    void later_runs_move_the_bias_by_their_residual_so_recent_runs_dominate() {
        RungBias.learn(file(), List.of(new RungBias.Pair(Rung.HOST, "run-tests", 10_000, 20_000)));
        // The next run was priced with the doubled bias and still ran twice as long: the residual is
        // ln 2 again, and the bias moves ALPHA of the way further.
        RungBias.learn(file(), List.of(new RungBias.Pair(Rung.HOST, "run-tests", 20_000, 40_000)));

        double expected = Math.exp(Math.log(2) * (1 + RungBias.ALPHA));
        assertThat(RungBias.load(file()).factor(Rung.HOST, "run-tests")).isCloseTo(expected, within(1e-3));
    }

    @Test
    void a_run_is_one_observation_per_rung_and_kind_however_many_steps_it_priced() {
        List<RungBias.Pair> many = new ArrayList<>();
        for (int i = 0; i < 49; i++) many.add(new RungBias.Pair(Rung.CALIBRATION, "compile-java", 1_000, 1_000));
        many.add(new RungBias.Pair(Rung.CALIBRATION, "compile-java", 1_000, 50_000));
        RungBias.learn(file(), many);

        assertThat(RungBias.load(file()).factor(Rung.CALIBRATION, "compile-java"))
                .as("the median of the run's residuals: one outlier does not move it")
                .isCloseTo(1.0, within(1e-3));
    }

    @Test
    void short_steps_and_steps_never_priced_teach_nothing_and_the_factor_is_clamped() throws Exception {
        RungBias.learn(
                file(),
                List.of(
                        new RungBias.Pair(Rung.CALIBRATION, "package-jar", 100, 900),
                        new RungBias.Pair(Rung.CALIBRATION, "package-assembly", 0, 9_000)));
        assertThat(file()).doesNotExist();

        RungBias.learn(file(), List.of(new RungBias.Pair(Rung.PROJECT, "compile-java", 1_000, 100_000)));
        assertThat(RungBias.load(file()).factor(Rung.PROJECT, "compile-java")).isEqualTo(RungBias.MAX_FACTOR);
    }

    @Test
    void a_torn_line_is_skipped_and_the_rest_still_read() throws Exception {
        Files.writeString(file(), "# header\nCALIBRATION compile 0.6931 2\nCALIBRATION suite x 1\nNOPE compile 1 1\n");
        RungBias bias = RungBias.load(file());
        assertThat(bias.factor(Rung.CALIBRATION, "compile-java")).isCloseTo(2.0, within(1e-3));
        assertThat(bias.factor(Rung.CALIBRATION, "run-tests")).isEqualTo(1.0);
    }

    @Test
    void steps_group_into_the_kinds_the_scorer_reports() {
        assertThat(RungBias.kindOf("compile-java")).isEqualTo("compile");
        assertThat(RungBias.kindOf("compile-guard")).isEqualTo("guard");
        assertThat(RungBias.kindOf("guard-format")).isEqualTo("guard");
        assertThat(RungBias.kindOf("run-tests@integration")).isEqualTo("suite");
        assertThat(RungBias.kindOf("package-jar")).isEqualTo("package");
        assertThat(RungBias.kindOf("native-image")).isEqualTo("package");
        assertThat(RungBias.kindOf("resolve")).isEqualTo("other");
        assertThat(StepCost.Rung.CALIBRATION.prior()).isTrue();
        assertThat(StepCost.Rung.MODULE.prior()).isFalse();
    }
}
