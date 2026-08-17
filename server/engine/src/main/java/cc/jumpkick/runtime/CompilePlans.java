// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.BuildPlan;
import java.nio.file.Path;
import java.util.Set;

/** {@code jk compile}: {@link BuildPlanner} in compile-only mode (no resources/tests/package). */
public final class CompilePlans {

    private CompilePlans() {}

    /** Compile-only plan for {@code dir} (auto-locks like {@code jk build}). */
    public static BuildPlan compileBuildPlan(Path dir, Path cache, String profileName, boolean verbose) {
        return compileBuildPlan(dir, cache, profileName, verbose, null);
    }

    /** As above with request-level Inputs decoration (JK-2102). {@code null} = none. */
    public static BuildPlan compileBuildPlan(
            Path dir,
            Path cache,
            String profileName,
            boolean verbose,
            java.util.function.UnaryOperator<BuildPlanner.Inputs> decorate) {
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(dir);
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                dir,
                cache,
                buildFile,
                lockFile,
                lockFile.getParent(),
                1,
                0,
                profileName,
                null,
                /* skipTests */ true,
                verbose, /* testOnly */
                false, /* compileOnly */
                true,
                Set.of(),
                cc.jumpkick.config.SessionContext.current());
        if (decorate != null) inputs = decorate.apply(inputs);
        return BuildPlanner.coreBuilder(inputs).build();
    }
}
