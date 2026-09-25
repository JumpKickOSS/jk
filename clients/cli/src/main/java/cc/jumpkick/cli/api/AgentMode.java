// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.terminal.Terminals;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * When a command prints the agent run report instead of the human one. {@code --agent} and
 * {@code JK_AGENT=1} force it. Otherwise it turns on only when stdout is not a terminal and a
 * coding-agent CLI has marked the process with one of {@link #SPAWNED}.
 */
public final class AgentMode {

    public static final String ENV = "JK_AGENT";

    /**
     * Environment variables set on the commands a coding-agent CLI spawns. A non-blank value is
     * the mark. Verified against the CLIs that export them; names that only identify a session
     * are not in this list.
     */
    static final List<String> SPAWNED = List.of(
            "GROK_AGENT", "CLAUDECODE", "AI_AGENT", "CURSOR_AGENT", "CODEX_THREAD_ID", "CODEX_SANDBOX", "GEMINI_CLI");

    /**
     * Commands whose stdout, in agent mode, is the run report when this invocation wrote one. Not
     * {@code run}: its stdout is the user's program.
     */
    private static final Set<String> RUNS = Set.of(
            "build",
            "test",
            "lock",
            "compile",
            "guard",
            "format",
            "assemble",
            "native",
            "image",
            "publish",
            "install",
            "update",
            "sync",
            "verify",
            "audit");

    private AgentMode() {}

    public static boolean requested(Invocation in) {
        return requested(in.isSet("agent"), System::getenv, Terminals.stdoutIsTty(), GlobalOptions.outputIsJson(in));
    }

    /**
     * {@code explicit} is {@code --agent}. {@code JK_AGENT=0} turns auto-detection off. A json
     * stdout ({@code --output json}) keeps that stream unless agent mode was asked for explicitly.
     */
    static boolean requested(
            boolean explicit, Function<String, @Nullable String> env, boolean stdoutTty, boolean jsonStdout) {
        if (explicit) return true;
        Optional<Boolean> flag = EnvValues.bool(env, ENV);
        if (flag.isPresent()) return flag.get();
        if (jsonStdout || stdoutTty) return false;
        for (String name : SPAWNED) {
            String value = env.apply(name);
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    public static boolean reportsRun(String command) {
        return RUNS.contains(command);
    }

    /** Latest agent report for {@code project}, journal copy first, then {@code target/}. */
    public static Optional<Path> find(Path project) {
        Optional<Path> journal = ProjectBuilds.latestRunFile(ProjectBuilds.buildsRoot(), project, ProjectBuilds.AGENT);
        if (journal.isPresent()) return journal;
        Path latest = project.resolve(BuildLayout.TARGET).resolve(ProjectBuilds.AGENT);
        return Files.isRegularFile(latest) ? Optional.of(latest) : Optional.empty();
    }
}
