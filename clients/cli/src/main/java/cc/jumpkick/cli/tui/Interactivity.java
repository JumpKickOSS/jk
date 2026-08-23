// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.WriterOutputStream;

/**
 * Interactive probes split: {@link #canPrompt()} (controlling TTY for input) vs {@link
 * #stdoutIsTty()} (animate stdout). {@code CI}/{@code JK_NONINTERACTIVE}/{@code TERM=dumb} force off.
 *
 * <p>Windows glyph/CSI correctness is owned by {@link WindowsUtf8} (console CP 65001 + UTF-8
 * {@link System#out}). {@link #installAnsiTerminalStreams(String[])} opens the shared JLine system
 * terminal for wizards / VTP fallback; it does <em>not</em> re-wrap {@link System#out} once
 * {@link WindowsUtf8} has run. The WriterOutputStream→{@code writer()} bridge remains only as a
 * fallback when UTF-8 console enablement did not apply.
 *
 * <p>Do not wrap {@link Terminal#output()} with a PrintStream using {@link Terminal#encoding()}:
 * JLine may bind {@code output()} to the console OEM code page (CP437/…) while Java string writes
 * are UTF-8, which decodes pulse glyphs like {@code ●} into mojibake ({@code ΓùÅ}).
 */
public final class Interactivity {

    private Interactivity() {}

    /** UTF-8 bridge for {@link System#out} → {@link Terminal#writer()} (WriteConsoleW on Windows). */
    static final Charset ANSI_STDOUT_CHARSET = StandardCharsets.UTF_8;

    // canPrompt() builds a JLine system terminal to probe the controlling terminal, which is not
    // free and emits capability queries — cache the verdict for the life of the process.
    private static volatile Boolean canPromptCache;

    // The single real system terminal built by the canPrompt() probe, kept OPEN so the wizard /
    // confirm UI can reuse it instead of building (and closing) a second one. JLine's system
    // terminal owns native FD 0; closing it closes FD 0, so a second system(true) build would fall
    // back to a dumb terminal whose reader throws "Stream Closed". Guarded by Interactivity.class;
    // cleared by takeSharedTerminal() once a caller assumes ownership (and the duty to close it).
    private static Terminal sharedTerminal;
    private static Attributes sharedSaved;

    /** True after {@link #installAnsiTerminalStreams(String[])} has replaced {@link System#out}. */
    private static boolean ansiStreamsInstalled;

    /**
     * Strong ref to the terminal whose {@link Terminal#output()} backs the replaced {@link
     * System#out}. Survives {@link #takeSharedTerminal()} clearing {@link #sharedTerminal}.
     */
    private static Terminal ansiStreamsTerminal;

    /** {@code saved} with only ECHO suppressed — canonical mode and VMIN/VTIME are preserved. */
    static Attributes quietAttributes(Attributes saved) {
        Attributes quiet = new Attributes(saved);
        quiet.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        return quiet;
    }

    /**
     * Full attribute restore (cooked, ECHO back on) for the still-owned shared terminal, before
     * handing FD 0 to an {@code inheritIO()} child — the probe leaves ECHO off, which would make
     * the child's interactive input invisible (JK-2164). No-op when nothing is owned; later jk
     * prompts re-raw the terminal themselves.
     */
    public static synchronized void restoreForChildProcess() {
        if (sharedTerminal == null || sharedSaved == null) return;
        restoreOwnedAttributes(sharedTerminal, sharedSaved);
    }

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
            // encoding(UTF-8): do not inherit the Windows OEM console code page for the
            // terminal's byte bridges — WriteConsoleW is Unicode; OEM CP poisons glyphs.
            probe = systemTerminalBuilder().dumb(true).build();
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
            // Timed drain leaves JLine's NonBlocking I/O thread blocked on stdin; wake it so a
            // later System.exit (JLine closer) does not hang on macOS when no key listener runs.
            Wizard.unblockBlockingInput(probe);
            // Back to the SAVED (cooked) attrs with only ECHO off — copying the post-unblock
            // attrs kept ICANON off/VMIN=0 for the rest of the process, breaking stdin for
            // probe-without-prompt paths and inheritIO children (JK-2164).
            probe.setAttributes(quietAttributes(saved));
            sharedTerminal = probe;
            sharedSaved = saved;
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
                .addShutdownHook(new Thread(() -> restoreOwnedAttributes(terminal, saved), "jk-terminal-restore"));
    }

    /**
     * Best-effort attribute restore for the still-owned shared terminal. JLine registers its own
     * closer on a system terminal; that hook and this one run concurrently at JVM exit, and
     * {@link Terminal#setAttributes} / {@link Terminal#flush} throw {@link IllegalStateException}
     * once the terminal is closed. Swallowing keeps a successful command from dumping
     * {@code Exception in thread "jk-terminal-restore"} — JLine's closer already restored the
     * original attributes.
     */
    static void restoreOwnedAttributes(Terminal terminal, Attributes saved) {
        synchronized (Interactivity.class) {
            if (sharedTerminal != terminal) return;
            try {
                // Same macOS hang as restoreCooked: wake NonBlocking stdin before cooked restore.
                Wizard.restoreCooked(terminal, saved);
            } catch (RuntimeException ignored) {
                // JLine closer already shut the terminal, or the tty vanished under us.
            }
        }
    }

    /**
     * Best-effort pre-exit cleanup for the shared system terminal. Unblocks any stuck JLine stdin
     * reader, restores cooked attributes, and closes the terminal so JLine's shutdown closer is
     * deregistered (that closer otherwise hangs on macOS until Enter when a NonBlocking {@code
     * read} is still pending). Safe when no terminal was opened. FD 0 close at process exit is
     * intentional — nothing after {@code System.exit} needs stdin.
     */
    public static void prepareProcessExit() {
        Terminal t;
        synchronized (Interactivity.class) {
            t = sharedTerminal;
            sharedTerminal = null;
        }
        if (t == null) {
            // No shared slot, but a NonBlocking read may still be pending on FD 0 from an earlier
            // take that was never returned — pulse O_NONBLOCK so System.exit's JLine closer can
            // finish if that terminal is still registered with ShutdownHooks.
            StdinWake.pulseNonBlocking();
            return;
        }
        try {
            // restoreCooked wakes the NonBlocking I/O thread (VMIN=0 + O_NONBLOCK) before ICANON.
            Wizard.restoreCooked(t, t.getAttributes());
            // One more pulse after cooked restore in case a late timed-read re-blocked.
            StdinWake.pulseNonBlocking();
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            t.close(); // removes JLine ShutdownHooks closer once the reader is no longer in read()
        } catch (Exception ignored) {
            // shutting down — ignore; StdinWake already ran inside restoreCooked
            try {
                StdinWake.pulseNonBlocking();
            } catch (RuntimeException ignored2) {
                // ignore
            }
        }
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

    /**
     * Shared {@link TerminalBuilder} options for jk's one system terminal: UTF-8 encodings (avoid
     * Windows OEM code-page auto-detect), no grapheme probe, no JLine native signal takeover.
     */
    static TerminalBuilder systemTerminalBuilder() {
        return TerminalBuilder.builder()
                .system(true)
                .encoding(ANSI_STDOUT_CHARSET)
                .stdinEncoding(ANSI_STDOUT_CHARSET)
                .stdoutEncoding(ANSI_STDOUT_CHARSET)
                .graphemeCluster(false)
                .nativeSignals(false);
    }

    /**
     * {@link PrintStream} that UTF-8-encodes string writes into a {@link WriterOutputStream} over
     * {@code t.writer()}. Round-trips Unicode to WriteConsoleW on Windows even when JLine's own
     * {@link Terminal#output()} was built against an OEM code page.
     */
    static PrintStream newAnsiStdoutStream(Terminal t) {
        return new PrintStream(new WriterOutputStream(t.writer(), ANSI_STDOUT_CHARSET), true, ANSI_STDOUT_CHARSET);
    }

    /**
     * Prepare interactive ANSI stdout. Idempotent. No-op when stdout is not a TTY, ANSI is off,
     * quiet, or primary stdout is machine JSON.
     *
     * <p>When {@link WindowsUtf8} already enabled the UTF-8 console, leave {@link System#out} alone
     * (do not re-bridge through JLine — that path was the mojibake failure mode) and only open the
     * shared system terminal for wizards / VTP. Otherwise fall back to a UTF-8 PrintStream over
     * {@link Terminal#writer()} ({@code WriteConsoleW} on Windows).
     *
     * @param args raw or rewritten argv (for {@code --output} detection); may be null
     * @return {@code true} when interactive ANSI stdout is ready
     */
    public static synchronized boolean installAnsiTerminalStreams(String[] args) {
        if (ansiStreamsInstalled) return true;
        if (!stdoutIsTty()) return false;
        if (!Theme.colorEnabled()) return false;
        if (cc.jumpkick.config.SessionContext.current().config().quietOr(false)) return false;
        if (jsonStdoutRequested(args)) return false;
        // Shared system terminal: wizards + VTP. Safe even when WindowsUtf8 already set CP_UTF8.
        if (!canPrompt()) return false;
        Terminal t = sharedTerminal;
        if (t == null) return false;
        if (WindowsUtf8.isEnabled()) {
            // Console is UTF-8; keep the FileDescriptor-backed UTF-8 System.out from WindowsUtf8.
            ansiStreamsTerminal = t;
            ansiStreamsInstalled = true;
            return true;
        }
        System.setOut(newAnsiStdoutStream(t));
        ansiStreamsTerminal = t; // strong ref; survives takeSharedTerminal()
        ansiStreamsInstalled = true;
        return true;
    }

    /** {@code true} after a successful {@link #installAnsiTerminalStreams(String[])}. */
    public static boolean ansiTerminalStreamsInstalled() {
        return ansiStreamsInstalled;
    }

    /**
     * Test-only: forget the install flag and drop the strong terminal ref. Does not restore the
     * primordial {@link System#out} (tests inject their own streams).
     */
    static synchronized void resetAnsiTerminalStreams() {
        ansiStreamsInstalled = false;
        ansiStreamsTerminal = null;
    }

    /** {@code --output json|jsonl}, {@code -O json|jsonl}, or {@code JK_OUTPUT=json|jsonl}. */
    static boolean jsonStdoutRequested(String[] args) {
        String env = System.getenv("JK_OUTPUT");
        if (isJsonOutputToken(env)) return true;
        if (args == null) return false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--output".equals(a) || "-O".equals(a)) {
                if (i + 1 < args.length && isJsonOutputToken(args[++i])) return true;
            } else if (a.startsWith("--output=")) {
                if (isJsonOutputToken(a.substring("--output=".length()))) return true;
            } else if (a.startsWith("-O=") && isJsonOutputToken(a.substring(3))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJsonOutputToken(String value) {
        if (value == null || value.isBlank()) return false;
        String v = value.trim();
        return v.equalsIgnoreCase("json") || v.equalsIgnoreCase("jsonl");
    }
}
