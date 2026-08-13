// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Rgb;
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
     * Live-stream painter: tracks actual/expected value colors and buffers {@code @@source} …
     * {@code @@src-end} so the editor snippet paints as a unit.
     */
    public static final class Stream {
        private ValueRole nextValue = ValueRole.ACTUAL;
        private List<String> sourceBuf;

        /**
         * Paint one body line. When buffering a source snippet, returns {@code null} for intermediate
         * markers (caller should skip writing); the last marker returns a multi-line string joined
         * with {@code \n}.
         */
        public String line(String raw) {
            if (raw == null) return null;
            if (raw.startsWith("@@source ")) {
                sourceBuf = new ArrayList<>();
                sourceBuf.add(raw);
                return null;
            }
            if (sourceBuf != null) {
                sourceBuf.add(raw);
                if (raw.equals("@@src-end")) {
                    Theme t = Theme.active();
                    List<String> painted = paintSourceBlock(sourceBuf, t);
                    sourceBuf = null;
                    return String.join("\n", painted);
                }
                return null;
            }
            Theme t = Theme.active();
            if (!t.isAnsi()) return railPlain(raw);
            if (raw.contains(" thrown at line ")) return rail(paintThrownAt(raw, t), t);
            nextValue = updateValueRole(raw, nextValue);
            return rail(paintContent(raw, t, nextValue), t);
        }

        public void reset() {
            nextValue = ValueRole.ACTUAL;
            sourceBuf = null;
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

    /** Web/console editor chrome: {@code --console-bg: #0b1116}. */
    private static final Rgb CONSOLE_BG = new Rgb(11, 17, 22);

    /**
     * Error-line band: {@code rgba(255, 51, 102, 0.12)} over console bg → roughly rgb(40, 21, 32).
     */
    private static final Rgb ERROR_LINE_BG = new Rgb(40, 21, 32);

    private static List<String> paintBlock(List<String> block) {
        List<String> out = new ArrayList<>(block.size() + 8);
        Theme t = Theme.active();
        boolean sawHeader = false;
        // Tracks whether the next indented value is an "actual" (red) or "expected" (green).
        ValueRole nextValue = ValueRole.ACTUAL;
        for (int i = 0; i < block.size(); i++) {
            String raw = block.get(i);
            if (raw == null) {
                out.add(null);
                continue;
            }
            if (!sawHeader && HEADER_SENTINEL.equals(raw.strip())) {
                out.add(paintHeader());
                sawHeader = true;
                continue;
            }
            if (raw.startsWith("@@source ")) {
                int end = i + 1;
                while (end < block.size() && !isSrcEnd(block.get(end))) end++;
                if (end < block.size()) end++; // include @@src-end
                out.addAll(paintSourceBlock(block.subList(i, end), t));
                i = end - 1;
                continue;
            }
            if (!t.isAnsi()) {
                out.add(railPlain(stripSrcMarkers(raw)));
                continue;
            }
            if (raw.contains(" thrown at line ")) {
                out.add(rail(paintThrownAt(raw, t), t));
                continue;
            }
            nextValue = updateValueRole(raw, nextValue);
            out.add(rail(paintContent(raw, t, nextValue), t));
        }
        out.add(DiagnosticReport.errorFooter());
        return out;
    }

    private static boolean isSrcEnd(String line) {
        return line != null && line.equals("@@src-end");
    }

    private static String stripSrcMarkers(String raw) {
        if (raw == null) return "";
        if (raw.startsWith("@@source ")) return raw.substring("@@source ".length());
        if (raw.startsWith("@@src ")) {
            int bar = raw.indexOf('|');
            return bar >= 0 ? raw.substring(bar + 1) : raw;
        }
        if (raw.equals("@@src-end")) return "";
        return raw;
    }

    /**
     * Editor snippet: path (periwinkle + underline), then guttered lines on console-bg; error line
     * uses a subtle dark-red band. No failure rail — reads as a code pane.
     */
    static List<String> paintSourceBlock(List<String> markers, Theme t) {
        if (markers.isEmpty()) return List.of();
        String header = markers.get(0);
        String path = attr(header, "path");
        String lang = attr(header, "lang");
        int errorLine = parseInt(attr(header, "line"), 0);
        SyntaxHighlight.Language language = languageOf(lang);

        List<String> out = new ArrayList<>();
        if (!t.isAnsi()) {
            out.add(path);
            for (int i = 1; i < markers.size(); i++) {
                String m = markers.get(i);
                if (m == null || m.equals("@@src-end") || !m.startsWith("@@src ")) continue;
                out.add(plainSrcLine(m));
            }
            return out;
        }

        // Path: periwinkle + underline (no console band — sits above the pane).
        out.add(Theme.colorize(path, t.path().underline()));

        Rgb pane = CONSOLE_BG;
        for (int i = 1; i < markers.size(); i++) {
            String m = markers.get(i);
            if (m == null || m.equals("@@src-end") || !m.startsWith("@@src ")) continue;
            out.add(paintSrcLine(m, errorLine, language, t, pane));
        }
        return out;
    }

    private static String plainSrcLine(String marker) {
        // @@src 15*|code  or  @@src 10|code
        int sp = marker.indexOf(' ');
        int bar = marker.indexOf('|');
        if (sp < 0 || bar < 0) return marker;
        String num = marker.substring(sp + 1, bar).replace("*", "");
        String code = marker.substring(bar + 1);
        return String.format("%4s│ %s", num, code);
    }

    private static String paintSrcLine(
            String marker, int errorLine, SyntaxHighlight.Language language, Theme t, Rgb paneBg) {
        int sp = marker.indexOf(' ');
        int bar = marker.indexOf('|');
        if (sp < 0 || bar < 0) return marker;
        String numPart = marker.substring(sp + 1, bar);
        boolean isError = numPart.endsWith("*");
        String num = isError ? numPart.substring(0, numPart.length() - 1) : numPart;
        String code = marker.substring(bar + 1);

        Rgb lineBg = isError ? ERROR_LINE_BG : paneBg;
        String gutter = Theme.colorize(String.format("%4s", num), t.withBackground(t.dim(), lineBg));
        String rail = Theme.colorize("│", t.withBackground(t.darkGray(), lineBg));
        String gap = Theme.colorize(" ", t.withBackground(AttributedStyle.DEFAULT, lineBg));
        String codePainted = code.isEmpty()
                ? Theme.colorize(" ", t.withBackground(AttributedStyle.DEFAULT, lineBg))
                : SyntaxHighlight.highlight(code, language, lineBg);
        return gutter + rail + gap + codePainted;
    }

    private static String paintThrownAt(String raw, Theme t) {
        // Fqcn thrown at line N
        int idx = raw.indexOf(" thrown at line ");
        if (idx <= 0) return Theme.colorize(raw, t.midGray());
        String fqcn = raw.substring(0, idx).strip();
        String rest = raw.substring(idx);
        return paintFqcn(fqcn, t) + Theme.colorize(rest, t.midGray());
    }

    private static String attr(String header, String key) {
        // @@source path=foo line=15 start=10 lang=java
        String needle = key + "=";
        int i = header.indexOf(needle);
        if (i < 0) return "";
        int s = i + needle.length();
        int e = s;
        while (e < header.length() && !Character.isWhitespace(header.charAt(e))) e++;
        return header.substring(s, e);
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return dflt;
        }
    }

    private static SyntaxHighlight.Language languageOf(String lang) {
        if (lang == null) return SyntaxHighlight.Language.JAVA;
        return switch (lang.toLowerCase(java.util.Locale.ROOT)) {
            case "kotlin", "kt" -> SyntaxHighlight.Language.KOTLIN;
            case "groovy" -> SyntaxHighlight.Language.GROOVY;
            default -> SyntaxHighlight.Language.JAVA;
        };
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
        if (line.startsWith("@@source ") || line.startsWith("@@src ") || line.equals("@@src-end")) return true;
        String t = line.stripLeading();
        if (t.startsWith("FAILED  ") || t.startsWith("class: ") || t.startsWith("at ") || t.startsWith("...")) {
            return true;
        }
        if (t.contains(" thrown at line ")) return true;
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
