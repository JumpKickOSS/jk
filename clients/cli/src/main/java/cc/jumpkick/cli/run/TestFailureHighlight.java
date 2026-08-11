// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Badge;
import cc.jumpkick.config.GlobalConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.utils.AttributedStyle;

/**
 * Styles the plain-text test-failure block emitted by the engine ({@code TestSupport.renderFailures})
 * for human terminals. JSON / machine modes never see this paint — only the live/deferred console
 * path.
 *
 * <p>Shape (plain, before paint):
 *
 * <pre>
 * Test Failure
 * 1 test failed:
 *
 *   FAILED  group:artifact :: method()
 *     class: fqcn
 *     java.lang.AssertionError
 *
 * Expecting actual:
 *   21670L
 * to be between:
 *   [28000L, 45000L]
 * </pre>
 *
 * <p>Painted: red {@code Test} pill + "Failure", heavy-red rail, coords / FQCNs / methods syntax-
 * highlighted, assertion actuals in red and expected values in green.
 */
public final class TestFailureHighlight {

    /** Engine sentinel title — must stay byte-identical to {@code TestSupport.renderFailures}. */
    public static final String HEADER_SENTINEL = "Test Failure";

    /** Heavy vertical box-drawing used as the failure rail (U+2503). */
    public static final String RAIL = "┃";

    private static final Pattern FAILED_LINE = Pattern.compile("^(?<indent>[ \\t]*)FAILED  (?<rest>.+)$");
    private static final Pattern CLASS_LINE = Pattern.compile("^(?<indent>[ \\t]*)class: (?<fqcn>.+)$");
    private static final Pattern FQCN_LINE =
            Pattern.compile("^(?<indent>[ \\t]*)(?<fqcn>[a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)+)$");
    private static final Pattern COUNT_LINE = Pattern.compile("^(\\d+) test(s?) failed:$");

    private TestFailureHighlight() {}

    /**
     * Paint a sequence of engine output lines. When the sequence is (or contains) a test-failure
     * block starting with {@link #HEADER_SENTINEL}, that block is restyled; other lines pass through
     * {@link StackTraceHighlight}.
     */
    public static List<String> paintLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return lines == null ? List.of() : lines;
        List<String> out = new ArrayList<>(lines.size() + 4);
        int i = 0;
        while (i < lines.size()) {
            String raw = lines.get(i);
            // Skip leading blanks before a failure header so only one blank remains under the prompt.
            if ((raw == null || raw.isEmpty())
                    && i + 1 < lines.size()
                    && HEADER_SENTINEL.equals(
                            lines.get(i + 1) != null ? lines.get(i + 1).strip() : null)) {
                i++;
                continue;
            }
            if (HEADER_SENTINEL.equals(raw != null ? raw.strip() : null)) {
                int end = findBlockEnd(lines, i);
                out.addAll(paintBlock(lines.subList(i, end)));
                i = end;
                // Drop trailing blanks after the block so the settle wedge sits tight under it.
                while (i < lines.size() && (lines.get(i) == null || lines.get(i).isEmpty())) i++;
                continue;
            }
            out.add(StackTraceHighlight.line(raw));
            i++;
        }
        return out;
    }

    /**
     * Live-stream painter: tracks whether the next indented value is actual (red) or expected
     * (green) across successive {@link #paintBodyLine} calls on the same block.
     */
    public static final class Stream {
        private ValueRole nextValue = ValueRole.ACTUAL;

        public String line(String raw) {
            if (raw == null) return null;
            Theme t = Theme.active();
            if (!t.isAnsi()) return railPlain(raw);
            nextValue = updateValueRole(raw, nextValue);
            return rail(paintContent(raw, t, nextValue), t);
        }

        public void reset() {
            nextValue = ValueRole.ACTUAL;
        }
    }

    /** Paint one line when the listener is already inside a failure block (rail applied). */
    public static String paintBodyLine(String raw) {
        return new Stream().line(raw);
    }

    /** Styled header: red Test pill + " Failure". */
    public static String paintHeader() {
        Theme t = Theme.active();
        if (!t.isAnsi()) return "[Test] Failure";
        // White text on failure red; nerd caps match chip fill (same as activity/tree pills).
        AttributedStyle body = t.withBackground(t.bright(255, 255, 255), t.planFailColor());
        AttributedStyle caps = t.bright(t.planFailColor());
        String pill = Badge.pill("Test", GlobalConfig.nerdfont(), body, caps);
        return pill + " " + Theme.colorize("Failure", t.error().bold());
    }

    // --- block painting ------------------------------------------------------

    private static List<String> paintBlock(List<String> block) {
        List<String> out = new ArrayList<>(block.size());
        Theme t = Theme.active();
        boolean sawHeader = false;
        // Tracks whether the next indented value is an "actual" (red) or "expected" (green).
        ValueRole nextValue = ValueRole.ACTUAL;
        for (String raw : block) {
            if (raw == null) {
                out.add(null);
                continue;
            }
            if (!sawHeader && HEADER_SENTINEL.equals(raw.strip())) {
                out.add(paintHeader());
                sawHeader = true;
                continue;
            }
            if (!t.isAnsi()) {
                out.add(railPlain(raw));
                continue;
            }
            nextValue = updateValueRole(raw, nextValue);
            out.add(rail(paintContent(raw, t, nextValue), t));
        }
        out.add(DiagnosticReport.errorFooter());
        return out;
    }

    private enum ValueRole {
        ACTUAL,
        EXPECTED
    }

    private static ValueRole updateValueRole(String raw, ValueRole current) {
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("actual") || lower.contains("but was") || lower.contains("but had")) {
            return ValueRole.ACTUAL;
        }
        if (lower.contains("expected")
                || lower.contains("between")
                || lower.contains("should be")
                || lower.contains("to be")) {
            return ValueRole.EXPECTED;
        }
        return current;
    }

    /**
     * End index (exclusive) of the failure block starting at {@code start}. Internal blanks (between
     * the count line and FAILED, between metadata and assertion body, between failures) are kept;
     * a trailing blank after the last content is dropped so the settle wedge sits tight under the
     * report.
     */
    static int findBlockEnd(List<String> lines, int start) {
        boolean sawFailed = false;
        for (int i = start + 1; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s != null && s.contains("FAILED  ")) sawFailed = true;
            if (s == null || !s.isEmpty()) continue;
            // blank line
            if (!sawFailed) continue; // blanks before the first FAILED stay in the block
            if (i + 1 >= lines.size()) return i; // drop trailing blank at EOF
            String next = lines.get(i + 1);
            if (next == null || next.isEmpty() || !isFailureContinuation(next)) {
                return i; // drop trailing blank before non-failure content
            }
        }
        return lines.size();
    }

    /** True when {@code line} still belongs inside a test-failure report after a blank. */
    static boolean isFailureContinuation(String line) {
        if (line == null) return false;
        if (line.startsWith(" ") || line.startsWith("\t")) return true;
        String t = line.stripLeading();
        if (t.startsWith("FAILED  ") || t.startsWith("class: ") || t.startsWith("at ") || t.startsWith("...")) {
            return true;
        }
        if (COUNT_LINE.matcher(t).matches()) return true;
        // Exception FQCN on its own line
        if (FQCN_LINE.matcher(line).matches() && looksLikeExceptionOrClass(t)) return true;
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("expect")
                || lower.contains("actual")
                || lower.contains("between")
                || lower.contains("but was")
                || lower.contains("but had");
    }

    private static String paintContent(String raw, Theme t, ValueRole valueRole) {
        if (raw.isEmpty()) return "";

        Matcher count = COUNT_LINE.matcher(raw);
        if (count.matches()) {
            return Theme.colorize(count.group(1), t.focused())
                    + Theme.colorize(" test" + count.group(2) + " failed:", t.midGray());
        }

        Matcher failed = FAILED_LINE.matcher(raw);
        if (failed.matches()) {
            return Theme.colorize(failed.group("indent") + "FAILED  ", t.error().bold())
                    + paintHeadline(failed.group("rest"), t);
        }

        Matcher cls = CLASS_LINE.matcher(raw);
        if (cls.matches()) {
            return Theme.colorize(cls.group("indent") + "class: ", t.midGray())
                    + paintFqcn(cls.group("fqcn").strip(), t);
        }

        Matcher fqcn = FQCN_LINE.matcher(raw);
        if (fqcn.matches() && looksLikeExceptionOrClass(fqcn.group("fqcn"))) {
            return Theme.colorize(fqcn.group("indent"), t.midGray()) + paintFqcn(fqcn.group("fqcn"), t);
        }

        // Stack frames — reuse the shared highlighter (keeps frame colors consistent).
        String stripped = raw.stripLeading();
        if (stripped.startsWith("at ") || stripped.startsWith("...")) {
            return StackTraceHighlight.line(raw);
        }

        return paintAssertionLine(raw, t, valueRole);
    }

    /**
     * {@code group:artifact :: method()  [wN]} — coords theme + method as function, worker tag dim.
     */
    static String paintHeadline(String rest, Theme t) {
        if (rest == null || rest.isEmpty()) return "";
        String worker = "";
        String body = rest;
        int w = rest.lastIndexOf("  [w");
        if (w > 0 && rest.endsWith("]")) {
            worker = rest.substring(w);
            body = rest.substring(0, w);
        }
        String painted;
        int sep = body.indexOf(" :: ");
        if (sep > 0) {
            String coord = body.substring(0, sep);
            String method = body.substring(sep + 4);
            painted = paintCoord(coord, t)
                    + Theme.colorize(" :: ", t.darkGray())
                    + Theme.colorize(method, SyntaxHighlight.styleFor(SyntaxHighlight.Role.FUNCTION));
        } else {
            painted = Theme.colorize(body, SyntaxHighlight.styleFor(SyntaxHighlight.Role.FUNCTION));
        }
        if (worker.isEmpty()) return painted;
        return painted + Theme.colorize(worker, t.darkGray());
    }

    private static String paintCoord(String coord, Theme t) {
        int colon = coord.indexOf(':');
        if (colon <= 0 || colon >= coord.length() - 1) {
            return Theme.colorize(coord, t.coordName());
        }
        return Coords.ga(coord.substring(0, colon), coord.substring(colon + 1));
    }

    static String paintFqcn(String fqcn, Theme t) {
        if (fqcn == null || fqcn.isEmpty()) return "";
        int dot = fqcn.lastIndexOf('.');
        if (dot < 0) {
            return Theme.colorize(fqcn, SyntaxHighlight.styleFor(SyntaxHighlight.Role.TYPE));
        }
        return Theme.colorize(fqcn.substring(0, dot + 1), SyntaxHighlight.styleFor(SyntaxHighlight.Role.NAMESPACE))
                + Theme.colorize(fqcn.substring(dot + 1), SyntaxHighlight.styleFor(SyntaxHighlight.Role.TYPE));
    }

    private static boolean looksLikeExceptionOrClass(String fqcn) {
        if (fqcn.indexOf('.') < 0) return false;
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        return Character.isUpperCase(simple.charAt(0));
    }

    /**
     * AssertJ / JUnit message lines: indented values use {@code valueRole} (red actual / green
     * expected); label prose stays mid-gray.
     */
    static String paintAssertionLine(String raw, Theme t, ValueRole valueRole) {
        String stripped = raw.stripLeading();
        int indentLen = raw.length() - stripped.length();
        String indent = raw.substring(0, indentLen);

        // Indented value line under "Expecting actual:" / "to be between:" etc.
        if (indentLen >= 2 && !stripped.isEmpty() && !stripped.endsWith(":")) {
            AttributedStyle v = valueRole == ValueRole.EXPECTED ? t.success() : t.error();
            return Theme.colorize(indent, t.midGray()) + Theme.colorize(stripped, v);
        }

        // Label lines and free prose.
        return Theme.colorize(raw, t.midGray());
    }

    private static String rail(String paintedContent, Theme t) {
        return " " + Theme.colorize(RAIL, t.error()) + " " + paintedContent;
    }

    private static String railPlain(String raw) {
        // no-ansi: still indent with a light ASCII rail so structure survives
        return " | " + (raw == null ? "" : raw);
    }

    /** True when this single output line is the engine failure-block title. */
    public static boolean isHeader(String line) {
        return line != null && HEADER_SENTINEL.equals(line.strip());
    }
}
