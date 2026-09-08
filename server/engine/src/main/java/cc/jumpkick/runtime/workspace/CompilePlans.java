// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.BuildPlanner;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/** {@code jk compile}: {@link BuildPlanner} in compile-only mode (no resources/tests/package). */
public final class CompilePlans {

    private CompilePlans() {}

    /** Compile-only plan for {@code dir} (auto-locks like {@code jk build}). */
    public static BuildPlan compileBuildPlan(Path dir, Path cache, @Nullable String profileName, boolean verbose) {
        return compileBuildPlan(dir, cache, profileName, verbose, null);
    }

    /** As above with request-level Inputs decoration. {@code null} = none. */
    public static BuildPlan compileBuildPlan(
            Path dir,
            Path cache,
            @Nullable String profileName,
            boolean verbose,
            @Nullable UnaryOperator<BuildPlanner.Inputs> decorate) {
        Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
        Path lockFile = LockPaths.lockFile(dir);
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                dir,
                cache,
                buildFile,
                lockFile,
                Objects.requireNonNull(lockFile.getParent(), "lock dir"),
                1,
                0,
                profileName,
                null,
                /* skipTests */ true,
                verbose, /* testOnly */
                false, /* compileOnly */
                true,
                Set.of(),
                SessionContext.current());
        if (decorate != null) inputs = decorate.apply(inputs);
        return BuildPlanner.coreBuilder(inputs).build();
    }
}
