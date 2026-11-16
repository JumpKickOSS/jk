// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.AgentMode;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.host.Guest;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.winhost.WindowsFromGuest;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.DecisionsAck;
import cc.jumpkick.wire.protocol.DecisionsRequest;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * After a person's successful build on Windows, the engine is asked to offer the machine-setup
 * decision; it does so once per machine, as a notification and never as a prompt here. On a WSL
 * guest with interop it is asked to offer JumpKick for Windows instead, and when it raises that offer
 * (once) this prints one hint line. An agent's run, {@code -a}, a json stdout or a run with no
 * terminal never offers either.
 */
final class SetupOfferAfterBuild {

    private SetupOfferAfterBuild() {}

    static void offer(GlobalOptions global) {
        boolean windows = Os.isWindows();
        if (!(windows || wslGuest(Guest.detect()))
                || !person(global.agent, global.outputIsJson(), Interactivity.canPrompt(), System::getenv)) {
            return;
        }
        String ask = MiniJson.write(Map.of("project", global.workingDir().toString()));
        try {
            DecisionsAck ack = EngineClient.decisions(
                    EnginePaths.current(), new DecisionsRequest(DecisionsRequest.OFFER, null, null, ask));
            String hint = hint(windows, ack);
            if (hint != null) CliOutput.out(hint);
        } catch (IOException | RuntimeException e) {
            Log.debug("machine setup offer not sent", e);
        }
    }

    /** A WSL guest whose engine can reach Windows. */
    static boolean wslGuest(Guest guest) {
        return guest.kind() == Guest.Kind.WSL && guest.interop();
    }

    /** The line to print for the engine's answer: the guest's hint when it raised its offer, else none. */
    static @Nullable String hint(boolean windows, DecisionsAck ack) {
        return !windows && ack.error() == null && ack.decision() != null ? WindowsFromGuest.HINT : null;
    }

    /** True for a run a person watches at a terminal, and false for anything an agent runs. */
    static boolean person(boolean agent, boolean json, boolean canPrompt, Function<String, @Nullable String> env) {
        return canPrompt && !agent && !json && !AgentMode.spawned(env);
    }
}
