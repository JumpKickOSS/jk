// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The models a run's countdown was seeded from, kept beside its record for the replay. */
class EtaModelsTest {

    @TempDir
    Path run;

    @Test
    void every_model_a_run_received_lands_beside_it_with_its_costs() throws Exception {
        Path lib = Path.of("/ws/lib");
        Path app = Path.of("/ws/app");
        EtaModels.put(
                901,
                WorkModel.of(
                        12_000,
                        4,
                        false,
                        true,
                        List.of(
                                new ModuleWorkCost(lib, Set.of(), 10, 4, 0, 6, 0, 0),
                                new ModuleWorkCost(app, Set.of(lib), 30, 20, 0, 10, 4_000, 12))));
        EtaModels.put(901, WorkModel.of(5_000, 4, false, true, List.of(new ModuleWorkCost(app, Set.of(), 30, 20))));

        EtaModels.write(run, 901);

        List<String> lines = Files.readAllLines(run.resolve(EtaModels.FILE));
        assertThat(lines)
                .as("a model that arrives mid-build is a line of its own")
                .hasSize(2);
        Map<?, ?> first = (Map<?, ?>) Objects.requireNonNull(MiniJson.parse(lines.get(0)));
        assertThat(num(first, "R0")).isEqualTo(12_000L);
        assertThat(num(first, "concurrency")).isEqualTo(4L);
        List<?> costs = MiniJson.list(first, "costs");
        assertThat(costs).hasSize(2);
        Map<?, ?> appCost = (Map<?, ?>) Objects.requireNonNull(costs.get(1));
        assertThat(appCost.get("dir")).isEqualTo(app.toString());
        assertThat(MiniJson.list(appCost, "prereqs")).hasSize(1).first().isEqualTo(lib.toString());
        assertThat(num(appCost, "suiteWall1")).isEqualTo(4_000L);
    }

    @Test
    void each_step_carries_the_rung_that_priced_it_and_the_seed_answers_for_it() throws Exception {
        // JK-626GS: the scorer judges each pricing layer against the walls it priced.
        Path lib = Path.of("/ws/lib");
        StepCost compile = new StepCost("compile-java", 20, StepCost.Kind.PRE, 10, 40, 0, StepCost.Rung.CALIBRATION);
        StepCost suite = new StepCost("run-tests", 60, StepCost.Kind.TEST, 50, 70, 4, StepCost.Rung.REPLAY);
        List<ModuleWorkCost> costs = List.of(new ModuleWorkCost(lib, Set.of(), 80, 60, 0, 0, 0, 0));
        EtaModels.put(903, new WorkModel(12_000, 4, false, true, costs, Map.of(lib, List.of(compile, suite))));
        StepCost later = new StepCost("compile-java", 5, StepCost.Kind.PRE, 5, 5, 0, StepCost.Rung.REPLAY);
        EtaModels.put(903, new WorkModel(9_000, 4, false, true, costs, Map.of(lib, List.of(later)), 1));

        assertThat(EtaModels.priced(903, lib.toString(), "compile-java"))
                .as("the seed's price, which the scorer attributes against")
                .isEqualTo(compile);
        assertThat(EtaModels.priced(903, lib.toString(), "package-jar")).isNull();
        assertThat(EtaModels.priced(903, "/ws/other", "compile-java")).isNull();

        EtaModels.write(run, 903);
        Map<?, ?> first = (Map<?, ?>) Objects.requireNonNull(
                MiniJson.parse(Files.readAllLines(run.resolve(EtaModels.FILE)).get(0)));
        List<?> rows = (List<?>)
                Objects.requireNonNull(((Map<?, ?>) Objects.requireNonNull(first.get("steps"))).get(lib.toString()));
        assertThat((List<?>) rows.get(0)).last().isEqualTo("CALIBRATION");
        assertThat((List<?>) rows.get(1)).last().isEqualTo("REPLAY");
    }

    @Test
    void a_run_that_received_no_model_writes_nothing() throws Exception {
        EtaModels.write(run, 902);
        assertThat(run.resolve(EtaModels.FILE)).doesNotExist();
    }

    private static long num(Map<?, ?> m, String key) {
        return ((Number) Objects.requireNonNull(m.get(key))).longValue();
    }
}
