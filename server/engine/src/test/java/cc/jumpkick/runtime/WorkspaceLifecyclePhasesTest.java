// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Characterizes phase handoffs without duplicating scheduler or lock integration coverage. */
class WorkspaceLifecyclePhasesTest {

    @TempDir
    Path tmp;

    @Test
    void empty_workspace_succeeds_and_finishes_after_graph_preflight() throws Exception {
        Path workspace = workspace(List.of());
        RecordingListener listener = new RecordingListener();

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(request(workspace), listener);

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(listener.events)
                .containsSubsequence(
                        "preflight:graph:0/0",
                        "preflight:graph:1/1",
                        "preflight:checking:0/0",
                        "preflight:checking:1/1",
                        "preflight:plan:0/1",
                        "preflight:plan:1/1",
                        "finish:0")
                .endsWith("finish:0");
        assertThat(listener.finished).containsExactly(result);
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
    void partial_restore_hands_only_misses_to_resource_planning() {
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");

        WorkspacePreflightPhase.Restore restore = WorkspacePreflightPhase.afterRestore(List.of(first, second, first));

        assertThat(restore.result()).isEmpty();
        assertThat(restore.dirty()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void restore_needed_modules_beside_dirty_work_are_built_in_graph_order_not_dropped() {
        Path core = Path.of("ws", "core").toAbsolutePath();
        Path app = Path.of("ws", "app").toAbsolutePath();
        List<BuildGraph.BuildUnit> units = List.of(unit(core), unit(app));

        // The post-failure memo shape once target/ is gone: app still dirty, core clean but
        // with no outputs on disk. The restore pass only runs for an all-clean graph.
        WorkspacePreflightPhase.Restore restore =
                WorkspacePreflightPhase.withoutRestorePass(units, Set.of(app), Set.of(core));
        assertThat(restore.result()).isEmpty();
        assertThat(restore.dirty()).containsExactly(core, app);

        // Nothing to restore: the dirty set passes through untouched.
        assertThat(WorkspacePreflightPhase.withoutRestorePass(units, Set.of(app), Set.of())
                        .dirty())
                .containsExactly(app);
    }

    private static BuildGraph.BuildUnit unit(Path dir) {
        return new BuildGraph.BuildUnit(dir, null, null, null);
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
