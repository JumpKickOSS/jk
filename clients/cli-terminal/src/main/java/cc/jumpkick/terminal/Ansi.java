// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

/**
 * CSI/OSC constructors. Always returns the sequence — OSC policy stays in {@code :cli} as
 * {@code Osc}. No {@code SessionContext}.
 */
public final class Ansi {
    private Ansi() {}

    public static final char ESC = '\033';
    public static final char BEL = '\007';
    public static final String CSI = ESC + "[";
    public static final String OSC = ESC + "]";
    public static final String ST = ESC + "\\";
    public static final String RESET = CSI + "0m";
    public static final String HIDE_CURSOR = CSI + "?25l";
    public static final String SHOW_CURSOR = CSI + "?25h";
    public static final String ERASE_LINE_TO_END = CSI + "K";
    public static final String ERASE_DISPLAY_TO_END = CSI + "0J";
    public static final String CLEAR_LINE = "\r" + ERASE_LINE_TO_END;
    public static final String TASKBAR_INDETERMINATE = OSC + "9;4;3" + BEL;
    public static final String TASKBAR_CLEAR = OSC + "9;4;0" + BEL;
    public static final String WINDOW_TITLE_CLEAR = OSC + "0;" + ST;

    public static String sgr(String body) {
        return CSI + body + "m";
    }

    public static String fgBody(int r, int g, int b) {
        return "38;2;" + r + ";" + g + ";" + b;
    }

    public static String boldFgBody(int r, int g, int b) {
        return "1;" + fgBody(r, g, b);
    }

    public static String cursorUp(int n) {
        return CSI + n + "A";
    }

    public static String cursorDown(int n) {
        return CSI + n + "B";
    }

    public static String cursorForward(int n) {
        return CSI + n + "C";
    }

    public static String cursorBack(int n) {
        return CSI + n + "D";
    }

    public static String cursorToColumn(int col) {
        return CSI + col + "G";
    }

    public static String cursorPrevLine(int n) {
        return CSI + n + "F";
    }

    public static String hyperlink(String url, String text) {
        return OSC + "8;;" + url + BEL + text + OSC + "8;;" + BEL;
    }

    public static String taskbarProgress(int percent) {
        return OSC + "9;4;1;" + percent + BEL;
    }

    public static String taskbarIndeterminate() {
        return TASKBAR_INDETERMINATE;
    }

    public static String taskbarClear() {
        return TASKBAR_CLEAR;
    }

    public static String windowTitle(String title) {
        String t = title == null ? "" : title;
        t = t.replace("\007", "").replace("\033", "").replace('\n', ' ').replace('\r', ' ');
        return OSC + "0;" + t + ST;
    }

    public static String windowTitleClear() {
        return WINDOW_TITLE_CLEAR;
    }

    public static String desktopNotify(String title, String body) {
        String t = oscSafe(title);
        String b = oscSafe(body);
        if (t.isEmpty() && b.isEmpty()) {
            return "";
        }
        // Match :cli Ansi: kitty OSC 99, i=jk, ST terminator. Always emit (policy is not here).
        String titleChunk = OSC + "99;i=jk:d=0;" + t + ST;
        String bodyChunk = OSC + "99;i=jk:d=1:p=body;" + b + ST;
        return titleChunk + bodyChunk;
    }

    static String oscSafe(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
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
