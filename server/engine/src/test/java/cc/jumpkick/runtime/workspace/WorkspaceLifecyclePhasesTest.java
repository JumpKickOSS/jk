// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Characterizes phase handoffs without duplicating scheduler or lock integration coverage. */
class WorkspaceLifecyclePhasesTest {

    @TempDir
    Path tmp;

    /** A workspace with nothing to build is not a passing build: it fails after the graph preflight, once. */
    @Test
    void empty_workspace_built_nothing_and_finishes_after_graph_preflight() throws Exception {
        Path workspace = workspace(List.of());
        RecordingListener listener = new RecordingListener();

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(request(workspace), listener);

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.errors()).containsExactly("built nothing: the workspace declares no modules");
        assertThat(listener.events).containsExactly("preflight:graph:0/0", "preflight:graph:1/1", "finish:2");
        assertThat(listener.finished).containsExactly(result);
    }

    @Test
    void modules_without_sources_are_built_nothing_and_one_source_tree_lifts_the_verdict() throws Exception {
        Path workspace = workspace(List.of("bom", "api"));
        RecordingListener listener = new RecordingListener();

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(request(workspace), listener);

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.errors())
                .containsExactly("built nothing: none of the 2 modules has sources (example:bom, example:api)");
        assertThat(listener.events).endsWith("finish:2");

        Files.createDirectories(workspace.resolve("api/src/main/java"));
        var root = JkBuildParser.parse(Files.readString(workspace.resolve("jk.toml")));
        assertThat(NothingToBuild.verdict(BuildGraph.resolve(workspace, root).topoOrder(), root))
                .as("one module with a source tree is enough")
                .isNull();
    }

    @Test
    void unmatched_selection_fails_before_forecast_and_finishes_once() throws Exception {
        Path workspace = workspace(List.of("app"));
        Path missing = workspace.resolve("missing");
        WorkspaceRequest request = request(workspace).withSpec(WorkspaceSpec.compile(Set.of(missing)));
        RecordingListener listener = new RecordingListener();

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(request, listener);

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.errors()).singleElement().asString().contains("selection matched no workspace module");
        assertThat(listener.events).containsExactly("preflight:graph:0/0", "preflight:graph:1/1", "finish:2");
        assertThat(listener.finished).hasSize(1);
    }

    @Test
    void restore_needed_modules_are_scheduled_beside_dirty_work_in_graph_order() {
        Path core = Path.of("ws", "core").toAbsolutePath();
        Path app = Path.of("ws", "app").toAbsolutePath();
        List<BuildGraph.BuildUnit> units = List.of(unit(core), unit(app));

        // The post-failure memo shape once target/ is gone: app still dirty, core clean but
        // with no outputs on disk.
        assertThat(WorkspacePreflightPhase.scheduled(units, Set.of(app), Set.of(core)))
                .containsExactly(core, app);

        // An all-clean graph with missing outputs schedules exactly those modules: their plans
        // restore by key, there is no pointer-driven restore beside the build.
        assertThat(WorkspacePreflightPhase.scheduled(units, Set.of(), Set.of(core, app)))
                .containsExactly(core, app);

        // Nothing to restore: the dirty set passes through untouched.
        assertThat(WorkspacePreflightPhase.scheduled(units, Set.of(app), Set.of()))
                .containsExactly(app);
    }

    // The nulls are deliberate: a unit's identity is its dir, and the phase under test reads nothing else.
    @SuppressWarnings("NullAway")
    private static BuildGraph.BuildUnit unit(Path dir) {
        return new BuildGraph.BuildUnit(dir, null, null, null);
    }

    /**
     * A cancel during a parallel prepare stops tasks before they run; the plans that never came back
     * are left out of the ordered map rather than carried as nulls into onPlan.
     */
    @Test
    void units_left_unprepared_by_a_cancel_are_omitted_not_nulled() {
        Path a = Path.of("ws", "a").toAbsolutePath();
        Path b = Path.of("ws", "b").toAbsolutePath();
        ModulePlan planA = new ModulePlan(a, "g:a", BuildPlan.builder("a").build(), 1, false, Path.of("cache"));

        Map<Path, ModulePlan> ordered =
                WorkspacePreparePhase.orderLikeUnits(List.of(unit(a), unit(b)), Map.of(a, planA));
        assertThat(ordered).containsExactly(Map.entry(a, planA));
        assertThat(WorkspacePreparePhase.orderLikeUnits(List.of(unit(a), unit(b)), Map.of()))
                .isEmpty();
    }

    @Test
    void final_aggregation_keeps_partial_completion_and_first_failure() {
        ModuleOutcome complete = outcome("complete", true, 0);
        ModuleOutcome firstFailure = outcome("first", false, 4);
        ModuleOutcome laterFailure = outcome("later", false, 7);

        WorkspaceFinalPhase.Decision decision =
                WorkspaceFinalPhase.aggregate(List.of(complete, firstFailure, laterFailure), Optional.empty(), false);

        assertThat(decision.result().modules()).containsExactly(complete, firstFailure, laterFailure);
        assertThat(decision.result().exitCode()).isEqualTo(4);
        assertThat(decision.result().success()).isFalse();
        assertThat(decision.learn()).isFalse();
    }

    @Test
    void cancellation_precedes_module_failure_and_never_learns() {
        ModuleOutcome failure = outcome("failed", false, 4);

        WorkspaceFinalPhase.Decision decision =
                WorkspaceFinalPhase.aggregate(List.of(failure), Optional.of(failure), true);

        assertThat(decision.result().exitCode()).isEqualTo(1);
        assertThat(decision.result().cancelled()).isTrue();
        assertThat(decision.learn()).isFalse();
    }

    @Test
    void only_full_success_is_eligible_for_learning() {
        WorkspaceFinalPhase.Decision decision =
                WorkspaceFinalPhase.aggregate(List.of(outcome("ok", true, 0)), Optional.empty(), false);

        assertThat(decision.result().success()).isTrue();
        assertThat(decision.learn()).isTrue();
    }

    @Test
    void clean_memo_is_package_only_and_requires_a_whole_graph_build() {
        WorkspaceRequest packageRequest = request(tmp);
        WorkspaceRequest hinted = new WorkspaceRequest(
                tmp, tmp.resolve("cache"), null, 0, null, false, false, 1, Set.of(tmp), false, false);

        assertThat(WorkspaceFinalPhase.shouldStoreCleanMemo(packageRequest)).isTrue();
        assertThat(WorkspaceFinalPhase.shouldStoreCleanMemo(
                        packageRequest.withSpec(WorkspaceSpec.of(WorkspaceTarget.IMAGE))))
                .isFalse();
        assertThat(WorkspaceFinalPhase.shouldStoreCleanMemo(packageRequest.withTestOnly(true)))
                .isFalse();
        assertThat(WorkspaceFinalPhase.shouldStoreCleanMemo(hinted)).isFalse();
    }

    @Test
    void run_phase_owns_fail_fast_and_keep_going_policy() {
        ModuleOutcome failure = outcome("failed", false, 4);

        assertThat(WorkspaceRunPhase.stoppingFailure(false, failure)).isSameAs(failure);
        assertThat(WorkspaceRunPhase.stoppingFailure(true, failure)).isNull();
        assertThat(WorkspaceRunPhase.stoppingFailure(false, outcome("ok", true, 0)))
                .isNull();
    }

    private WorkspaceRequest request(Path workspace) {
        return new WorkspaceRequest(
                workspace, tmp.resolve("cache"), null, 0, null, false, false, 1, null, false, false);
    }

    private Path workspace(List<String> modules) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("workspace-" + modules.size()));
        String members = modules.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));
        Files.writeString(workspace.resolve("jk.toml"), """
                group = "example"
                name = "root"
                version = "1"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(members));
        for (String name : modules) {
            Path module = Files.createDirectories(workspace.resolve(name));
            Files.writeString(module.resolve("jk.toml"), """
                    group = "example"
                    name = "%s"
                    version = "1"
                    java = 25
                    """.formatted(name));
        }
        return workspace;
    }

    private static ModuleOutcome outcome(String name, boolean success, int exitCode) {
        return new ModuleOutcome("example:" + name, Path.of(name), success, exitCode, 10);
    }

    private static final class RecordingListener implements WorkspaceBuildListener {
        private final List<String> events = new ArrayList<>();
        private final List<WorkspaceResult> finished = new ArrayList<>();

        @Override
        public void onPreflight(String stage, int done, int total, String label) {
            events.add("preflight:" + stage + ":" + done + "/" + total);
        }

        @Override
        public void onWorkspaceFinish(WorkspaceResult result) {
            events.add("finish:" + result.exitCode());
            finished.add(result);
        }
    }
}
