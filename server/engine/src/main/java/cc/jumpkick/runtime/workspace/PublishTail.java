// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.ModuleJar;
import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.base.PublishPlans;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.TailArgs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk publish}'s {@link Tail}: its refusals run beside the build, so a refused publish fails
 * before anything compiles, and the upload extends the build, so it ships what this run packaged.
 */
public final class PublishTail {

    private PublishTail() {}

    public static Tail of(TailArgs.Publish args) {
        PublishPlans.Request req = request(args);
        return (plan, in) -> {
            if (ModuleJar.packagingStep(manifest(in.buildFile()), in.dir()) == null) return;
            plan.stateKeys(
                            PublishPlans.PROJECT,
                            PublishPlans.JAR,
                            PublishPlans.FILES,
                            PublishPlans.EXISTING,
                            PublishPlans.WRITTEN,
                            PublishPlans.BUNDLE,
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
                a.region(),
                a.endpoint(),
                a.jar(),
                a.allowSnapshot(),
                a.dryRun(),
                a.keyFile(),
                a.gpgPassphrase(),
                a.sigstore(),
                a.slsa(),
                a.sbom(),
                a.credential(),
                a.central(),
                a.publishingType());
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
