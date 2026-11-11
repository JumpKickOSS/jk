// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.form.InlineYesNo;
import cc.jumpkick.cli.watch.ProcessTrees;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.config.UpdateOffer;
import cc.jumpkick.config.WhatsNew;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a command at a terminal does once it has finished: record that a person uses jk here, print
 * the notice that a pre-1.0 {@code self-update = "ask"} or {@code "never"} is ignored, and offer an
 * update the engine has downloaded:
 *
 * <pre> ↑ Update  A new version of JumpKick is available. Update Now?  [Yes]  No </pre>
 *
 * <p>The first such command after an install says so, once:
 *
 * <pre> ↑ Updated  JumpKick was updated to 0.51.0 (from 0.50.3). What's new: https://… </pre>
 *
 * <p>The offer comes whenever updating is on and a newer release is staged ({@link UpdateOffer}):
 * before 1.0 that is always, and the engine installs the update at its next quiet spell anyway, so
 * Yes only gets it sooner. No quiets the offer on every surface until the next check. A command
 * that failed, speaks a machine format, or manages jk itself is left alone.
 */
final class SelfUpdatePrompt {

    /** How stale the "a person was here" stamp may get before it is written again. */
    static final Duration SEEN_REFRESH = Duration.ofHours(1);

    /** Commands after which jk never prompts: jk managing itself, and long-lived protocol servers. */
    static final Set<String> QUIET_COMMANDS = Set.of("self", "engine", "mcp", "lsp", "completion", "shell");

    /** The question on the yellow chip. */
    static final String QUESTION = "A new version of JumpKick is available. Update Now?";

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
        if (state.announceDue(version, state.announcedCli())) {
            CliOutput.stderr().println(announcement(RenderContext.current(), state.updatedFrom(), version));
            state = SelfUpdateState.update(s -> s.withAnnouncedCli(version));
        }
        if (SelfUpdatePolicy.decide(version, env, configured, state, true).action() == SelfUpdatePolicy.Action.OFF) {
            return;
        }
        String staged = PreparedUpdate.read(EngineInstall.current().engineHome())
                .map(PreparedUpdate::version)
                .orElse(null);
        Optional<String> offer = UpdateOffer.due(state, staged, version, now);
        if (offer.isEmpty()) return;
        String next = offer.get();
        if (ask()) {
            install(next);
        } else {
            SelfUpdateState.update(s -> UpdateOffer.snooze(s, next, now));
        }
    }

    /** The yellow {@code ↑ Updated} line: what changed and where its highlights are. */
    static String announcement(RenderContext ctx, @Nullable String from, String to) {
        String text = WhatsNew.headline(from == null ? to : from, to) + ". What's new: " + WhatsNew.releaseUrl(to);
        return JkWedge.update("Updated", RichText.plain(text)).renderLine(ctx);
    }

    /** The offer on the controlling terminal; anything but Yes (No, Esc, end of input) is "not now". */
    private static boolean ask() {
        if (!Interactivity.canAsk()) return false;
        TerminalSession tty = Terminals.controlling();
        if (!tty.isLive()) return false;
        RenderContext ctx = RenderContext.current();
        String lead = JkWedge.update("Update", RichText.plain(QUESTION)).renderLine(ctx);
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return InlineYesNo.ask(tty, CliOutput.stderr(), in, ctx, lead, true).orElse(false);
    }

    /** {@code jk self update <version>} on the PATH client: installs the download and hands the engine over. */
    private static void install(String version) throws IOException {
        Path jk = EngineInstall.pathClient(JkDirs.binDir());
        if (!Files.isRegularFile(jk)) return;
        try {
            ProcessTrees.awaitExit(
                    CliOutput.handOffTerminal(new ProcessBuilder(jk.toString(), "self", "update", version)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
