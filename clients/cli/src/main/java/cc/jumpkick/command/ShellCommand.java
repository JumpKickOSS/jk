// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.compat.PassthroughEnv;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Terminals;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk shell} — spawn a subshell with the env the project would have under {@code jk
 * activate}. Resolves the JDK via {@link JkEnv} (the {@code jk.toml} + {@code jk-lock.toml} flow), so
 * {@code jk shell} and {@code jk activate} can never disagree on which JDK is "current".
 *
 * <p>POSIX shells only for this iteration — picks {@code $SHELL} (or {@code /bin/sh}) and inherits
 * stdio. Native cmd / PowerShell wiring lands in a follow-up.
 */
public final class ShellCommand implements CliCommand {

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Spawn a subshell with the project's pinned JDK on PATH";
    }

    @Override
    public List<Opt> options() {
        return List.of(CommonOpts.jdksDir());
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        // jk shell hands control to an interactive subshell. Without a terminal
        // to attach it to (scripted, CI, or running inside a jk worker whose stdin
        // is a control pipe) the spawned shell would sit at its prompt forever and
        // waitFor() would block. Fail fast instead. Keyed on the controlling
        // terminal, so `jk shell` under `curl | bash` (piped stdin) still works.
        if (!Interactivity.canPrompt()) {
            CommandWedge.printFail(
                    "Shell",
                    "requires an interactive terminal " + "(run it directly from your shell, not piped or scripted)");
            return Exit.CONFIG;
        }
        Path jdksDir = CommonOpts.jdksDirValue(in);
        Path dir = new GlobalOptions().workingDir();
        var origPath = System.getenv().getOrDefault("PATH", "");
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        var target = new JkEnv(registry, origPath).resolve(dir);
        if (!target.isActive()) {
            CommandWedge.printFail(
                    "Shell",
                    "no pinned JDK for " + PathDisplay.styledRaw(dir)
                            + " (run `jk new` to scaffold, or stamp `jdk = \"<id>\"` in jk-lock.toml)");
            return Exit.CONFIG;
        }
        String shell = System.getenv().getOrDefault("SHELL", "/bin/sh");
        ProcessBuilder pb = new ProcessBuilder(shell);
        pb.directory(dir.toFile());
        Terminals.restoreForChild();
        pb.inheritIO();
        var env = pb.environment();
        target.vars().forEach(env::put);
        // Strip the vars through which the surrounding shell could out-vote the pin we just applied
        // (JDK_HOME above all — see PassthroughEnv). Null javaHome: JkEnv already layered PATH on
        // __JK_ORIG_PATH, so prepending <jdk>/bin again here would double it.
        PassthroughEnv.apply(env, null);

        var javaHome = target.vars().get(JkEnv.JAVA_HOME);
        CliOutput.out("Entering jk shell with JAVA_HOME=" + javaHome);
        Process p = pb.start();
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        return p.waitFor();
    }
}
