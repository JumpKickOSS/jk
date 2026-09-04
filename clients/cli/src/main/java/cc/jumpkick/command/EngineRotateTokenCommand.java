// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.EngineProcessControl;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * {@code jk engine rotate-token} — invalidate the embedded HTTP server's bearer token
 * ({@code docs/http.md}). The token is otherwise persisted and stable across restarts so open
 * dashboard tabs keep working; rotation is the explicit escape hatch for when you actually want to
 * revoke it (say, you shared a tokenized URL and want to lock it out).
 *
 * <p>Revocation has two halves: delete the persisted token file, and stop any running engine so its
 * in-memory copy stops being honored. The next {@code jk} command spawns a fresh engine that mints a
 * new token; {@code jk engine status} prints the new tokenized URL.
 */
public final class EngineRotateTokenCommand implements CliCommand {

    @Override
    public String name() {
        return "rotate-token";
    }

    @Override
    public String description() {
        return "Invalidate and regenerate the HTTP dashboard token";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        EnginePaths.Paths paths = EnginePaths.current();
        try {
            Files.deleteIfExists(paths.httpToken());
        } catch (IOException e) {
            CommandWedge.printFail("Engine", "could not remove the token file (" + e.getMessage() + ")");
            return Exit.SOFTWARE;
        }
        // A running engine still holds the old token in memory — stop it so the old value is
        // genuinely revoked, not just replaced on disk. The next command respawns and mints fresh.
        if (EngineProbe.ping(EnginePaths.activeSocket(paths))
                && !EngineProcessControl.stop(EnginePaths.activeSocket(paths))) {
            CommandWedge.printFail(
                    "Engine",
                    "token file removed, but stopping the running engine failed;"
                            + " run 'jk engine stop' so the old token stops being accepted");
            return Exit.SOFTWARE;
        }
        CommandWedge.printOk(
                "Engine",
                "token rotated." + " The next command spawns an engine with a fresh token —"
                        + " run 'jk engine status' for the new dashboard URL.");
        return Exit.SUCCESS;
    }
}
