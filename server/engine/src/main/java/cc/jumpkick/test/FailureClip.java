// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

/**
 * The one bound on a test failure's message and stack, applied where the engine first reads a
 * failure, so the summary, the live event, the journal and the test reports all hold the same
 * clipped text. A failure shows a few dozen frames and its cause chain; that is what the clip keeps.
 *
 * <p>Every cut is marked: {@link JUnitLauncher#MESSAGE_TRUNCATION_MARKER} ends a clipped message,
 * {@link #LINE_TRUNCATION_MARKER} a clipped stack line and {@link
 * JUnitLauncher#STACK_TRUNCATION_MARKER} a clipped stack, so secret redaction can mask a value the
 * cut split.
 */
public final class FailureClip {

    /** Characters of a failure message kept; the test runner caps its field at the same size. */
    public static final int MAX_MESSAGE_CHARS = 4_096;

    /** Stack lines kept from the top of a trace before the clip keeps only its cause headers. */
    public static final int MAX_STACK_LINES = 64;

    /** Characters of one stack line kept: a header line repeats the whole message. */
    public static final int MAX_LINE_CHARS = 512;

    /** Characters of a stack kept after the line clip. */
    public static final int MAX_STACK_CHARS = 16_384;

    /** Marks a stack line cut at {@link #MAX_LINE_CHARS}, before its remainder count. */
    public static final String LINE_TRUNCATION_MARKER = " ... line truncated (";

    private static final String MORE = " more chars)";
    private static final String CAUSED_BY = "Caused by: ";

    private FailureClip() {}

    /**
     * {@code message} cut at {@link #MAX_MESSAGE_CHARS} with its remainder count. A message the test
     * runner already cut in this form passes through with its own count.
     */
    public static String message(String message) {
        if (message.length() <= MAX_MESSAGE_CHARS || runnerCapped(message)) return message;
        return cut(message, MAX_MESSAGE_CHARS, JUnitLauncher.MESSAGE_TRUNCATION_MARKER);
    }

    /**
     * The first {@link #MAX_STACK_LINES} lines of {@code stack}, then every {@code Caused by:} header
     * past them so the innermost cause survives, each line cut at {@link #MAX_LINE_CHARS}, and the
     * whole cut at a line boundary within {@link #MAX_STACK_CHARS}.
     */
    public static String stack(String stack) {
        if (stack.isEmpty()) return stack;
        String[] lines = stack.split("\n", -1);
        if (lines.length <= MAX_STACK_LINES && stack.length() <= MAX_STACK_CHARS && shortLines(lines)) return stack;
        StringBuilder sb = new StringBuilder();
        int kept = Math.min(lines.length, MAX_STACK_LINES);
        for (int i = 0; i < kept; i++) {
            if (i > 0) sb.append('\n');
            sb.append(line(lines[i]));
        }
        int elided = 0;
        for (int i = kept; i < lines.length; i++) {
            if (lines[i].strip().startsWith(CAUSED_BY)) {
                if (elided > 0) sb.append("\n\t… ").append(elided).append(" lines");
                elided = 0;
                sb.append('\n').append(line(lines[i]));
            } else if (!lines[i].isEmpty()) {
                elided++;
            }
        }
        if (elided > 0) sb.append("\n\t… ").append(elided).append(" lines");
        if (sb.length() <= MAX_STACK_CHARS) return sb.toString();
        int at = sb.lastIndexOf("\n", MAX_STACK_CHARS);
        if (at <= 0) at = MAX_STACK_CHARS;
        return sb.substring(0, at) + JUnitLauncher.STACK_TRUNCATION_MARKER + (sb.length() - at) + MORE;
    }

    private static boolean shortLines(String[] lines) {
        for (String line : lines) {
            if (line.length() > MAX_LINE_CHARS) return false;
        }
        return true;
    }

    private static String line(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : cut(line, MAX_LINE_CHARS, LINE_TRUNCATION_MARKER);
    }

    private static String cut(String text, int max, String marker) {
        int at = max;
        // A lone high surrogate at the cut would encode as an unpaired code unit.
        if (Character.isHighSurrogate(text.charAt(at - 1))) at--;
        return text.substring(0, at) + marker + (text.length() - at) + MORE;
    }

    /**
     * A runner-capped message is cap-sized content, the marker and a remainder count. Re-cutting it
     * would replace the runner's count with the marker's own length.
     */
    private static boolean runnerCapped(String message) {
        if (!message.endsWith(MORE)) return false;
        int at = message.lastIndexOf(JUnitLauncher.MESSAGE_TRUNCATION_MARKER);
        if (at < 0 || at > MAX_MESSAGE_CHARS) return false;
        int digitsFrom = at + JUnitLauncher.MESSAGE_TRUNCATION_MARKER.length();
        int digitsTo = message.length() - MORE.length();
        if (digitsTo <= digitsFrom) return false;
        for (int i = digitsFrom; i < digitsTo; i++) {
            char c = message.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
