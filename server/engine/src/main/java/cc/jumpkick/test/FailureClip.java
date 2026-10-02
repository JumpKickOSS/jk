// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import org.jspecify.annotations.Nullable;

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
        if (lines.length <= MAX_STACK_LINES && stack.length() <= MAX_STACK_CHARS && shortLines(lines, MAX_LINE_CHARS)) {
            return stack;
        }
        return clip(lines, MAX_STACK_LINES, MAX_LINE_CHARS, -1);
    }

    /**
     * The first {@code maxLines} of {@code lines}, then the frame at {@code keep} and the one above
     * it when {@code keep} is past them, and every {@code Caused by:} header, with a count for each
     * run of lines left out; each line cut at {@code lineChars} and the whole cut at a line
     * boundary within {@link #MAX_STACK_CHARS}.
     */
    private static String clip(String[] lines, int maxLines, int lineChars, int keep) {
        if (lines.length <= maxLines && shortLines(lines, lineChars)) return String.join("\n", lines);
        StringBuilder sb = new StringBuilder();
        int kept = Math.min(lines.length, maxLines);
        for (int i = 0; i < kept; i++) {
            if (i > 0) sb.append('\n');
            sb.append(line(lines[i], lineChars));
        }
        int elided = 0;
        for (int i = kept; i < lines.length; i++) {
            boolean frame = keep >= 0 && (i == keep || i == keep - 1);
            if (frame || lines[i].strip().startsWith(CAUSED_BY)) {
                if (elided > 0) sb.append("\n\t… ").append(elided).append(" lines");
                elided = 0;
                sb.append('\n').append(line(lines[i], lineChars));
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

    /** Characters of a message the live event carries; {@code jk-results.md} shows one line of it. */
    public static final int EVENT_MESSAGE_CHARS = 1_024;

    /** Stack lines the live event keeps from the top, besides the test's own frame and the cause headers. */
    public static final int EVENT_STACK_LINES = 24;

    /** Characters of one stack line the live event carries. */
    public static final int EVENT_LINE_CHARS = 256;

    /**
     * {@code message} as the live test-failure event carries it: cut at {@link
     * #EVENT_MESSAGE_CHARS}. The summary and the reports keep {@link #message}.
     */
    public static String eventMessage(String message) {
        if (message.length() <= EVENT_MESSAGE_CHARS) return message;
        return cut(message, EVENT_MESSAGE_CHARS, JUnitLauncher.MESSAGE_TRUNCATION_MARKER);
    }

    /**
     * {@code stack} as the live test-failure event carries it: the first {@link
     * #EVENT_STACK_LINES} lines, the first frame of {@code testClass} and the one above it when
     * they lie further down, and every {@code Caused by:} header, each line cut at {@link
     * #EVENT_LINE_CHARS}.
     */
    public static String eventStack(String stack, @Nullable String testClass) {
        if (stack.isEmpty()) return stack;
        String[] lines = stack.split("\n", -1);
        int test = testFrame(lines, testClass);
        return clip(lines, EVENT_STACK_LINES, EVENT_LINE_CHARS, test >= EVENT_STACK_LINES ? test : -1);
    }

    private static boolean shortLines(String[] lines, int lineChars) {
        for (String line : lines) {
            if (line.length() > lineChars) return false;
        }
        return true;
    }

    private static String line(String line, int lineChars) {
        return line.length() <= lineChars ? line : cut(line, lineChars, LINE_TRUNCATION_MARKER);
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

    /**
     * Index of the first frame (top down) whose class is {@code testClass} or a nested class of it;
     * else the first frame in the test's package; else {@code -1}.
     */
    public static int testFrame(String[] lines, @Nullable String testClass) {
        if (testClass == null || testClass.isBlank()) return -1;
        int dot = testClass.lastIndexOf('.');
        String pkg = dot < 0 ? "" : testClass.substring(0, dot + 1);
        int inPackage = -1;
        for (int i = 0; i < lines.length; i++) {
            String cls = frameClass(lines[i]);
            if (cls == null) continue;
            if (cls.equals(testClass) || cls.startsWith(testClass + "$")) return i;
            if (inPackage < 0 && !pkg.isEmpty() && cls.startsWith(pkg)) inPackage = i;
        }
        return inPackage;
    }

    /**
     * The declaring class of an {@code at pkg.Class.method(File.java:NN)} line — module prefix
     * ({@code java.base/}) and class-loader prefix stripped — or {@code null} for any other line.
     */
    public static @Nullable String frameClass(String line) {
        String s = line.strip();
        if (!s.startsWith("at ")) return null;
        s = s.substring(3).strip();
        int paren = s.indexOf('(');
        if (paren > 0) s = s.substring(0, paren);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int method = s.lastIndexOf('.');
        return method <= 0 ? null : s.substring(0, method);
    }
}
