// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;

/**
 * Bring the build engine up <em>before</em> an engine-backed command builds its own plan console, so
 * a first start (and the JDK download it may need) renders on its own and the command's TUI then
 * takes over cleanly (sequential, never interleaved). When an engine is already running this is a
 * fast handshake no-op that shows nothing.
 *
 * <p>Best-effort by contract: a failure here is swallowed so the command's own engine call surfaces
 * the real error through its existing error handling — the prewarm only ever <em>adds</em> the early
 * start, it never changes failure behavior. Call only on the hosted (non-test) path.
 */
public final class EnginePrewarm {
    private EnginePrewarm() {}

    public static void ensure() {
        try {
            EngineClient.ensureRunning(EnginePaths.current(), JkVersion.VERSION);
        } catch (IOException | RuntimeException ignored) {
            // The command's own ensureRunning call will surface any genuine failure.
        }
    }
}
