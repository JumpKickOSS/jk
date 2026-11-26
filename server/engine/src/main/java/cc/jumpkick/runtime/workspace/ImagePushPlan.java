// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlanKey;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * What a publish run's {@code image-plan} step leaves for the image lane to push once the run's
 * other lanes are done: the cache root the image worker writes under, the entrypoint's main class
 * (a JVM image in Jib mode), and for a node image the worker spec over the layers the step gathered.
 */
record ImagePushPlan(
        Path cache, @Nullable String main, @Nullable NodeSpec node) {

    /** The push a publish run's image lane makes for this module; absent from every other plan. */
    static final BuildPlanKey<ImagePushPlan> KEY = BuildPlanKey.scalar("image-push", ImagePushPlan.class);

    /** A node image's worker spec, given the base reference the push resolved. */
    @FunctionalInterface
    interface NodeSpec {
        SpecWriter of(String base) throws IOException;
    }
}
