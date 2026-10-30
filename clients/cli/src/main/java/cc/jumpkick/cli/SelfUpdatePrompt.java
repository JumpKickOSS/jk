// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.Prompt;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a command at a terminal does once it has finished: record that a person uses jk here (the
 * engine's default asks first where one does), print the notice that a pre-1.0 {@code
 * self-update = "ask"} or {@code "never"} is ignored, and, under {@code ask}, offer an update the
 * engine has downloaded. A command that failed, speaks a machine format, or manages jk itself is
 * left alone.
 */
final class SelfUpdatePrompt {

    /** How long "not now" keeps the prompt quiet. */
    static final Duration SNOOZE = Duration.ofHours(24);

    /** How stale the "a person was here" stamp may get before it is written again. */
    static final Duration SEEN_REFRESH = Duration.ofHours(1);

    /** Commands after which jk never prompts: jk managing itself, and long-lived protocol servers. */
    static final Set<String> QUIET_COMMANDS = Set.of("self", "engine", "mcp", "lsp", "completion", "shell");

    /** The prompt's answers. */
    enum Answer {
        YES,
        NOT_NOW,
        ALWAYS,
        NEVER
    }

    private SelfUpdatePrompt() {}

    /** Run after {@code command} exited with {@code exit}; never throws and never changes the exit. */
    static void afterCommand(@Nullable String command, List<String> args, int exit) {
        try {
            if (!eligible(command, args, exit) || !Interactivity.canPrompt()) return;
            run(Clock.SYSTEM.instant());
        } catch (IOException | RuntimeException e) {
            Log.debug("SelfUpdatePrompt: skipped", e);
        }
    }

    /** Whether a finished command may be followed by the notice or the prompt. */
    static boolean eligible(@Nullable String command, List<String> args, int exit) {
        if (exit != 0 || command == null || QUIET_COMMANDS.contains(command)) return false;
        for (String a : args) {
            if (a.equals("--agent") || a.equals("-q") || a.equals("--quiet") || a.equals("--json")) return false;
            if (a.startsWith("-O") || a.startsWith("--output")) return false;
        }
        return true;
    }

    private static void run(Instant now) throws IOException {
        String version = JkVersion.VERSION;
        SelfUpdateState state = SelfUpdateState.read();
        if (state.interactiveSeen() == null
                || state.interactiveSeen().plus(SEEN_REFRESH).isBefore(now)) {
            state = SelfUpdateState.update(s -> s.withInteractiveSeen(now));
        }
        String env = System.getenv(SelfUpdatePolicy.ENV);
        String configured = GlobalConfig.selfUpdate().orElse(null);
        if (SelfUpdatePolicy.configIgnored(version, env, configured) && !version.equals(state.notice())) {
            CliOutput.stderr()
                    .println("jk: " + SelfUpdatePolicy.KEY + " = \"" + configured + "\" is ignored before 1.0;"
                            + " jk updates itself until then");
            state = SelfUpdateState.update(s -> s.withNotice(version));
        }
        if (SelfUpdatePolicy.decide(version, env, configured, state, true).action() != SelfUpdatePolicy.Action.ASK) {
            return;
        }
        if (state.snoozeUntil() != null && now.isBefore(state.snoozeUntil())) return;
        Optional<PreparedUpdate> ready =
                PreparedUpdate.read(EngineInstall.current().engineHome()).filter(p -> p.newerThan(version));
        if (ready.isEmpty()) return;
        String next = ready.get().version();
        Answer answer = new Prompt<>(
                        RichText.plain("jk " + next + " is ready (this is " + version + "). Update now?"),
                        List.of(
                                new Prompt.Binding<>('y', "update", Answer.YES),
                                new Prompt.Binding<>('n', "not now", Answer.NOT_NOW),
                                new Prompt.Binding<>('a', "always update", Answer.ALWAYS),
                                new Prompt.Binding<>('v', "never update", Answer.NEVER)),
                        Answer.YES,
                        Answer.NOT_NOW)
                .ask();
        switch (answer) {
            case YES -> install(next);
            case NOT_NOW -> SelfUpdateState.update(s -> s.withSnoozeUntil(now.plus(SNOOZE)));
            case ALWAYS -> {
                UserConfigEditor.setSelfUpdate(JkDirs.userConfigFile(), SelfUpdatePolicy.Mode.AUTO);
                install(next);
            }
            case NEVER -> {
                UserConfigEditor.setSelfUpdate(JkDirs.userConfigFile(), SelfUpdatePolicy.Mode.NEVER);
                CliOutput.stderr()
                        .println("jk: wrote " + SelfUpdatePolicy.KEY + " = \"never\" to " + JkDirs.userConfigFile()
                                + "; `jk self update` still updates by hand");
            }
        }
    }

    /** {@code jk self update <version>} on the PATH client: installs the download and hands the engine over. */
    private static void install(String version) throws IOException {
        Path jk = EngineInstall.pathClient(JkDirs.binDir());
        if (!Files.isRegularFile(jk)) return;
        try {
            CliOutput.handOffTerminal(new ProcessBuilder(jk.toString(), "self", "update", version))
                    .waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
