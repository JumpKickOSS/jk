// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void any_step_is_priced_from_runs_of_its_own_shape_first(@TempDir Path home) {
        RunShapes.Shape forcedBuild = new RunShapes.Shape("", true, false);
        RunShapes.Shape forcedIntegration = new RunShapes.Shape("integration", true, false);
        RunShapes.Shape filteredTest = new RunShapes.Shape("", false, true);
        StepLedger.append(home, forcedBuild, List.of(new StepLedger.Sample("/ws/g", "guard", "SUCCESS", 69_000)));
        StepLedger.append(home, forcedIntegration, List.of(new StepLedger.Sample("/ws/g", "guard", "SUCCESS", 40_000)));
        StepLedger.append(home, filteredTest, List.of(new StepLedger.Sample("/ws/g", "guard", "SUCCESS", 15_000)));
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.price("/ws/g", "guard", "SUCCESS", forcedBuild, Set.of()))
                .hasValue(69_000);
        assertThat(ledger.price("/ws/g", "guard", "SUCCESS", filteredTest, Set.of()))
                .hasValue(15_000);
        assertThat(ledger.price("/ws/g", "guard", "SUCCESS", DEFAULT, Set.of()))
                .as("no run of the default shape: the latest runs under the same load, a light one")
                .hasValue(15_000);
    }

    @Test
    void a_steps_range_is_the_spread_of_the_walls_it_is_priced_from_and_never_narrower_than_a_wobble(
            @TempDir Path home) {
        for (long ms : new long[] {10_000, 12_000, 20_000, 11_000, 30_000}) {
            StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/a", "run-tests", "SUCCESS", ms)));
        }
        StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/b", "guard", "SUCCESS", 4_000)));
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.price("/ws/a", "run-tests", "SUCCESS", DEFAULT, Set.of()))
                .hasValue(12_000);
        assertThat(ledger.range("/ws/a", "run-tests", "SUCCESS", DEFAULT, Set.of()))
                .as("the 80th percentile of 10, 11, 12, 20, 30 s above; below, the 20th (10.8 s) is"
                        + " closer than the least spread under the 12 s median")
                .hasValueSatisfying(r -> assertThat(r).containsExactly(10_200, 22_000));
        assertThat(ledger.range("/ws/b", "guard", "SUCCESS", DEFAULT, Set.of()))
                .as("one sample: the least spread either side")
                .hasValueSatisfying(r -> assertThat(r).containsExactly(3_400, 4_600));
        assertThat(ledger.range("/ws/c", "guard", "SUCCESS", DEFAULT, Set.of())).isEmpty();
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

    /** A run whose main compiles ran in {@code compiling} of {@code modules} modules, and a guard. */
    private static List<StepLedger.Sample> build(int modules, int compiling, long guardMs) {
        List<StepLedger.Sample> out = new ArrayList<>();
        for (int i = 0; i < modules; i++) {
            out.add(new StepLedger.Sample("/ws/m" + i, "compile-java", i < compiling ? "SUCCESS" : "SKIPPED", 2_000));
        }
        out.add(new StepLedger.Sample("/ws/g", "guard", "SUCCESS", guardMs));
        return out;
    }

    @Test
    void a_first_build_that_compiles_most_modules_is_recorded_as_a_full_rebuild(@TempDir Path home) {
        StepLedger.append(home, DEFAULT, build(6, 6, 69_000));
        StepLedger ledger = StepLedger.load(home);
        RunShapes.Shape full = new RunShapes.Shape("", true, false);
        assertThat(ledger.price(
                        "/ws/g",
                        "guard",
                        "SUCCESS",
                        full,
                        Set.of("/ws/m0", "/ws/m1", "/ws/m2", "/ws/m3", "/ws/m4", "/ws/m5")))
                .as("a cold first build is a full rebuild: priced so for the next one")
                .hasValue(69_000);
        assertThat(StepLedger.fullRebuild(3, 6)).isTrue();
        assertThat(StepLedger.fullRebuild(2, 6)).isFalse();
        assertThat(StepLedger.fullRebuild(2, 2)).as("too few modules to tell").isFalse();
    }

    @Test
    void a_step_seen_only_under_a_full_rebuild_is_priced_for_a_light_run_at_the_load_ratio(@TempDir Path home) {
        StepLedger.append(home, DEFAULT, build(6, 6, 60_000));
        StepLedger ledger = StepLedger.load(home);
        assertThat(ledger.price("/ws/g", "guard", "SUCCESS", DEFAULT, Set.of("/ws/m0")))
                .as("no step has run under both loads: the default ratio")
                .hasValue(Math.round(60_000 * StepLedger.DEFAULT_LIGHT_OVER_HEAVY));
        assertThat(ledger.range("/ws/g", "guard", "SUCCESS", DEFAULT, Set.of("/ws/m0")))
                .as("a price read across loads is a wide one")
                .hasValueSatisfying(r -> assertThat(r[1] - r[0]).isGreaterThanOrEqualTo(2 * 12_000));
    }

    @Test
    void the_load_ratio_is_learned_from_steps_that_ran_under_both(@TempDir Path home) {
        RunShapes.Shape full = new RunShapes.Shape("", true, false);
        for (String step : List.of("guard", "package-jar", "javadoc")) {
            StepLedger.append(home, full, List.of(new StepLedger.Sample("/ws/a", step, "SUCCESS", 10_000)));
            StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/a", step, "SUCCESS", 3_000)));
        }
        StepLedger.append(home, full, List.of(new StepLedger.Sample("/ws/b", "guard", "SUCCESS", 40_000)));
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.lightOverHeavy()).isEqualTo(0.3);
        assertThat(ledger.price("/ws/b", "guard", "SUCCESS", DEFAULT, Set.of())).hasValue(12_000);
    }

    @Test
    void a_compile_is_never_priced_across_loads(@TempDir Path home) {
        RunShapes.Shape full = new RunShapes.Shape("", true, false);
        StepLedger.append(home, full, List.of(new StepLedger.Sample("/ws/a", "compile-java", "SUCCESS", 20_000)));
        assertThat(StepLedger.load(home).price("/ws/a", "compile-java", "SUCCESS", DEFAULT, Set.of()))
                .as("a full compile is other work than an incremental one")
                .isEmpty();
    }

    @Test
    void a_suite_under_a_profile_it_never_ran_in_borrows_its_default_wall_as_a_floor(@TempDir Path home) {
        RunShapes.Shape integration = new RunShapes.Shape("integration", false, false);
        StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/core", "run-tests", "SUCCESS", 8_000)));
        StepLedger ledger = StepLedger.load(home);
        assertThat(ledger.price("/ws/core", "run-tests", "SUCCESS", integration, Set.of()))
                .hasValue(8_000);
        assertThat(ledger.range("/ws/core", "run-tests", "SUCCESS", integration, Set.of()))
                .hasValueSatisfying(r -> assertThat(r[1]).isEqualTo(24_000));
    }

    @Test
    void the_steps_a_shape_last_ran_in_a_module_are_what_a_forced_build_runs_there_again(@TempDir Path home) {
        RunShapes.Shape full = new RunShapes.Shape("", true, false);
        StepLedger.append(
                home,
                full,
                List.of(
                        new StepLedger.Sample("/ws/a", "compile-java", "SUCCESS", 1_300),
                        new StepLedger.Sample("/ws/a", "run-tests", "SUCCESS", 4_000),
                        new StepLedger.Sample("/ws/a", "package-jar", "SKIPPED", 30)));
        StepLedger.append(home, DEFAULT, List.of(new StepLedger.Sample("/ws/a", "run-tests", "SUCCESS", 2_000)));
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.stepsRun("/ws/a", full)).containsExactly("compile-java", "run-tests");
        assertThat(ledger.stepsRun("/ws/b", full)).isEmpty();
    }

    @Test
    void a_suite_reads_its_class_share_through_the_median_of_its_curves_of_the_nearest_kind(@TempDir Path home) {
        int[] a = {100, 200, 300, 400, 500, 600, 700, 800, 980};
        int[] b = {120, 210, 320, 410, 520, 610, 690, 740, 760};
        int[] c = {110, 190, 310, 420, 510, 590, 710, 760, 750};
        int[] test = {300, 450, 520, 600, 650, 700, 750, 800, 810};
        int[] integration = {270, 400, 500, 600, 640, 700, 760, 820, 870};
        List<StepLedger.Sample> suite = List.of(new StepLedger.Sample("/ws/engine", "run-tests", "SUCCESS", 40_000));
        Set<String> build = Set.of("/ws/engine");
        StepLedger.append(home, DEFAULT, build, Map.of("/ws/engine", a), suite);
        StepLedger.append(home, DEFAULT, Set.of("/ws/engine", "/ws/cli"), Map.of("/ws/engine", test), suite);
        StepLedger.append(home, DEFAULT, build, Map.of("/ws/engine", b), suite);
        StepLedger.append(home, DEFAULT, build, Map.of("/ws/engine", c), suite);
        RunShapes.Shape tier = new RunShapes.Shape("integration", false, false);
        StepLedger.append(home, tier, build, Map.of("/ws/engine", integration), suite);
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.curve("/ws/engine", DEFAULT, build))
                .as("the pointwise median of the work set's three, never below the point before")
                .containsExactly(110, 200, 310, 410, 510, 600, 700, 760, 760);
        assertThat(ledger.curve("/ws/engine", DEFAULT, Set.of("/ws/engine", "/ws/cli")))
                .as("the other work set's own, not mixed with the build's")
                .containsExactly(test);
        assertThat(ledger.curve("/ws/engine", tier, Set.of())).containsExactly(integration);
        assertThat(ledger.curve("/ws/engine", new RunShapes.Shape("", true, false), Set.of()))
                .as("another shape of the same profile: the median of its four, the middle two's mean")
                .containsExactly(115, 205, 315, 415, 515, 605, 705, 780, 785);
        assertThat(ledger.curve("/ws/cli", DEFAULT, Set.of())).isNull();
    }

    @Test
    void a_suite_is_priced_at_the_workers_its_newest_sample_ran_on(@TempDir Path home) {
        RunShapes.Shape forced = new RunShapes.Shape("", true, false);
        Set<String> all = Set.of("/ws/engine", "/ws/cli");
        // A first build gave the suite 2 JVMs; each forced build after it, 8.
        record Ran(long ms, int workers) {}
        for (Ran r : List.of(new Ran(72_800, 2), new Ran(38_000, 8), new Ran(36_700, 8))) {
            StepLedger.append(
                    home,
                    forced,
                    all,
                    Map.of(),
                    Map.of("/ws/engine", new long[] {r.workers(), 0, 0}),
                    List.of(new StepLedger.Sample("/ws/engine", "run-tests", "SUCCESS", r.ms())));
        }
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.workers("/ws/engine", "run-tests", "SUCCESS", forced, all))
                .isEqualTo(8);
        assertThat(ledger.price("/ws/engine", "run-tests", "SUCCESS", forced, all))
                .as("the first build's 73 s on 2 JVMs reads as 32 s on 8; the median is 36.7 s")
                .hasValue(36_700);
        assertThat(ledger.workers("/ws/engine", "compile-java", "SUCCESS", forced, all))
                .as("not a suite")
                .isZero();
    }

    @Test
    void an_even_count_of_samples_is_priced_at_the_newer_middle_one(@TempDir Path home) {
        Set<String> build = Set.of("/ws/engine");
        StepLedger.append(
                home, DEFAULT, build, List.of(new StepLedger.Sample("/ws/engine", "run-tests", "SUCCESS", 72_000)));
        StepLedger.append(
                home, DEFAULT, build, List.of(new StepLedger.Sample("/ws/engine", "run-tests", "SUCCESS", 37_000)));
        assertThat(StepLedger.load(home).price("/ws/engine", "run-tests", "SUCCESS", DEFAULT, build))
                .hasValue(37_000);
    }

    @Test
    void a_run_is_recorded_under_the_work_set_it_was_priced_with(@TempDir Path home) {
        Set<String> build = Set.of("/ws/engine");
        Set<String> test = Set.of("/ws/engine", "/ws/maven-spy");
        // A 30 ms no-op compile of /ws/web would have keyed the run by compiles under a set no
        // forecast prices with: the jk test run then priced its suite from the build's.
        List<StepLedger.Sample> testRun = List.of(
                new StepLedger.Sample("/ws/web", "compile-java", "SUCCESS", 30),
                new StepLedger.Sample("/ws/engine", "run-tests", "SUCCESS", 56_000));
        StepLedger.append(
                home, DEFAULT, build, List.of(new StepLedger.Sample("/ws/engine", "run-tests", "SUCCESS", 44_000)));
        StepLedger.append(home, DEFAULT, test, testRun);
        StepLedger.append(
                home, DEFAULT, build, List.of(new StepLedger.Sample("/ws/engine", "run-tests", "SUCCESS", 43_000)));
        StepLedger ledger = StepLedger.load(home);

        assertThat(ledger.price("/ws/engine", "run-tests", "SUCCESS", DEFAULT, test))
                .hasValue(56_000);
        assertThat(ledger.price("/ws/engine", "run-tests", "SUCCESS", DEFAULT, build))
                .as("the newer of the build's two")
                .hasValue(43_000);
    }
}
