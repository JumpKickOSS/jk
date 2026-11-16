// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.wire.DashboardSigning;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Whether a dashboard link this command prints may carry the engine's token, and the link as it
 * may be printed. The one rule behind every command that prints a dashboard URL ({@code jk web},
 * {@code jk engine status}, {@code jk doctor --fix}).
 *
 * <p>The token opens this engine and, since a front door pairs them, every engine on the machine;
 * what a command prints is captured wherever its output goes. So a printed link is signed only for
 * a person at an interactive terminal: stdout is a terminal, the run is not {@code --agent} or
 * {@code -O json}/{@code jsonl}, {@code CI} is not set and no coding agent spawned it ({@link
 * AgentMode#spawned}). Anywhere else the link is printed bare; a browser that has opened a signed
 * link once keeps the token and opens it signed in, and one that has not shows the dashboard's
 * authorization screen, which says to run {@code jk web}. Links the engine answers with carry no
 * token at all ({@code DashboardLinks}); the CLI signs them here under the same rule.
 */
public final class DashboardToken {

    private DashboardToken() {}

    /**
     * True when a printed link may carry the token: {@code stdoutTty}, not {@code agent}, not
     * {@code json}, and {@code env} names neither CI nor a coding agent.
     */
    public static boolean mayPrint(
            boolean agent, boolean json, boolean stdoutTty, Function<String, @Nullable String> env) {
        return stdoutTty && !agent && !json && !EnvValues.isCi(env) && !AgentMode.spawned(env);
    }

    /** {@link #mayPrint} for this invocation: its flags, this process's stdout and environment. */
    public static boolean mayPrint(GlobalOptions global) {
        return mayPrint(global.agent, global.outputIsJson(), Terminals.stdoutIsTty(), System::getenv);
    }

    /** {@code link} as this invocation may print it: signed when {@link #mayPrint}, else as given. */
    public static @Nullable String forOutput(@Nullable String link, GlobalOptions global, EnginePaths.Paths paths) {
        return mayPrint(global) ? signed(link, paths) : link;
    }

    /**
     * {@code link} signed with the engine's token, for what is never printed: the URL handed to the
     * browser {@code jk web} opens. {@code link} itself when the owner-only token file is unreadable.
     */
    public static @Nullable String signed(@Nullable String link, EnginePaths.Paths paths) {
        return DashboardSigning.signed(link, token(paths));
    }

    /** The engine's bearer token from its owner-only file; {@code ""} when it cannot be read. */
    public static String token(EnginePaths.Paths paths) {
        try {
            return Files.readString(paths.httpToken()).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
