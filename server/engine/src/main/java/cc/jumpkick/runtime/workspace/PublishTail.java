// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.base.PublishPlans;
import java.nio.file.Path;

/**
 * {@code jk publish}'s {@link Tail}: its refusals run beside the build, so a refused publish fails
 * before anything compiles, and the upload extends the build, so it ships what this run packaged.
 */
public final class PublishTail {

    private PublishTail() {}

    public static Tail of(PublishPlans.Request req) {
        Path workerJar = PublishPlans.workerJar();
        return (plan, in) -> plan.stateKeys(
                        PublishPlans.PROJECT,
                        PublishPlans.JAR,
                        PublishPlans.FILES,
                        PublishPlans.WRITTEN,
                        PublishPlans.BUNDLE,
                        PublishPlans.DEPLOYMENT)
                .addTask(PublishPlans.checkStep(in.dir(), req))
                .extend(PublishPlans.publishStep(in.dir(), workerJar, req));
    }
}
