// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.ModuleJar;
import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.base.PublishPlans;
import cc.jumpkick.runtime.base.ReleasePlans;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.TailArgs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk publish}'s {@link Tail}, one part per lane the run ships to. The library lane's
 * refusals run beside the build, so a refused publish fails before anything compiles; each upload
 * extends the build, so it ships what this run packaged. The image lane's plan comes first, so an
 * image that cannot be pushed stops the module before it uploads anything; its push waits for the
 * whole run ({@link ImageLane}). The release lane's upload comes next: a module's library goes out
 * only after its release files are in place.
 */
public final class PublishTail {

    private PublishTail() {}

    /**
     * The tail of the module {@code unit} describes, as the workspace sees it: the image lane reads
     * the {@code [image]} table a member inherits from the root.
     */
    public static Tail of(TailArgs.Publish args, JkBuild unit) {
        PublishPlans.Request req = request(args);
        return (plan, in) -> {
            if (args.runs(PublishLane.IMAGE) && ImagePlans.images(unit, in.dir())) {
                if (NodeImagePlans.isNodeModule(in.buildFile(), in.dir()))
                    NodeImagePlans.publish(plan, in, in.dir(), in.cache());
                else ImagePlans.publish(plan, in);
            }
            JkBuild manifest = manifest(in.buildFile());
            if (args.runs(PublishLane.RELEASE) && !ModuleJar.skipsRelease(manifest)) {
                var files = ModuleJar.releaseFiles(manifest, in.dir());
                if (!files.isEmpty()) {
                    plan.stateKeys(ReleasePlans.FILES, ReleasePlans.EXPECTED)
                            .extend(ReleasePlans.uploadStep(in.dir(), args, files));
                }
            }
            if (!args.runs(PublishLane.LIBRARY) || ModuleJar.shippedStep(manifest, in.dir()) == null) return;
            plan.stateKeys(
                            PublishPlans.PROJECT,
                            PublishPlans.JAR,
                            PublishPlans.FILES,
                            PublishPlans.EXISTING,
                            PublishPlans.WRITTEN,
                            PublishPlans.BUNDLE,
                            PublishPlans.BUNDLE_BYTES,
                            PublishPlans.DEPLOYMENT,
                            PublishPlans.DESTINATION,
                            PublishPlans.DRY_RUN)
                    .addTask(PublishPlans.checkStep(in.dir(), req))
                    .extend(PublishPlans.publishStep(in.dir(), req));
        };
    }

    /** The publish worker's request from the arguments the client resolved. */
    static PublishPlans.Request request(TailArgs.Publish a) {
        return new PublishPlans.Request(
                a.repoUrl(),
                a.allowSnapshot(),
                a.dryRun(),
                a.keyFile(),
                a.gpgPassphrase(),
                a.credential(),
                a.central(),
                holdsForRelease(a));
    }

    /**
     * True when a Central upload stops at {@code VALIDATED} for the release lane to publish it once
     * the release is out: the final run of a project that ships both.
     */
    static boolean holdsForRelease(TailArgs.Publish a) {
        return a.central() && a.runs(PublishLane.RELEASE) && !a.stage() && !a.dryRun();
    }

    /** What a publish pass sent, from its plan's state; null for a plan that published nothing. */
    public static ModuleOutcome.@Nullable Published outcomeOf(BuildPlan plan) {
        String destination = plan.get(PublishPlans.DESTINATION).orElse(null);
        if (destination == null) return null;
        PublishPlans.Deployment deployment = plan.get(PublishPlans.DEPLOYMENT).orElse(null);
        return new ModuleOutcome.Published(
                destination,
                plan.get(PublishPlans.DRY_RUN).orElse(false),
                Math.max(plan.get(PublishPlans.FILES).orElse(0), 0),
                Math.max(plan.get(PublishPlans.EXISTING).orElse(0), 0),
                plan.get(PublishPlans.WRITTEN).orElse(List.of()),
                plan.get(PublishPlans.BUNDLE).orElse(List.of()),
                plan.get(PublishPlans.BUNDLE_BYTES).orElse(0L),
                deployment == null ? null : deployment.id(),
                deployment == null ? null : deployment.state(),
                deployment == null ? List.of() : deployment.errors());
    }

    private static JkBuild manifest(Path buildFile) {
        try {
            return JkBuildParser.parse(buildFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
