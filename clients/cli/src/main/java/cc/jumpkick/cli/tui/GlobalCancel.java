// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineCancel;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Signals;
import cc.jumpkick.terminal.Terminals;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-level SIGINT, SIGTERM and SIGHUP handler — cancel the live engine job through the same
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
 * <li>Run the verb's interrupt hooks — the child a {@code jk run} owns, and {@code jk dev}'s app
 * and sidecars — within {@link #HOOKS_BOUND_MILLIS}
 * <li>{@code halt(}{@link Exit#INTERRUPTED}{@code )} — guaranteed process death if anything
 * above is stuck. 130 is {@code 128 + SIGINT}, what every shell already means by it; this
 * handler must not halt with Exit.USAGE (2); use the cancel exit code.
 * </ol>
 *
 * <p>The halt is a backup, not the normal route: a verb that notices the cancel unwinds and exits
 * on its own thread long before step 4. {@link #exitCodeFor} is what makes both routes end at
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
     * The exit code of the signal that landed, set the instant it does, before anything that can
     * block; {@code 0} until then. The halt below is only a backup and often loses on a TTY when
     * the verb settles first. {@link #exitCodeFor} makes both routes exit {@code 128 + signal}
     * ({@link Exit#INTERRUPTED} for SIGINT), not a plain failure.
     */
    private static volatile int signalExit;

    /** {@code 128 + SIGTERM}: an IDE's stop button, an agent's tool runner, {@code kill}. */
    static final int TERMINATED = 128 + 15;

    /** {@code 128 + SIGHUP}: the terminal went away. */
    static final int HUNG_UP = 128 + 1;

    /**
     * What a verb wants done before this process halts: children to stop, mostly. {@link
     * Runtime#halt} skips finally blocks and shutdown hooks, so a {@code jk run} or {@code jk dev}
     * that owns a child would otherwise leave it running whenever SIGINT reaches only jk — an IDE
     * stop button, a {@code kill}, a terminal that closed or did not forward to the process group.
     */
    private static final List<Runnable> INTERRUPT_HOOKS = new CopyOnWriteArrayList<>();

    /** How long the hooks may take together; a wedged child must not defeat the halt. */
    public static final long HOOKS_BOUND_MILLIS = 6_000;

    private GlobalCancel() {}

    /** Register {@code cleanup} to run on SIGINT, SIGTERM or SIGHUP; close the handle when the verb has cleaned up itself. */
    public static Registration onInterrupt(Runnable cleanup) {
        INTERRUPT_HOOKS.add(cleanup);
        return () -> INTERRUPT_HOOKS.remove(cleanup);
    }

    /** The handle {@link #onInterrupt} returns; closing it withdraws the hook. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Run every registered hook on one daemon thread, waiting at most {@code boundMillis} for all
     * of them. A hook that throws does not stop the next; a hook that hangs is abandoned.
     */
    static void runInterruptHooks(long boundMillis) {
        if (INTERRUPT_HOOKS.isEmpty()) return;
        Thread hooks = Thread.ofPlatform().daemon(true).name("jk-sigint-hooks").start(() -> {
            for (Runnable hook : INTERRUPT_HOOKS) {
                try {
                    hook.run();
                } catch (Throwable ignored) {
                    // the next hook still runs; halt follows regardless
                }
            }
        });
        try {
            hooks.join(boundMillis);
        } catch (InterruptedException ignored) {
            // halt follows regardless
        }
    }

    /**
     * The code the process must exit with, given the verb returned {@code verbExit}. Once Ctrl-C
     * has fired, {@link Exit#INTERRUPTED} — whichever path reaches the exit first — so {@code $?}
     * and the engine's journal row agree about the same run.
     */
    public static int exitCodeFor(int verbExit) {
        int signalled = signalExit;
        return signalled != 0 ? signalled : verbExit;
    }

    /**
     * Leave the terminal in a settled, clearly canceled state: repaint the live region (a plan
     * becomes its cancelled job line) or print the generic notice, then close the command's
     * blank-line envelope. {@link Runtime#halt} skips the dispatch return that normally calls
     * {@link CliOutput#closeEnvelope()}, so the closing blank has to be printed here or the cancel
     * line ends up flush against the next shell prompt.
     */
    static void settleAsCanceled() {
        LiveRegion active = LiveRegion.active();
        boolean handled = false;
        String message = "Build job was cancelled";
        if (active != null) {
            handled = active.renderCanceled();
            message = active.canceledMessage();
        }
        var err = System.err;
        if (!handled) {
            CliOutput.err(
                    Theme.colorize(Glyphs.CROSS + " " + message, Theme.active().error()));
        }
        err.print(Ansi.RESET);
        CliOutput.closeEnvelope();
        err.flush();
        // stdout too, not only err: when stdout is not a TTY it is buffered with autoFlush off,
        // and the halt skips shutdown hooks — so up to a full buffer of `-O jsonl` output was
        // silently lost on Ctrl-C into a pipe.
        System.out.flush();
    }

    /**
     * Ctrl-C, and the two ways a process is told to go without one: SIGTERM and SIGHUP. All three
     * take the same path, so the children a verb owns stop whichever arrives; without a handler
     * SIGTERM and SIGHUP end the process outright and leave them running.
     */
    public static void install() {
        Signals.register("INT", () -> onSignal(Exit.INTERRUPTED));
        Signals.register("TERM", () -> onSignal(TERMINATED));
        Signals.register("HUP", () -> onSignal(HUNG_UP));
    }

    private static void onSignal(int exitCode) {
        // 0) Claim the exit code before anything that can block or throw: from here on this
        // process is signalled no matter which thread reaches the exit.
        if (signalExit == 0) signalExit = exitCode;
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
                .start(() -> EngineCancel.cancelBestEffortForInterrupt(dir));
        // 2) Settle the live region (plan → cancelled job line) or a one-line notice.
        settleAsCanceled();

        // 3) The verb's own children — an app under `jk dev`, its sidecars — stop here, before
        // the halt below skips every finally block that would have stopped them.
        runInterruptHooks(HOOKS_BOUND_MILLIS);

        // 4) Restore the tty (cooked attrs + stdin wake) on a bounded daemon thread —
        // halt() skips shutdown hooks, so nothing else puts the terminal back. Bounded so
        // a wedged JLine close can never break the Ctrl-C-never-hangs guarantee.
        Thread tty =
                Thread.ofPlatform().daemon(true).name("jk-sigint-tty-restore").start(Terminals::shutdown);
        try {
            tty.join(500L);
        } catch (InterruptedException ignored) {
            // halt follows regardless
        }

        // 5) Give the cancel RPCs a short, bounded window (they also self-limit), then hard
        // kill this CLI process — guaranteed death even if everything above is wedged.
        try {
            rpc.join(3_000L);
        } catch (InterruptedException ignored) {
            // halt follows regardless
        }
        Runtime.getRuntime().halt(signalExit);
    }
}
