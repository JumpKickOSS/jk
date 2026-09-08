// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.command.JkEnv;
import cc.jumpkick.command.ToolchainPath;
import cc.jumpkick.command.toolchain.Shell;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Hidden {@code jk hook-env}: shell exports for {@code JAVA_HOME}/{@code GRAALVM_HOME} and a
 * surgical {@code PATH} toolchain-bin swap, undoing via {@code __JK_DIFF} when leaving or switching
 * projects. {@code PATH} itself is never frozen in the diff — only the homes are — so user PATH
 * edits (nvm, etc.) survive every prompt.
 */
public final class HookEnvCommand implements CliCommand {

    @Override
    public String name() {
        return "hook-env";
    }

    @Override
    public String description() {
        return "Internal: emit env-sync commands for the current directory";
    }

    @Override
    public boolean hidden() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<shell>", "Shell: bash | zsh | fish | pwsh | powershell.", "-s", "--shell")
                        .require(),
                Opt.flag("Undo JAVA_HOME/GRAALVM_HOME and toolchain bins.", "--clear"));
    }

    /** The activate script's directory hook evals stdout on every prompt. */
    @Override
    public boolean scriptMode(Invocation in) {
        return true;
    }

    @Override
    public int run(Invocation in) throws IOException {
        String shellName = in.value("shell").orElseThrow();
        var shell = Shell.byName(shellName);
        if (shell.isEmpty()) {
            CommandWedge.printFail("Hook-env", "unsupported shell `" + shellName + "`");
            return Exit.USAGE;
        }
        var prevDiff = JkDiff.parse(System.getenv("__JK_DIFF"));
        var snapshot = JkDiff.EnvSnapshot.fromSystem();
        // --clear: tear down session env without re-resolving a default JDK for cwd.
        var target =
                in.isSet("clear") ? JkEnv.Target.empty() : JkEnv.defaults().resolve(new GlobalOptions().workingDir());

        var out = new StringBuilder();
        emit(shell.get(), target, prevDiff, snapshot, out);
        CliOutput.outRaw(String.valueOf(out));
        return 0;
    }

    /**
     * Compute the diff between {@code prevDiff}'s tracked keys and {@code target}'s desired state,
     * append shell commands to {@code out}, and write the updated {@code __JK_DIFF}.
     *
     * <p>Package-private so tests can drive it without a real shell.
     */
    public static void emit(
            Shell shell, JkEnv.Target target, JkDiff prevDiff, JkDiff.EnvSnapshot snapshot, StringBuilder out) {
        // PATH is swapped surgically from the live value; never recorded in __JK_DIFF.
        var managed = new LinkedHashMap<>(target.vars());
        managed.remove(JkEnv.PATH);
        var trackedTarget = new JkEnv.Target(target.projectRoot(), managed);
        var nextDiff = prevDiff.next(trackedTarget, snapshot);

        // 1. Keys the prior diff tracked but the new target no longer owns —
        //    restore the original value (or unset). Skip PATH: a frozen prior PATH
        //    would clobber anything the user added since activation.
        var dropped = new LinkedHashSet<>(prevDiff.keys());
        dropped.removeAll(managed.keySet());
        dropped.remove(JkEnv.PATH);
        for (var key : dropped) {
            String previous = prevDiff.previousValue(key);
            if (prevDiff.wasUnset(key) || previous == null) {
                out.append(shell.unsetEnv(key));
            } else {
                out.append(shell.setEnv(key, previous));
            }
        }

        // 2. Homes in the new target — set them. (Even if unchanged from a
        //    prior hook-env call, re-emitting is cheap and keeps the shell
        //    self-consistent across re-sourcing the activate script.)
        for (var entry : managed.entrySet()) {
            out.append(shell.setEnv(entry.getKey(), entry.getValue()));
        }

        // 3. PATH: strip the bins of homes jk itself exported, prepend the new
        //    toolchain bins. A live home is jk-managed iff the prior diff tracks its
        //    variable — a user-owned JAVA_HOME/GRAALVM_HOME bin is never removed:
        //    activation prepends ahead of it, so leaving needs no re-add. Only emit
        //    when we manage a toolchain now or a prior diff did (so leave/deactivate
        //    strips cleanly) — never rewrite PATH on a no-op prompt with no jk state.
        boolean managing = managed.containsKey(JkEnv.JAVA_HOME) || managed.containsKey(JkEnv.GRAALVM_HOME);
        boolean wasManaging = prevDiff.keys().contains(JkEnv.JAVA_HOME)
                || prevDiff.keys().contains(JkEnv.GRAALVM_HOME)
                || prevDiff.keys().contains(JkEnv.PATH);
        if (managing || wasManaging) {
            String path = ToolchainPath.swap(
                    snapshot.get(JkEnv.PATH),
                    prevDiff.keys().contains(JkEnv.JAVA_HOME) ? snapshot.get(JkEnv.JAVA_HOME) : null,
                    prevDiff.keys().contains(JkEnv.GRAALVM_HOME) ? snapshot.get(JkEnv.GRAALVM_HOME) : null,
                    managed.get(JkEnv.JAVA_HOME),
                    managed.get(JkEnv.GRAALVM_HOME));
            out.append(shell.setEnv(JkEnv.PATH, path));
        }

        // 4. Update __JK_DIFF (or unset it when there's nothing to remember).
        var encoded = nextDiff.encode();
        if (encoded.isEmpty()) {
            if (!prevDiff.keys().isEmpty()) {
                out.append(shell.unsetEnv("__JK_DIFF"));
            }
        } else {
            out.append(shell.setEnv("__JK_DIFF", encoded));
        }
    }
}
