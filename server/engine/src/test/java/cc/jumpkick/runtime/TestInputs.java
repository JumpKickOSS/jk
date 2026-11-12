// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Path;

/**
 * The {@link BuildPlanner.Inputs} a test plans one module from: {@code dir}'s own {@code jk.toml}
 * and {@code jk-lock.toml}, named outright rather than looked up, so a fixture that writes them
 * after building its inputs, or that sits inside a workspace, plans the files it means. Everything
 * else is {@link BuildPlanner.Inputs#of}'s; a test sets what it needs with the {@code with} copies.
 */
public final class TestInputs {

    private TestInputs() {}

    /** Under the current session. */
    public static BuildPlanner.Inputs of(Path dir, Path cache) {
        return of(dir, cache, SessionContext.current());
    }

    public static BuildPlanner.Inputs of(Path dir, Path cache, Session session) {
        return BuildPlanner.Inputs.of(dir, cache, session)
                .withBuildFile(dir.resolve("jk.toml"))
                .withLockFile(dir.resolve("jk-lock.toml"))
                .withLockDir(dir);
    }
}
