// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk engine start} — eager, blocking start: waits for the engine to be confirmed live (or
 * fails with a clear error) rather than firing lazily and hoping, so CI pre-warming gets a meaningful
 * exit code. A no-op (exit 0) when a live, version-matched engine is already running.
 */
public final class EngineStartCommand implements CliCommand {

    @Override
    public String name() {
        return "start";
    }

    @Override
    public String description() {
        return "Start the build engine (no-op if already running)";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        EnginePaths.Paths paths = EnginePaths.current();
        // Was a matching engine already up before we touched it? Distinguishes "already running"
        // from a fresh start in the settled wedge below.
        boolean alreadyUp = EngineProbe.handshake(EnginePaths.activeSocket(paths), Jk.VERSION)
                .map(h -> Jk.VERSION.equals(h.version()))
                .orElse(false);
        try {
            EngineProbe.Handshake hs = EngineClient.ensureRunning(paths, Jk.VERSION);
            String pid = pidStyled(hs.pid());
            String message =
                    alreadyUp ? "Engine already running (pid " + pid + ")" : "Build engine started (pid " + pid + ")";
            CommandWedge.printOk("Engine", message);
            return Exit.SUCCESS;
        } catch (IOException e) {
            CommandWedge.printFail("Engine", e.getMessage());
            return Exit.SOFTWARE;
        }
    }

    /** The engine pid in yellow on an ANSI terminal (matching the status wedge), plain otherwise. */
    private static @Nullable String pidStyled(long pid) {
        String s = Long.toString(pid);
        return Theme.active().isAnsi() ? Theme.colorize(s, Theme.active().warning()) : s;
    }
}
