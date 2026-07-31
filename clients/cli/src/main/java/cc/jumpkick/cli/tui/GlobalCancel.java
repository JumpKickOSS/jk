// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import org.jline.utils.Signals;

/**
 * App-level SIGINT handler (JK-1252): cancel the live engine job through the same
 * {@code cancel-request} path as {@code jk cancel}, settle the TUI as cancelled, then hard-exit
 * the CLI ({@link Runtime#halt(int) halt(2)}) as a backup so Ctrl-C never hangs.
 *
 * <p>Order matters:
 *
 * <ol>
 *   <li>Cooperative session cancel + engine {@code cancel-request} (jid / project dir) — same
 *       kill path as the web UI and {@code jk cancel}
 *   <li>Settle the active pipeline region ("Build job was cancelled by user took …")
 *   <li>{@code halt(2)} — guaranteed process death if anything above is stuck
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
            // 1) Same cancel path as `jk cancel` / web — before we paint or die. Best-effort,
            // never spawns an engine, bounded by a short socket watchdog.
            cc.jumpkick.config.SessionContext.current().cancel().cancel();
            java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath().normalize();
            cc.jumpkick.cli.engine.EngineClient.cancelBestEffortForInterrupt(dir);

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

            // 3) Hard kill this CLI process — backup if the reader/engine path is wedged.
            Runtime.getRuntime().halt(2);
        });
    }
}
