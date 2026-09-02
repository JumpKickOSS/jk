// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Signals;
import cc.jumpkick.terminal.Terminals;
import java.nio.file.Path;

/**
 * App-level SIGINT handler — cancel the live engine job through the same
 * {@code cancel-request} path as {@code jk cancel}, settle the TUI as cancelled, then hard-exit
 * the CLI ({@link Runtime#halt(int) halt}({@link Exit#INTERRUPTED})) as a backup so Ctrl-C never
 * hangs.
 *
 * <p>Order matters:
 *
 * <ol>
 * <li>Cooperative session cancel + engine {@code cancel-request} (jid / project dir) — same
 * kill path as the web UI and {@code jk cancel}
 * <li>Settle the active plan region ("Build job was cancelled by user took …")
 * <li>{@code halt(}{@link Exit#INTERRUPTED}{@code )} — guaranteed process death if anything
 * above is stuck. 130 is {@code 128 + SIGINT}, what every shell already means by it; this
 * handler must not halt with Exit.USAGE (2); use the cancel exit code.
 * </ol>
 *
 * <p>The halt is a backup, not the normal route: a verb that notices the cancel unwinds and exits
 * on its own thread long before step 3. {@link #exitCodeFor} is what makes both routes end at
 * {@link Exit#INTERRUPTED}.
 *
 * <p>Wizards run in {@code PROMPT} (ISIG off) so Ctrl-C arrives as {@code Key.CtrlC} instead of
 * SIGINT. Live plans use {@code PLAN_KEYS} (ISIG on) so this handler still owns Ctrl-C.
 *
 * <p>Uses {@link Signals#register} (reflective {@code sun.misc.Signal} wrapper) so the compiler
 * doesn't emit "internal proprietary API" warnings.
 */
public final class GlobalCancel {

    /**
     * Set the instant SIGINT lands, before anything that can block. The halt below is only a
     * backup and often loses on a TTY when the verb settles first. {@link #exitCodeFor} makes both
     * routes exit {@link Exit#INTERRUPTED} (130), not a plain failure.
     */
    private static volatile boolean interrupted;

    private GlobalCancel() {}

    /**
     * The code the process must exit with, given the verb returned {@code verbExit}. Once Ctrl-C
     * has fired, {@link Exit#INTERRUPTED} — whichever path reaches the exit first — so {@code $?}
     * and the engine's journal row agree about the same run.
     */
    public static int exitCodeFor(int verbExit) {
        return interrupted ? Exit.INTERRUPTED : verbExit;
    }

    public static void install() {
        Signals.register("INT", () -> {
            // 0) Claim the exit code before anything that can block or throw: from here on this
            // process is interrupted no matter which thread reaches the exit.
            interrupted = true;
            // 1) Cooperative cancel is synchronous (cheap, in-process); the engine RPCs go on a
            // background thread so the user sees the cancelled settle immediately instead of a
            // still-animating spinner while a wedged engine eats socket watchdogs.
            SessionContext.current().cancel().cancel();
            // The session's working dir honors -C/--dir (the raw process CWD does not),
            // and jobs register their workspace-root ENTRY dir — resolve to it so Ctrl-C from a
            // member dir cancels the covering workspace build.
            Path invocationDir;
            try {
                invocationDir = SessionContext.current().workingDir();
            } catch (RuntimeException e) {
                invocationDir = null;
            }
            if (invocationDir == null) {
                invocationDir = Path.of("").toAbsolutePath().normalize();
            }
            Path dir = WorkspaceScan.findRoot(invocationDir).orElse(invocationDir);
            Thread rpc = Thread.ofPlatform()
                    .daemon(true)
                    .name("jk-sigint-cancel")
                    .start(() -> EngineClient.cancelBestEffortForInterrupt(dir));

            // 2) Settle the live region (plan → cancelled job line) or a one-line notice.
            LiveRegion active = LiveRegion.active();
            boolean handled = false;
            String message = "Build job was cancelled";
            if (active != null) {
                handled = active.renderCanceled();
                message = active.canceledMessage();
            }
            var err = System.err;
            if (!handled) {
                err.print("\n"
                        + Theme.colorize(
                                Glyphs.CROSS + " " + message, Theme.active().error()) + "\n");
            }
            err.print(Ansi.RESET);
            err.flush();
            // stdout too, not only err: when stdout is not a TTY it is buffered with autoFlush off,
            // and halt() below skips shutdown hooks — so up to a full buffer of `-O json` output was
            // silently lost on Ctrl-C into a pipe.
            System.out.flush();

            // 3) Restore the tty (cooked attrs + stdin wake) on a bounded daemon thread —
            // halt() skips shutdown hooks, so nothing else puts the terminal back. Bounded so
            // a wedged JLine close can never break the Ctrl-C-never-hangs guarantee.
            Thread tty = Thread.ofPlatform()
                    .daemon(true)
                    .name("jk-sigint-tty-restore")
                    .start(Terminals::shutdown);
            try {
                tty.join(500L);
            } catch (InterruptedException ignored) {
                // halt follows regardless
            }

            // 4) Give the cancel RPCs a short, bounded window (they also self-limit), then hard
            // kill this CLI process — guaranteed death even if everything above is wedged.
            try {
                rpc.join(3_000L);
            } catch (InterruptedException ignored) {
                // halt follows regardless
            }
            Runtime.getRuntime().halt(Exit.INTERRUPTED);
        });
    }
}
