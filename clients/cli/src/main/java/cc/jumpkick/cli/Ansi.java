// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

/**
 * Centralized ANSI escape-sequence primitives for the whole {@code :cli} module.
 *
 * <p>This class owns the raw control-sequence mechanics — the {@link #ESC} and {@link #BEL}
 * characters, the CSI/OSC introducers, the {@link #ST} terminator, the SGR {@link #RESET}, cursor
 * movement, line erasure, OSC&nbsp;8 hyperlinks, and OSC&nbsp;9;4 taskbar progress. Nothing here
 * knows about <em>colors</em>: color choices live in the theme layer (see {@code
 * cc.jumpkick.cli.theme.Theme}), which uses {@link #RESET} (and the SGR helpers) to emit styled
 * text.
 *
 * <p>Keeping every escape in one place means a single audited definition of {@code "\033[K"},
 * {@code "\033]9;4;…\007"}, etc., instead of the literals that used to be scattered across the help
 * renderer, the progress bars, the spinner, the wizard, and individual commands.
 */
public final class Ansi {

    private Ansi() {}

    /** Escape, {@code U+001B}. The lead byte of every CSI/OSC/SS sequence. */
    public static final char ESC = '\033';

    /** Bell, {@code U+0007}. Doubles as the legacy OSC string terminator. */
    public static final char BEL = '\007';

    /** Control Sequence Introducer: {@code ESC [}. */
    public static final String CSI = ESC + "[";

    /** Operating System Command introducer: {@code ESC ]}. */
    public static final String OSC = ESC + "]";

    /** String Terminator: {@code ESC \}. The spec-correct closer for OSC strings. */
    public static final String ST = ESC + "\\";

    /** SGR reset — clears all colors and attributes. */
    public static final String RESET = CSI + "0m";

    // --- SGR (color/attribute) mechanics ---------------------------------
    // The theme layer decides WHICH colors; these just assemble the sequence.

    /** Wrap an SGR parameter body (e.g. {@code "1;38;2;0;188;212"}) into a full {@code ESC[…m}. */
    public static String sgr(String body) {
        return CSI + body + "m";
    }

    /**
     * Truecolor foreground SGR body, e.g. {@code "38;2;r;g;b"} (no leading {@code ESC[}, no trailing
     * {@code m}).
     */
    public static String fgBody(int r, int g, int b) {
        return "38;2;" + r + ";" + g + ";" + b;
    }

    /** Bold + truecolor foreground SGR body, e.g. {@code "1;38;2;r;g;b"}. */
    public static String boldFgBody(int r, int g, int b) {
        return "1;" + fgBody(r, g, b);
    }

    // --- Cursor visibility & movement ------------------------------------

    /** Hide the cursor (DECTCEM). */
    public static final String HIDE_CURSOR = CSI + "?25l";

    /** Show the cursor (DECTCEM). */
    public static final String SHOW_CURSOR = CSI + "?25h";

    /** Erase from the cursor to the end of the current line (EL 0). */
    public static final String ERASE_LINE_TO_END = CSI + "K";

    /** Erase from the cursor to the end of the display (ED 0). */
    public static final String ERASE_DISPLAY_TO_END = CSI + "0J";

    /** Carriage return followed by erase-to-end — the common "redraw this line" prefix. */
    public static final String CLEAR_LINE = "\r" + ERASE_LINE_TO_END;

    /** Move the cursor up {@code n} rows (CUU). */
    public static String cursorUp(int n) {
        return CSI + n + "A";
    }

    /** Move the cursor down {@code n} rows (CUD). */
    public static String cursorDown(int n) {
        return CSI + n + "B";
    }

    /** Move the cursor forward {@code n} columns (CUF). */
    public static String cursorForward(int n) {
        return CSI + n + "C";
    }

    /** Move the cursor back {@code n} columns (CUB). */
    public static String cursorBack(int n) {
        return CSI + n + "D";
    }

    /** Move the cursor to absolute column {@code col}, 1-based (CHA). */
    public static String cursorToColumn(int col) {
        return CSI + col + "G";
    }

    /** Move the cursor up {@code n} lines and to column 1 (CPL). */
    public static String cursorPrevLine(int n) {
        return CSI + n + "F";
    }

    // --- OSC 8 hyperlinks -------------------------------------------------

    /**
     * Wrap {@code text} in an OSC&nbsp;8 hyperlink to {@code url}. Terminals that don't support it
     * render {@code text} plainly. Uses {@link #BEL} as the terminator (the widely-supported form).
     */
    public static String hyperlink(String url, String text) {
        return OSC + "8;;" + url + BEL + text + OSC + "8;;" + BEL;
    }

    // --- OSC 9;4 taskbar progress (ConEmu / Windows Terminal / WezTerm / kitty) ---

    /** Set determinate taskbar progress to {@code percent} (0–100). Empty when OSC is disabled. */
    public static String taskbarProgress(int percent) {
        if (!oscEnabled()) return "";
        return OSC + "9;4;1;" + percent + BEL;
    }

    /** Set the taskbar to the indeterminate (busy) state. Empty when OSC is disabled. */
    public static String taskbarIndeterminate() {
        return oscEnabled() ? OSC + "9;4;3" + BEL : "";
    }

    /**
     * Indeterminate taskbar progress (legacy constant form). Prefer {@link #taskbarIndeterminate()}
     * so {@code --no-osc} is honored; this field is the raw sequence for tests that assert bytes.
     */
    public static final String TASKBAR_INDETERMINATE = OSC + "9;4;3" + BEL;

    /** Clear any taskbar progress indicator. Empty when OSC is disabled. */
    public static String taskbarClear() {
        return oscEnabled() ? OSC + "9;4;0" + BEL : "";
    }

    /** Clear taskbar progress (raw sequence; see {@link #taskbarClear()}). */
    public static final String TASKBAR_CLEAR = OSC + "9;4;0" + BEL;

    // --- OSC 0 window title -------------------------------------------------

    /**
     * Set the terminal window/tab title via OSC&nbsp;0. Terminals that ignore OSC 0 no-op; empty
     * {@code title} clears the title. Terminated with {@link #ST} ({@code ESC \}) per ECMA-48 /
     * XTerm OSC, not legacy BEL. Control characters that would break the OSC string are stripped.
     */
    public static String windowTitle(String title) {
        if (!oscEnabled()) return "";
        String t = title == null ? "" : title;
        // OSC text must not contain BEL, ESC, or ST (would terminate / nest sequences).
        t = t.replace("\007", "").replace("\033", "").replace('\n', ' ').replace('\r', ' ');
        return OSC + "0;" + t + ST;
    }

    /** Clear the terminal window title (empty OSC 0 + ST). Empty when OSC is disabled. */
    public static String windowTitleClear() {
        return oscEnabled() ? OSC + "0;" + ST : "";
    }

    /** Clear window title (raw sequence; see {@link #windowTitleClear()}). */
    public static final String WINDOW_TITLE_CLEAR = OSC + "0;" + ST;

    // --- OSC 99 desktop notifications (kitty / ghostty / conforming terminals) ---

    /**
     * Whether OSC sequences may be emitted. False when {@code --no-osc} is set (or the session
     * config carries {@code noOsc}). Independent of color / {@code --no-ansi}.
     */
    public static boolean oscEnabled() {
        return !cc.jumpkick.config.SessionContext.current().config().noOscOr(false);
    }

    /**
     * OSC&nbsp;99 desktop notification with a title and body (kitty protocol). Terminals that ignore
     * OSC 99 no-op. Payload is sanitized to escape-code-safe UTF-8 (no C0 controls). Uses a two-chunk
     * form: title first ({@code d=0}), then body with {@code d=1} so the notification is complete.
     *
     * <p>Returns empty string when OSC is disabled so callers can print unconditionally.
     */
    public static String desktopNotify(String title, String body) {
        if (!oscEnabled()) return "";
        String t = oscSafe(title);
        String b = oscSafe(body);
        if (t.isEmpty() && b.isEmpty()) return "";
        // i=jk: fixed id; d=0 holds title until body chunk completes the notification.
        String titleChunk = OSC + "99;i=jk:d=0;" + t + ST;
        String bodyChunk = OSC + "99;i=jk:d=1:p=body;" + b + ST;
        return titleChunk + bodyChunk;
    }

    /** Strip C0/C1 controls that would break an OSC string; collapse newlines to spaces. */
    static String oscSafe(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                // drop C0 / DEL / C1
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
