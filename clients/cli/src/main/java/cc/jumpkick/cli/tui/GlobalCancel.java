// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import org.jline.utils.Signals;

/**
 * App-level SIGINT handlercancel the live engine job through the same
 * {@code cancel-request} path as {@code jk cancel}, settle the TUI as cancelled, then hard-exit
 * the CLI ({@link Runtime#halt(int) halt(2)}) as a backup so Ctrl-C never hangs.
 *
 * <p>Order matters:
 *
 * <ol>
 * <li>Cooperative session cancel + engine {@code cancel-request} (jid / project dir) — same
 * kill path as the web UI and {@code jk cancel}
 * <li>Settle the active pipeline region ("Build job was cancelled by user took …")
 * <li>{@code halt(2)} — guaranteed process death if anything above is stuck
 * </ol>
 *
 * <p>Wizards temporarily override this via {@link org.jline.terminal.Terminal#handle} so Ctrl-C
 * inside a wizard runs the wizard's own cancel path instead. The wizard re-calls {@link #install}
 * from its {@code finally} block so the global default is restored on exit — JLine's "previous
 * handler" tracking doesn't reliably round-trip the underlying {@code sun.misc.Signal} handler back
 * into place.
 *
 * <p>Uses {@link Signals#register} (JLine's reflective wrapper around {@code sun.misc.Signal})
 * instead of calling that class directly, so the compiler doesn't emit "internal proprietary API"
 * warnings.
 */
public final class GlobalCancel {

    private GlobalCancel() {}

    public static void install() {
        Signals.register("INT", () -> {
            // 1) Cooperative cancel is synchronous (cheap, in-process); the engine RPCs go on a
            // background thread so the user sees the cancelled settle immediately instead of a
            // still-animating spinner while a wedged engine eats socket watchdogs.
            cc.jumpkick.config.SessionContext.current().cancel().cancel();
            // The session's working dir honors -C/--dir (the raw process CWD does not),
            // and jobs register their workspace-root ENTRY dir — resolve to it so Ctrl-C from a
            // member dir cancels the covering workspace build.
            java.nio.file.Path invocationDir;
            try {
                invocationDir = cc.jumpkick.config.SessionContext.current().workingDir();
            } catch (RuntimeException e) {
                invocationDir = null;
            }
            if (invocationDir == null) {
                invocationDir = java.nio.file.Path.of("").toAbsolutePath().normalize();
            }
            java.nio.file.Path dir =
                    cc.jumpkick.config.WorkspaceScan.findRoot(invocationDir).orElse(invocationDir);
            Thread rpc = Thread.ofPlatform()
                    .daemon(true)
                    .name("jk-sigint-cancel")
                    .start(() -> cc.jumpkick.cli.engine.EngineClient.cancelBestEffortForInterrupt(dir));

            // 2) Settle the live region (pipeline → cancelled job line) or a one-line notice.
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

            // 3) Give the cancel RPCs a short, bounded window (they also self-limit), then hard
            // kill this CLI process — guaranteed death even if everything above is wedged.
            try {
                rpc.join(3_000L);
            } catch (InterruptedException ignored) {
                // halt follows regardless
            }
            Runtime.getRuntime().halt(2);
        });
    }
}
