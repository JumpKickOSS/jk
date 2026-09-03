// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceExecute#requestKnobs} is the one spelling of the request-knob set. Both roads to
 * a module's {@link BuildPlanner.Inputs} — the decorate operator the NATIVE/IMAGE/COMPILE terminal
 * branches take, and {@link WorkspaceExecute#moduleInputs} on the PACKAGE/INSTALL path — must
 * deliver the request's knobs verbatim; a knob dropped from the owner is a module silently planned
 * against a different manifest than its siblings.
 */
class WorkspaceRequestKnobsTest {

    @TempDir
    Path tmp;

    @Test
    void the_decorate_branch_and_the_package_branch_carry_the_request_knobs_verbatim() throws Exception {
        Path mod = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(mod.resolve("jk.toml"), """
                [project]
                name = "app"
                group = "ex"
                version = "1.0"
                """);
        Set<Path> dirs = Set.of(mod);
        WorkspaceRequest req = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 3, "fast", false, false, 0, null, true, true)
                .withVariant("release", Map.of("SECRET_HOME", "v"))
                .withEphemeralActions(true);

        // The operator handed to the NATIVE/IMAGE/COMPILE terminal branches, over neutral inputs…
        BuildPlanner.Inputs decorated = WorkspacePreparePhase.requestKnobs(req, dirs)
                .apply(TaskForecaster.inputsFor(mod, req.cache(), 1, null, null, false, false, Set.of(), false));
        // …and the PACKAGE/INSTALL path.
        BuildPlanner.Inputs packaged = WorkspacePreparePhase.moduleInputs(mod, req, dirs, false);

        for (BuildPlanner.Inputs in : List.of(decorated, packaged)) {
            assertThat(in.workerCount()).isEqualTo(3);
            assertThat(in.profileName()).isEqualTo("fast");
            assertThat(in.projectModules()).isEqualTo(dirs);
            assertThat(in.variant()).isEqualTo("release");
            assertThat(in.clientEnv()).isEqualTo(Map.of("SECRET_HOME", "v"));
            assertThat(in.ephemeralActions()).isTrue();
        }
    }

    /**
     * {@code requestKnobs} / {@code moduleInputs} are a pass-through: whatever workers the request
     * carries is what the module plan gets, including {@code 0}. Resolution of {@code 0 = auto}
     * happens once per build in {@code buildWorkspace}, where the graph width is known — not here.
     *
     * <p>This test used to assert that both paths turned {@code 0} into {@code 1}, and so pinned a
     * defect rather than a contract: {@code 1} does not read as "auto" downstream, it reads as an
     * explicit request for a single JVM ({@link TestWorkers#resolve}), so a module with no
     * {@code [test] workers} pin ran its whole suite serially no matter how much machine was idle.
     */
    @Test
    void the_worker_request_is_passed_through_verbatim_including_auto() throws Exception {
        Path mod = Files.createDirectories(tmp.resolve("app"));
        Set<Path> dirs = Set.of(mod);
        WorkspaceRequest req =
                new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, true, true);

        BuildPlanner.Inputs decorated = WorkspacePreparePhase.requestKnobs(req, dirs)
                .apply(TaskForecaster.inputsFor(mod, req.cache(), 1, null, null, false, false, Set.of(), false));
        BuildPlanner.Inputs packaged = WorkspacePreparePhase.moduleInputs(mod, req, dirs, false);

        assertThat(decorated.workerCount()).isZero();
        assertThat(packaged.workerCount()).isZero();

        // An explicit -w1 is also carried verbatim, and still means serial downstream.
        WorkspaceRequest serial =
                new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 1, null, false, false, 0, null, true, true);
        assertThat(WorkspacePreparePhase.moduleInputs(mod, serial, dirs, false).workerCount())
                .isEqualTo(1);
        assertThat(TestWorkers.resolve(1, 40, 12)).isEqualTo(1);
    }

    /**
     * Auto is a <em>share</em> of the machine, not "as many as this module could use", because jk
     * takes its parallelism from modules first and the two layers draw on one machine.
     *
     * <p>Measured on the 30-module dogfood build: giving every module {@code min(jobs, classCount)}
     * — each module sharding as if it were alone — moved the wall from 73 s to 103 s, and the
     * whole-build curve is monotone in {@code -w} (73 s at {@code -w1}, 77 s at {@code -w2}, 81 s at
     * {@code -w4}). Dividing by width reproduces the fast end for a wide build while still letting a
     * single dirty module shard, which is the case within-module sharding was built for and where it
     * was measured to pay (5.8 s → 2.4 s at {@code -w4}).
     */
    @Test
    void auto_workers_are_the_machine_divided_by_how_wide_the_build_can_get() {
        WorkspaceRequest auto =
                new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, true, true);

        // Wide dogfood-shaped graph on 24 threads: modules already fill the machine.
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(auto, 13, 24)).isEqualTo(1);
        // One module selected: nothing else is running, so shard as wide as the machine.
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(auto, 1, 24)).isEqualTo(24);
        // In between.
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(auto, 4, 24)).isEqualTo(6);
        // Degenerate inputs must not produce 0 workers or divide by zero.
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(auto, 0, 0)).isEqualTo(1);
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(auto, 99, 24)).isEqualTo(1);

        // An explicit -w N is never rewritten, at any width.
        WorkspaceRequest pinned =
                new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 8, null, false, false, 0, null, true, true);
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(pinned, 13, 24)).isEqualTo(8);
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(pinned, 1, 24)).isEqualTo(8);
    }

    /**
     * `jobs` is documented as the concurrent module/worker budget, so `-j 4` on a 24-thread host
     * caps the whole build at four JVMs: width and per-module share come from the same number. The
     * request carries the client-resolved budget as its module-concurrency cap; the ETA derives the
     * share through the same function, so explain and the countdown cannot price different builds.
     */
    @Test
    void the_jobs_budget_not_the_core_count_is_what_the_share_divides() {
        WorkspaceRequest jobs4 =
                new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, false, false, 4, null, true, true);
        int budget = TestWorkers.jobsBudget(jobs4.maxModuleConcurrency());
        assertThat(budget).isEqualTo(4);
        // One dirty module: four runners, not twenty-four.
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(jobs4, 1, budget)).isEqualTo(4);
        // Four independent dirty modules under -j 4: one runner each.
        assertThat(WorkspaceResourcePhase.resolveAutoWorkers(jobs4, 4, budget)).isEqualTo(1);
        // The ETA's derivation is the executor's.
        assertThat(TestWorkers.autoShare(budget, 1)).isEqualTo(4);
        assertThat(TestWorkers.autoShare(24, 4)).isEqualTo(6);
        assertThat(TestWorkers.autoShare(0, 0)).isEqualTo(1);
        // No cap on the request: the engine's own budget, which defaults to every core.
        assertThat(TestWorkers.jobsBudget(0)).isEqualTo(TestWorkers.effectiveJobs());
    }

    /**
     * The memory plan multiplies the resolved per-module workers by the graph width, so the share
     * has to keep that product near the core count rather than blowing past it — that product is
     * what sizes per-JVM heaps and the {@code PluginSlots} permit count.
     */
    @Test
    void the_resolved_share_keeps_the_memory_plan_within_the_core_count() {
        WorkspaceRequest auto =
                new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, true, true);
        for (int width : new int[] {1, 2, 4, 8, 13, 24, 30}) {
            int w = WorkspaceResourcePhase.resolveAutoWorkers(auto, width, 24);
            assertThat(HeapPlan.requestedJvms(width, w, true, 24))
                    .as("width %d x %d workers must stay within the cap", width, w)
                    .isLessThanOrEqualTo(24);
        }
    }
}
