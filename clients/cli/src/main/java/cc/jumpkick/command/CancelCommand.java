// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Param;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk cancel} / {@code jk kill} / {@code jk cancel &lt;jid&gt;} — cancel a live engine job
 *
 *
 * <ul>
 * <li>No args: cancel every running job for the current project directory.
 * <li>{@code &lt;jid&gt;}: cancel that job by id (from {@code jk jobs} / {@code job-start}).
 * </ul>
 */
public final class CancelCommand implements CliCommand {

    @Override
    public String name() {
        return "cancel";
    }

    @Override
    public List<String> aliases() {
        return List.of("kill");
    }

    @Override
    public String description() {
        return "Cancel a running engine job (by jid, or all for this project)";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("jid", Arity.ZERO_OR_ONE, "Job id from `jk jobs` (omit to cancel this project)"));
    }

    /** The dir jobs are registered under: the workspace root when {@code dir} is inside one. */
    static Path cancelScope(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        return cc.jumpkick.config.WorkspaceScan.findRoot(normalized).orElse(normalized);
    }

    @Override
    public int run(Invocation in) throws Exception {
        // From the invocation (so -C/--dir works, like every other command), then resolved
        // to the workspace root: jobs register their ENTRY dir, so cancelling from a member dir
        // must match the workspace build that covers it.
        Path dir = cancelScope(GlobalOptions.from(in).workingDir());
        Optional<String> jidArg = in.positionals().isEmpty()
                ? Optional.empty()
                : Optional.of(in.positionals().get(0).trim());

        Optional<String> ack;
        if (jidArg.isPresent()) {
            long jid;
            try {
                jid = Long.parseLong(jidArg.get());
            } catch (NumberFormatException e) {
                CommandWedge.printFail("Cancel", "jid must be a number (got `" + jidArg.get() + "`)");
                return Exit.USAGE;
            }
            if (jid < 0) {
                CommandWedge.printFail("Cancel", "jid must be non-negative");
                return Exit.USAGE;
            }
            ack = EngineClient.cancel(EnginePaths.current(), jid);
        } else {
            ack = EngineClient.cancelForDir(EnginePaths.current(), dir.toString());
        }

        if (ack.isEmpty()) {
            CommandWedge.printFail("Cancel", "engine did not acknowledge cancel");
            return Exit.SOFTWARE;
        }
        String line = ack.get();
        boolean cancelled = Jsonl.bool(line, "cancelled", false);
        String note = Jsonl.str(line, "note");
        long jid = Jsonl.longValue(line, "jid", -1);
        if (cancelled) {
            String msg = jid > 0 ? cancelledJobMessage(jid) : "Cancelled project build jobs";
            if (note != null && !note.isBlank() && jid <= 0) msg = msg + " (" + note + ")";
            CommandWedge.printOk("Cancel", msg);
            return 0;
        }
        String fail = note != null && !note.isBlank() ? note : "no running job matched";
        CommandWedge.printFail("Cancel", fail);
        // Unknown jid is a soft user error (not a crash).
        return jidArg.isPresent() ? Exit.DATA_ERR : 0;
    }

    /** {@code Cancelled build job #N} with N bold white when ANSI is on. */
    static String cancelledJobMessage(long jid) {
        Theme t = Theme.active();
        String num = t.isAnsi() ? Theme.colorize(String.valueOf(jid), t.focused()) : String.valueOf(jid);
        return "Cancelled build job #" + num;
    }
}
