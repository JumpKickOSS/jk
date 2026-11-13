// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.AgentMode;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.DecisionsRequest;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * After a person's successful build on Windows, the engine is asked to offer the machine-setup
 * decision; it does so once per machine, as a notification and never as a prompt here. An agent's
 * run, {@code -a}, a json stdout or a run with no terminal never offers it.
 */
final class SetupOfferAfterBuild {

    private SetupOfferAfterBuild() {}

    static void offer(GlobalOptions global) {
        if (!Os.isWindows()
                || !person(global.agent, global.outputIsJson(), Interactivity.canPrompt(), System::getenv)) {
            return;
        }
        String ask = MiniJson.write(Map.of("project", global.workingDir().toString()));
        try {
            EngineClient.decisions(
                    EnginePaths.current(), new DecisionsRequest(DecisionsRequest.OFFER, null, null, ask));
        } catch (IOException | RuntimeException e) {
            Log.debug("machine setup offer not sent", e);
        }
    }

    /** True for a run a person watches at a terminal, and false for anything an agent runs. */
    static boolean person(boolean agent, boolean json, boolean canPrompt, Function<String, @Nullable String> env) {
        return canPrompt && !agent && !json && !AgentMode.spawned(env);
    }
}
