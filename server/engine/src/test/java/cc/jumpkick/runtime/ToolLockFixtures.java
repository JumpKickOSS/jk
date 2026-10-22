// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.runtime.base.LockMode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A hand-made lock for a test that plans a build directly, with the {@code [[tool]]} rows {@code
 * jk lock} would write for the module's plugin tools: a build fetches no tool the lock does not pin.
 */
final class ToolLockFixtures {

    private ToolLockFixtures() {}

    /** {@code lock} with the module's tool rows pinned, written to {@code moduleDir/jk-lock.toml}. */
    static Lockfile writeWithTools(Path moduleDir, Path cache, Lockfile lock) throws IOException {
        Lockfile pinned = new LockPipeline(
                        moduleDir,
                        JkBuildParser.parse(moduleDir.resolve("jk.toml")),
                        cache,
                        null,
                        List.of(),
                        false,
                        new LockMode.Freshen())
                .pinTools(lock, LockPipeline.Progress.SILENT);
        LockfileWriter.write(pinned, moduleDir.resolve("jk-lock.toml"));
        return pinned;
    }
}
