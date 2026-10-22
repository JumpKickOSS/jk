// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.PublishPlans;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * {@code jk publish}'s {@link Tail}: its refusals run beside the build, so a refused publish fails
 * before anything compiles, and the upload extends the build, so it ships what this run packaged.
 */
public final class PublishTail {

    private PublishTail() {}

    public static Tail of(PublishPlans.Request req) {
        return of(req, PublishPlans.workerJar());
    }

    static Tail of(PublishPlans.Request req, Path workerJar) {
        return (plan, in) -> {
            if (CompileSupport.coordinatorOnly(manifest(in.buildFile()), in.dir())) return;
            plan.stateKeys(
                            PublishPlans.PROJECT,
                            PublishPlans.JAR,
                            PublishPlans.FILES,
                            PublishPlans.WRITTEN,
                            PublishPlans.BUNDLE,
                            PublishPlans.DEPLOYMENT)
                    .addTask(PublishPlans.checkStep(in.dir(), req))
                    .extend(PublishPlans.publishStep(in.dir(), workerJar, req));
        };
    }

    private static JkBuild manifest(Path buildFile) {
        try {
            return JkBuildParser.parse(buildFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
