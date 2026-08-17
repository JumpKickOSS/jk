// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.io.IOException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Interactive probes split: {@link #canPrompt()} (controlling TTY for input) vs {@link
 * #stdoutIsTty()} (animate stdout). {@code CI}/{@code JK_NONINTERACTIVE}/{@code TERM=dumb} force off.
 */
public final class Interactivity {

    private Interactivity() {}

    // canPrompt() builds a JLine system terminal to probe the controlling terminal, which is not
    // free and emits capability queries — cache the verdict for the life of the process.
    private static volatile Boolean canPromptCache;

    // The single real system terminal built by the canPrompt() probe, kept OPEN so the wizard /
    // confirm UI can reuse it instead of building (and closing) a second one. JLine's system
    // terminal owns native FD 0; closing it closes FD 0, so a second system(true) build would fall
    // back to a dumb terminal whose reader throws "Stream Closed". Guarded by Interactivity.class;
    // cleared by takeSharedTerminal() once a caller assumes ownership (and the duty to close it).
    private static Terminal sharedTerminal;

    /** {@code true} when {@code CI} or {@code JK_NONINTERACTIVE} is set, or {@code TERM=dumb}. */
    private static boolean forcedNonInteractive() {
        if (System.getenv("CI") != null) return true;
        String nonInteractive = System.getenv("JK_NONINTERACTIVE");
        if (nonInteractive != null && !nonInteractive.isBlank()) return true;
        return "dumb".equals(System.getenv("TERM"));
    }

    /**
     * Whether jk can prompt a human — the <em>input</em> axis. False if forced non-interactive; else
     * true iff the controlling terminal is reachable. We probe it the same way the prompt UI does
     * ({@code TerminalBuilder.system(true)}, mirroring {@link Wizard#openTerminal()}) so this can
     * never disagree with whether a wizard/confirm would actually work — including under GraalVM
     * native-image, where a hand-rolled {@code /dev/tty} open might behave differently than JLine's
     * provider. {@code dumb(true)} makes {@code build()} fall back to a dumb terminal (which we then
     * reject) instead of throwing when there is no tty. Independent of stdin/stdout redirection, so
     * {@code jk foo | less} still counts as promptable.
     */
    public static boolean canPrompt() {
        Boolean cached = canPromptCache;
        if (cached != null) return cached;
        return computeAndCache();
    }

    private static synchronized boolean computeAndCache() {
        if (canPromptCache != null) return canPromptCache; // another thread won the race
        boolean result = computeCanPrompt();
        canPromptCache = result;
        return result;
    }

    private static boolean computeCanPrompt() {
        if (forcedNonInteractive()) return false;
        Terminal probe = null;
        try {
            // system(true): bind to the controlling terminal, not our (maybe-piped) stdio.
            // dumb(true): fall back to a dumb terminal instead of throwing when none exists.
            // graphemeCluster(false): skip JLine's mode-2027 grapheme probe (a DECRQM + DA1 query
            //   with a 200ms response timeout). On a slow/cold terminal (WSL) the reply lands after
            //   the timeout, once JLine has restored ECHO, so the tty echoes it as an ANSI flash. jk
            //   renders only single-codepoint glyphs, so grapheme-cluster width mode is unused — the
            //   probe is pure cost. Disabling it removes the flash and speeds up terminal open.
            // nativeSignals(false): JLine's default is SIG_DFL for INT/TERM/…, which
            // overwrites {@link GlobalCancel}'s pretty Ctrl-C handler. Wizards that need
            // JLine to own SIGINT call {@code terminal.handle} themselves.
            probe = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)
                    .graphemeCluster(false)
                    .nativeSignals(false)
                    .build();
            GlobalCancel.install();
            String type = probe.getType();
            if (Terminal.TYPE_DUMB.equals(type) || Terminal.TYPE_DUMB_COLOR.equals(type)) {
                probe.close(); // a dumb terminal owns nothing worth reusing
                return false;
            }
            // A real system terminal. build() just emitted capability queries (DA / DECRQM) whose
            // responses are about to arrive on the tty: suppress ECHO now, before they land, so the
            // driver doesn't paint them to the screen as ANSI noise, then drain them so they aren't
            // later read as phantom keystrokes.
            //
            // Crucially we do NOT close this probe. JLine's system terminal owns native FD 0, and
            // closing it closes FD 0 — the next system(true) build would fail to make a system
            // terminal (falling back to a dumb one whose reader throws "Stream Closed"). Cache it
            // and hand this exact terminal to Wizard.openTerminal(), so the whole process opens
            // exactly one system terminal.
            Attributes saved = probe.getAttributes();
            Attributes noEcho = new Attributes(saved);
            noEcho.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            probe.setAttributes(noEcho);
            Wizard.drainInput(probe.reader(), 40L);
            sharedTerminal = probe;
            installRestoreHook(probe, saved);
            return true;
        } catch (IOException | RuntimeException e) {
            if (probe != null) {
                try {
                    probe.close();
                } catch (IOException ignored) {
                    // best-effort close of the probe terminal
                }
            }
            return false; // no reachable terminal → can't prompt
        }
    }

    /**
     * Transfer ownership of the cached system terminal (built and drained by {@link #canPrompt()},
     * with ECHO already suppressed) to the caller, which becomes responsible for closing it. Returns
     * {@code null} when there is nothing to reuse — the caller then builds its own. Cleared on
     * handoff so the terminal is handed out exactly once and the restore hook stands down.
     */
    static synchronized Terminal takeSharedTerminal() {
        Terminal t = sharedTerminal;
        sharedTerminal = null;
        return t;
    }

    /**
     * Give a taken system terminal back for reuse instead of closing it. JLine's system terminal
     * owns native FD 0 — closing it mid-process closes stdin for everything that follows in the
     * same invocation: {@code jk run}'s {@code inheritIO()} subprocess, a wizard/confirm after a
     * plan, the next plan's Ctrl-O listener. Callers restore tty attributes (cooked) BEFORE
     * returning. The original restore hook still guards this instance at shutdown
     * ({@code sharedTerminal == terminal} holds again after the return). Never closes {@code t}.
     */
    static synchronized void returnSharedTerminal(Terminal t) {
        if (t == null || sharedTerminal == t) return;
        if (sharedTerminal == null) sharedTerminal = t;
        // else: a different shared terminal exists (should not happen — one system terminal per
        // process); leave both open rather than close an FD-0 owner.
    }

    /**
     * Guarantee the probe terminal's tty attributes (notably ECHO) are restored if no caller ever
     * takes and closes it. Restores attributes rather than calling {@code close()} to avoid JLine's
     * close blocking on its reader thread during shutdown; a taken terminal is closed by its owner,
     * at which point {@code sharedTerminal != terminal} makes this a no-op.
     */
    private static void installRestoreHook(Terminal terminal, Attributes saved) {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            synchronized (Interactivity.class) {
                                if (sharedTerminal == terminal) {
                                    terminal.setAttributes(saved);
                                    terminal.flush();
                                }
                            }
                        },
                        "jk-terminal-restore"));
    }

    /**
     * Whether stdout is an interactive terminal — the <em>output</em> axis for animation / live
     * regions. Keyed on {@link System#console()} (non-null ⇒ stdout is a tty) so a redirected or
     * piped stdout ({@code | less}, {@code > file}, CI logs) draws plain text and never leaks
     * cursor-movement ANSI into the stream. Deliberately does <em>not</em> consult the controlling
     * terminal — that is {@link #canPrompt()}'s job.
     */
    public static boolean stdoutIsTty() {
        return System.console() != null && !forcedNonInteractive();
    }
}
