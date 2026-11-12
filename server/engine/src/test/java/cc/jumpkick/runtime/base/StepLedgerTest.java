// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step is priced from its own recent walls under the same status and shape, the same edit first:
 * the guard lane that took 15 s on a one-module edit and 70 s on a forced build is priced 15 s for
 * the one-module edit, where one average across both priced it at neither.
 */
class StepLedgerTest {

    private static final RunShapes.Shape DEFAULT = RunShapes.Shape.DEFAULT;

    private static List<StepLedger.Sample> run(String frontierModule, long guardMs, long suiteMs) {
        return List.of(
                new StepLedger.Sample(frontierModule, "compile-java", "SUCCESS", 3_000),
                new StepLedger.Sample("/ws/guard", "guard", "SUCCESS", guardMs),
                new StepLedger.Sample("/ws/engine", "run-tests", "SUCCESS", suiteMs));
    }

    @Test
    void the_same_frontier_is_the_first_rung_then_any_frontier(@TempDir Path home) {
        StepLedger.append(home, DEFAULT, run("/ws/leaf", 15_000, 60_000));
        StepLedger.append(home, DEFAULT, run("/ws/core", 70_000, 60_000));
        StepLedger.append(home, DEFAULT, run("/ws/leaf", 16_000, 60_000));
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.price("/ws/guard", "guard", "SUCCESS", DEFAULT, Set.of("/ws/leaf")))
                .hasValue(16_000);
        assertThat(ledger.price("/ws/guard", "guard", "SUCCESS", DEFAULT, Set.of("/ws/core")))
                .hasValue(70_000);
        assertThat(ledger.price("/ws/guard", "guard", "SUCCESS", DEFAULT, Set.of("/ws/other")))
                .as("no same-frontier run: the median of the latest")
                .hasValue(16_000);
    }

    @Test
    void a_restore_is_not_priced_as_a_run(@TempDir Path home) {
        StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/cli", "native-image", "SUCCESS", 31_000)));
        StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/cli", "native-image", "SKIPPED", 900)));
        StepLedger ledger = StepLedger.load(home);
        assertThat(ledger.price("/ws/cli", "native-image", "SKIPPED", DEFAULT, Set.of()))
                .hasValue(900);
        assertThat(ledger.price("/ws/cli", "native-image", "SUCCESS", DEFAULT, Set.of()))
                .hasValue(31_000);
    }

    @Test
    void a_suite_is_priced_under_its_own_profile_and_never_from_a_filtered_run(@TempDir Path home) {
        RunShapes.Shape integration = new RunShapes.Shape("integration", false, false);
        RunShapes.Shape filtered = new RunShapes.Shape("", false, true);
        StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/cli", "run-tests", "SUCCESS", 40_000)));
        StepLedger.append(
                home, integration, List.of(new StepLedger.Sample("/ws/cli", "run-tests", "SUCCESS", 650_000)));
        StepLedger.append(home, filtered, List.of(new StepLedger.Sample("/ws/cli", "run-tests", "SUCCESS", 1_500)));
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.price("/ws/cli", "run-tests", "SUCCESS", DEFAULT, Set.of()))
                .hasValue(40_000);
        assertThat(ledger.price("/ws/cli", "run-tests", "SUCCESS", integration, Set.of()))
                .hasValue(650_000);
        assertThat(ledger.price("/ws/cli", "run-tests", "SUCCESS", filtered, Set.of()))
                .as("a filtered suite is priced by its selected classes")
                .isEmpty();
    }

    @Test
    void a_forced_compile_is_not_an_incremental_one(@TempDir Path home) {
        RunShapes.Shape forced = new RunShapes.Shape("", true, false);
        StepLedger.append(home, forced, List.of(new StepLedger.Sample("/ws/a", "compile-java", "SUCCESS", 20_000)));
        StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/a", "compile-java", "SUCCESS", 2_000)));
        StepLedger ledger = StepLedger.load(home);
        assertThat(ledger.price("/ws/a", "compile-java", "SUCCESS", DEFAULT, Set.of()))
                .hasValue(2_000);
        assertThat(ledger.price("/ws/a", "compile-java", "SUCCESS", forced, Set.of()))
                .hasValue(20_000);
    }

    @Test
    void the_ledger_keeps_its_latest_runs_and_drops_instant_steps(@TempDir Path home) throws Exception {
        for (int i = 0; i < StepLedger.MAX_RUNS + 5; i++) {
            StepLedger.append(
                    home,
                    DEFAULT,
                    List.of(
                            new StepLedger.Sample("/ws/a", "compile-java", "SUCCESS", 1_000 + i),
                            new StepLedger.Sample("/ws/a", "write-stamp", "SUCCESS", 2)));
        }
        assertThat(Files.readAllLines(home.resolve(StepLedger.FILE))).hasSize(StepLedger.MAX_RUNS);
        StepLedger ledger = StepLedger.load(home);
        assertThat(ledger.runs()).isEqualTo(StepLedger.MAX_RUNS);
        assertThat(ledger.price("/ws/a", "write-stamp", "SUCCESS", DEFAULT, Set.of()))
                .isEmpty();
    }
}
