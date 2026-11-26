// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release-lane publish ships each {@code [native]} module's executable, so its tail requires the
 * native image: planned for a final, stage or dry run whatever {@code enabled} says, never for
 * {@code jk build}, a library-only publish, {@code enabled = false} or a host the release does not
 * list, and planned once when {@code enabled = "always"} already builds it.
 */
class ReleaseNativeTailTest {

    @TempDir
    Path tmp;

    private BuildGraph.BuildUnit app(String nativeTable, String release) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/ex"));
        Files.writeString(
                src.resolve("App.java"), "package ex; public class App { public static void main(String[] a) {} }\n");
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "app"
                version = "1.0.0"
                java = 25

                [application]
                main = "ex.App"

                %s

                [publish]
                release = "s3://releases/app/"

                %s
                """.formatted(nativeTable, release));
        return new BuildGraph.BuildUnit(
                tmp, JkBuildParser.parse(tmp.resolve("jk.toml")), "ex:app", BuildGraph.Origin.MODULE);
    }

    private BuildGraph.BuildUnit app(String nativeTable) throws Exception {
        return app(nativeTable, "");
    }

    private static TailArgs.Publish publish(List<PublishLane> lanes, boolean stage, boolean dryRun) {
        return new TailArgs.Publish(
                null, false, dryRun, null, null, RepoCredential.ANONYMOUS, false, lanes, stage, null, false);
    }

    private static TailArgs.Publish release() {
        return publish(List.of(PublishLane.LIBRARY, PublishLane.RELEASE), false, false);
    }

    private ModulePipeline.Planned planned(BuildGraph.BuildUnit unit, WorkspaceSpec spec) throws Exception {
        Path graal = Files.createDirectories(tmp.resolve("graal"));
        WorkspaceRequest request = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, false, false)
                .withSpec(spec.withGraalByDir(Map.of(tmp, graal)));
        return ModulePipeline.planned(unit, request, Set.of(tmp), false, Set.of(), Set.of(), SiblingArtifacts.NONE);
    }

    private ModulePipeline.Planned publishing(BuildGraph.BuildUnit unit, TailArgs.Publish args) throws Exception {
        return planned(unit, WorkspaceSpec.publish(Set.of(), args));
    }

    private static long natives(BuildPlan plan) {
        return plan.steps().stream()
                .filter(t -> t.name().equals(TaskNames.NATIVE_IMAGE))
                .count();
    }

    /** Every step {@code step} requires, directly or through another. */
    private static Set<String> upstream(BuildPlan plan, String step) {
        Map<String, Task> byName = plan.steps().stream().collect(Collectors.toMap(Task::name, Function.identity()));
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(step));
        while (!todo.isEmpty()) {
            Task task = byName.get(todo.pop());
            if (task == null) continue;
            for (String name : task.requires()) {
                if (seen.add(name)) todo.add(name);
            }
        }
        return seen;
    }

    @Test
    void the_build_leaves_an_on_demand_native_image_alone() throws Exception {
        assertThat(natives(planned(app("[native]"), WorkspaceSpec.of(WorkspaceTarget.PACKAGE))
                        .plan()))
                .isZero();
    }

    @Test
    void a_release_lane_publish_requires_the_native_image_before_its_upload() throws Exception {
        BuildGraph.BuildUnit unit = app("[native]");
        for (TailArgs.Publish args : List.of(
                release(),
                publish(List.of(PublishLane.RELEASE), true, false),
                publish(List.of(PublishLane.RELEASE), false, true))) {
            ModulePipeline.Planned planned = publishing(unit, args);
            assertThat(natives(planned.plan()))
                    .as("stage %s, dry run %s", args.stage(), args.dryRun())
                    .isOne();
            assertThat(planned.tailSteps()).contains(TaskNames.NATIVE_IMAGE, TaskNames.RELEASE_UPLOAD);
            assertThat(upstream(planned.plan(), TaskNames.RELEASE_UPLOAD))
                    .contains(TaskNames.NATIVE_IMAGE, TaskNames.RUN_TESTS);
        }
    }

    @Test
    void a_publish_without_the_release_lane_builds_no_native_image() throws Exception {
        BuildGraph.BuildUnit unit = app("[native]");
        assertThat(natives(publishing(unit, publish(List.of(PublishLane.LIBRARY), false, false))
                        .plan()))
                .isZero();
        assertThat(natives(publishing(unit, publish(List.of(PublishLane.IMAGE), false, false))
                        .plan()))
                .isZero();
    }

    @Test
    void an_always_native_module_builds_its_image_once_in_the_lifecycle() throws Exception {
        BuildGraph.BuildUnit unit = app("[native]\nenabled = \"always\"");
        assertThat(natives(
                        planned(unit, WorkspaceSpec.of(WorkspaceTarget.PACKAGE)).plan()))
                .isOne();
        ModulePipeline.Planned planned = publishing(unit, release());
        assertThat(natives(planned.plan())).isOne();
        assertThat(planned.tailSteps()).doesNotContain(TaskNames.NATIVE_IMAGE);
        assertThat(upstream(planned.plan(), TaskNames.RELEASE_UPLOAD)).contains(TaskNames.NATIVE_IMAGE);
    }

    @Test
    void a_disabled_native_image_is_never_built_for_a_release() throws Exception {
        assertThat(natives(
                        publishing(app("[native]\nenabled = false"), release()).plan()))
                .isZero();
    }

    @Test
    void a_host_the_release_does_not_list_builds_no_native_image() throws Exception {
        String other = ReleasePlatform.current().equals("linux-x86_64") ? "windows-x86_64" : "linux-x86_64";
        BuildGraph.BuildUnit unit = app("[native]", "[release]\nplatforms = [\"" + other + "\"]");
        assertThat(natives(publishing(unit, release()).plan())).isZero();
    }
}
