// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.task.ModuleIdentity;
import cc.jumpkick.wire.runtime.ImagePush;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The image lane's workspace half: which modules push an image, when a run that pushes images is
 * refused, and that the pushes go out in order after the build, or are only listed on a dry run.
 */
class ImageLaneTest {

    @TempDir
    Path tmp;

    @Test
    void a_module_pushes_an_image_when_a_registry_reaches_it_and_it_has_something_to_run() throws Exception {
        assertThat(ImagePlans.images(manifest("app", "[application]\nmain = \"ex.App\"\n"), tmp.resolve("app")))
                .isTrue();
        assertThat(ImagePlans.images(manifest("lib", ""), tmp.resolve("lib")))
                .as("a library has nothing to run")
                .isFalse();
        assertThat(ImagePlans.images(
                        manifest("tool", "[application]\nmain = \"ex.Tool\"\n\n[publish]\nskip = true\n"),
                        tmp.resolve("tool")))
                .as("[publish] skip keeps the image back too")
                .isFalse();
    }

    @Test
    void an_image_lane_alone_with_nothing_to_push_is_refused_and_beside_other_lanes_is_not() throws Exception {
        Map<Path, JkBuild> units = Map.of(tmp.resolve("lib"), manifest("lib", ""));
        assertThat(ImageLane.refusals(request(List.of(PublishLane.IMAGE)), units, d -> true))
                .containsExactly("[image] registry: no module has a main class, Dockerfile or node server");
        assertThat(ImageLane.refusals(request(List.of(PublishLane.LIBRARY, PublishLane.IMAGE)), units, d -> true))
                .isEmpty();
        Map<Path, JkBuild> app = Map.of(tmp.resolve("app"), manifest("app", "[application]\nmain = \"ex.App\"\n"));
        assertThat(ImageLane.refusals(request(List.of(PublishLane.IMAGE)), app, d -> true))
                .isEmpty();
    }

    @Test
    void a_dry_run_lists_each_reference_and_pushes_nothing() throws Exception {
        List<BuildPlan> plans = List.of(plan("app"), plan("web"));
        WorkspaceResult result = ImageLane.push(plans, true, ok(), (plan, said) -> {
            throw new AssertionError("a dry run pushes nothing");
        });
        assertThat(result.success()).isTrue();
        assertThat(result.images())
                .as("the module version is the tag; outside a release session it is the snapshot")
                .containsExactly(
                        new ImagePush("app", "reg.example/app:1.0.0-SNAPSHOT", "", true),
                        new ImagePush("web", "reg.example/web:1.0.0-SNAPSHOT", "", true));
    }

    @Test
    void the_images_go_out_in_order_and_the_first_that_fails_stops_the_lane() throws Exception {
        List<BuildPlan> plans = List.of(plan("app"), plan("web"), plan("api"));
        List<String> tried = new ArrayList<>();
        WorkspaceResult result = ImageLane.push(plans, false, ok(), (plan, said) -> {
            String name = plan.get(BuildPlanner.MODULE)
                    .orElseThrow()
                    .project()
                    .project()
                    .name();
            tried.add(name);
            if (name.equals("web")) throw new IOException("denied: requested access to the resource is denied");
            return new ImagePush(name, "reg.example/" + name + ":1.0.0", "sha256:ab12", false);
        });
        assertThat(tried).containsExactly("app", "web");
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsExactly("web image: denied: requested access to the resource is denied");
        assertThat(result.images())
                .as("what went up before the failure is still reported")
                .containsExactly(new ImagePush("app", "reg.example/app:1.0.0", "sha256:ab12", false));
    }

    private static WorkspaceResult ok() {
        return new WorkspaceResult(true, 0, List.of(), List.of());
    }

    private WorkspaceRequest request(List<PublishLane> lanes) {
        TailArgs.Publish args = new TailArgs.Publish(
                null, false, true, null, null, RepoCredential.ANONYMOUS, false, lanes, false, null, false);
        return new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, false, false, 0, null, false, false)
                .withSpec(WorkspaceSpec.publish(Set.of(), args));
    }

    /** {@code name}'s manifest, with {@code [image] registry} and {@code extra} appended. */
    private JkBuild manifest(String name, String extra) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "%s"
                version = "1.0.0"
                java = 25

                [image]
                registry = "reg.example"

                %s
                """.formatted(name, extra));
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    /** A plan whose image-plan step left what a push of {@code name} reads. */
    private BuildPlan plan(String name) throws Exception {
        JkBuild project = manifest(name, "[application]\nmain = \"ex.Main\"\n");
        Path dir = tmp.resolve(name);
        ImageConfig config = new ImageConfig(
                "base:25",
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                "reg.example",
                null,
                List.of(),
                null,
                null,
                null,
                false,
                true,
                false);
        BuildPlan plan = BuildPlan.builder(name)
                .stateKeys(BuildPlanner.MODULE, ImagePlans.CONFIG, ImagePushPlan.KEY)
                .addTask(Task.builder("image-plan")
                        .forecast(StepForecast.RIDES)
                        .ticks(1)
                        .execute(ctx -> {
                            ctx.put(BuildPlanner.MODULE, new ModuleIdentity(project, BuildLayout.of(dir, project)));
                            ctx.put(ImagePlans.CONFIG, config);
                            ctx.put(ImagePushPlan.KEY, new ImagePushPlan(tmp.resolve("cache"), "ex.Main", null));
                        })
                        .build())
                .build();
        assertThat(plan.run().success()).isTrue();
        return plan;
    }
}
