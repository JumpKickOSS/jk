// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.RunNotices;
import cc.jumpkick.host.Errors;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.PlannerPlugin;
import cc.jumpkick.wire.runtime.ImagePush;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The image lane's workspace half. Before anything builds, a run that ships to the image lane alone
 * is refused when no selected module builds an image; beside other lanes such a lane pushes
 * nothing. Each image module's plan prepares its image beside the other lanes ({@link
 * ImagePlans#publish}); once every module is done and the release lane has published the release,
 * this pushes them, one module at a time. A dry run lists what it would push.
 */
final class ImageLane {

    private ImageLane() {}

    /** The publish arguments of a run that ships to the image lane, else {@code null}. */
    static TailArgs.@Nullable Publish args(WorkspaceRequest request) {
        if (request.target() != WorkspaceTarget.PUBLISH || request.spec() == null) return null;
        return request.spec().tail() instanceof TailArgs.Publish p && p.runs(PublishLane.IMAGE) ? p : null;
    }

    /**
     * The line a run that ships only to the image lane, and has no selected module that builds an
     * image, is refused with; empty otherwise.
     */
    static List<String> refusals(WorkspaceRequest request, Map<Path, JkBuild> units, Predicate<Path> selected) {
        TailArgs.Publish args = args(request);
        if (args == null || !args.lanes().equals(List.of(PublishLane.IMAGE))) return List.of();
        for (Map.Entry<Path, JkBuild> unit : units.entrySet()) {
            if (selected.test(unit.getKey()) && ImagePlans.images(unit.getValue(), unit.getKey())) return List.of();
        }
        return List.of("[image] registry: no module has a main class, Dockerfile or node server");
    }

    /** {@code result} with what the image lane pushed, or failed with the push that stopped it. */
    static WorkspaceResult complete(WorkspaceRunPhase.Run run, WorkspaceResult result) {
        TailArgs.Publish args = args(run.prepared().resources().request());
        if (args == null || args.stage() || !result.success() || result.cancelled()) return result;
        List<BuildPlan> plans = new ArrayList<>();
        for (ModulePlan module : run.prepared().plans().values()) {
            if (module.plan().get(ImagePushPlan.KEY).isPresent()) plans.add(module.plan());
        }
        return push(plans, args.dryRun(), result, ImagePlans::push);
    }

    /** One module's push; a test hands in one that reaches no registry. */
    interface Pusher {
        ImagePush push(BuildPlan plan, Consumer<String> said) throws IOException;
    }

    /** Push every image {@code plans} prepared, in order, stopping at the first that fails. */
    static WorkspaceResult push(List<BuildPlan> plans, boolean dryRun, WorkspaceResult result, Pusher pusher) {
        List<ImagePush> pushed = new ArrayList<>();
        for (BuildPlan plan : plans) {
            if (dryRun) {
                pushed.add(ImagePlans.planned(plan));
                continue;
            }
            String module = ImagePlans.moduleName(plan);
            try {
                pushed.add(pusher.push(plan, line -> say(module, line)));
            } catch (IOException | RuntimeException e) {
                return result.failed(Exit.FAILURE, module + " image: " + Errors.text(e), result.release())
                        .withImages(pushed);
            }
        }
        return result.withImages(pushed);
    }

    /** A worker diagnostic, said as a run notice: there is no step left to attach it to. */
    private static void say(String module, String line) {
        String message = PlannerPlugin.diagnosticText(line);
        RunNotices.warnOnce(
                "image-lane:" + module + "\0" + message, () -> module + " " + TaskNames.WRITE_IMAGE + ": " + message);
    }
}
