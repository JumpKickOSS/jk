// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.EtaSpread;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rarely run shape keeps its own walls however many runs of a common shape come after it: an
 * integration-tier suite is priced from integration runs, not from the default tier's suite, after
 * a working day of {@code jk test -m} runs.
 */
class StepLedgerRetentionTest {

    private static final RunShapes.Shape INTEGRATION = new RunShapes.Shape("integration", false, false);

    @Test
    void a_rare_shape_keeps_its_walls_past_sixty_runs_of_another(@TempDir Path home) {
        StepLedger.append(
                home,
                INTEGRATION,
                List.of(
                        new StepLedger.Sample("/ws/leaf", "compile-java", "SUCCESS", 3_000),
                        new StepLedger.Sample("/ws/cli", "run-tests", "SUCCESS", 650_000)));
        StepLedger before = StepLedger.load(home);
        assertThat(before.price("/ws/cli", "run-tests", "SUCCESS", INTEGRATION, Set.of("/ws/leaf")))
                .hasValue(650_000);

        for (int i = 0; i < StepLedger.MAX_RUNS; i++) {
            StepLedger.append(
                    home,
                    RunShapes.Shape.DEFAULT,
                    List.of(
                            new StepLedger.Sample("/ws/leaf", "compile-java", "SUCCESS", 3_000),
                            new StepLedger.Sample("/ws/cli", "run-tests", "SUCCESS", 40_000)));
        }
        StepLedger after = StepLedger.load(home);
        System.out.println("integration suite priced "
                + after.price("/ws/cli", "run-tests", "SUCCESS", INTEGRATION, Set.of("/ws/leaf"))
                + " spread " + after.spread("/ws/cli", "run-tests", "SUCCESS", INTEGRATION, Set.of("/ws/leaf"))
                + " (PROFILE_BORROWED = " + EtaSpread.PROFILE_BORROWED + ")");
        assertThat(after.price("/ws/cli", "run-tests", "SUCCESS", INTEGRATION, Set.of("/ws/leaf")))
                .as("the integration suite after %d default-tier runs", StepLedger.MAX_RUNS)
                .hasValue(650_000);
    }

    @Test
    void past_the_cap_the_most_run_shape_gives_up_its_oldest_run(@TempDir Path home) {
        RunShapes.Shape forced = new RunShapes.Shape("", true, false);
        StepLedger.append(home, forced, List.of(new StepLedger.Sample("/ws/a", "guard", "SUCCESS", 9_000)));
        for (int i = 0; i < 3; i++) {
            StepLedger.append(
                    home, INTEGRATION, List.of(new StepLedger.Sample("/ws/a", "guard", "SUCCESS", 5_000 + i)));
        }
        for (int i = 0; i < StepLedger.MAX_RUNS; i++) {
            StepLedger.append(
                    home,
                    RunShapes.Shape.DEFAULT,
                    List.of(new StepLedger.Sample("/ws/a", "guard", "SUCCESS", 1_000 + i)));
        }
        StepLedger ledger = StepLedger.load(home);
        assertThat(ledger.runs()).isEqualTo(StepLedger.MAX_RUNS);
        assertThat(ledger.price("/ws/a", "guard", "SUCCESS", forced, Set.of())).hasValue(9_000);
        assertThat(ledger.price("/ws/a", "guard", "SUCCESS", INTEGRATION, Set.of()))
                .hasValue(5_001);
        assertThat(ledger.price("/ws/a", "guard", "SUCCESS", RunShapes.Shape.DEFAULT, Set.of()))
                .as("the default shape's newest runs, its oldest gone")
                .hasValue(1_000 + StepLedger.MAX_RUNS - 1 - StepLedger.SAME_FRONTIER / 2);
    }

    @Test
    void trim_drops_an_unreadable_line_first_then_the_most_populous_shapes_oldest() {
        List<String> lines = new ArrayList<>();
        lines.add("{\"profile\":\"integration\",\"forced\":false,\"filtered\":false,\"n\":0}");
        lines.add("not json");
        for (int i = 1; i < StepLedger.MAX_RUNS; i++) {
            lines.add("{\"profile\":\"\",\"forced\":false,\"filtered\":false,\"n\":" + i + "}");
        }
        lines.add("{\"profile\":\"\",\"forced\":false,\"filtered\":false,\"n\":99}");
        List<String> kept = StepLedger.trim(lines);
        assertThat(kept).hasSize(StepLedger.MAX_RUNS).doesNotContain("not json");
        assertThat(kept.getFirst()).contains("integration");
        assertThat(kept.get(1)).contains("\"n\":2}");
        assertThat(kept.getLast()).contains("\"n\":99}");
    }
}
