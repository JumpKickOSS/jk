// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.terminal.Terminals;

/**
 * Interactive probes split: {@link #canPrompt()} (controlling TTY for input) vs {@link
 * #stdoutIsTty()} (animate stdout). {@code CI}/{@code JK_NONINTERACTIVE}/{@code TERM=dumb} force off.
 *
 * <p>The controlling TTY lives in {@link Terminals}. This class is the {@code :cli} policy layer
 * (forced-noninteractive env) on top of that leaf.
 */
public final class Interactivity {

    private Interactivity() {}

    private static volatile Boolean canPromptCache;

    /** {@code true} when {@code CI} or {@code JK_NONINTERACTIVE} is set, or {@code TERM=dumb}. */
    private static boolean forcedNonInteractive() {
        if (System.getenv("CI") != null) {
            return true;
        }
        String nonInteractive = System.getenv("JK_NONINTERACTIVE");
        if (nonInteractive != null && !nonInteractive.isBlank()) {
            return true;
        }
        return "dumb".equals(System.getenv("TERM"));
    }

    /**
     * Whether jk can prompt a human — the <em>input</em> axis. Independent of stdin/stdout
     * redirection, so {@code jk foo | less} still counts as promptable.
     */
    public static boolean canPrompt() {
        Boolean cached = canPromptCache;
        if (cached != null) {
            return cached;
        }
        synchronized (Interactivity.class) {
            if (canPromptCache != null) {
                return canPromptCache;
            }
            boolean result = !forcedNonInteractive() && Terminals.controllingIsTty();
            canPromptCache = result;
            return result;
        }
    }

    /**
     * Whether stdout is an interactive terminal — the <em>output</em> axis for animation / live
     * regions. Prefers {@code isatty(1)} / console-mode; {@code System.console()} only when FFM is
     * unavailable. Forced-noninteractive still wins.
     */
    public static boolean stdoutIsTty() {
        return Terminals.stdoutIsTty() && !forcedNonInteractive();
    }

    /** Restore original attrs before an {@code inheritIO} child. No-op if no session was opened. */
    public static void restoreForChildProcess() {
        Terminals.restoreForChild();
    }

    /** Restore + close native fds. Safe when no session was opened. */
    public static void prepareProcessExit() {
        Terminals.shutdown();
    }
}
