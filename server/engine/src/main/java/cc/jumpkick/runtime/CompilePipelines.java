// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.Pipeline;
import java.nio.file.Path;

/** {@code jk compile}: {@link BuildPipelines} in compile-only mode (no resources/tests/package). */
public final class CompilePipelines {

    private CompilePipelines() {}

    /** Compile-only pipeline for {@code dir} (auto-locks like {@code jk build}). */
    public static Pipeline compilePipeline(Path dir, Path cache, String profileName, boolean verbose) {
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        BuildPipelines.Inputs inputs = new BuildPipelines.Inputs(
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
                java.util.Set.of(),
                cc.jumpkick.config.SessionContext.current());
        return BuildPipelines.coreBuilder(inputs).build();
    }
}
