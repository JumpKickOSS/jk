// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
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
 * <p>Painted shape:
 *
 * <pre>
 * ✘ Test failure in group:artifact › 1 test failed
 *
 * FAILED SimpleClass.method()
 *
 * "description"
 *  Expected: 42
 *   But Was: 41
 *
 *     path/to/File.java
 *   19│ …
 *     AssertionFailedError thrown at line 23
 * </pre>
 *
 * <p>No thick outer rail — the body is flush-left; colors match the prior CLI report.
 */
public final class TestFailureHighlight {

    /** Engine sentinel title — must stay byte-identical to {@code TestSupport.renderFailures}. */
    public static final String HEADER_SENTINEL = "Test Failure";

    /** @deprecated rail removed from the report; kept for tests that asserted its presence. */
    @Deprecated
    public static final String RAIL = "┃";

    private static final Pattern FAILED_LINE = Pattern.compile("^(?<indent>[ \\t]*)FAILED (?<rest>.+)$");
    private static final Pattern MODULE_LINE = Pattern.compile("^module: (?<mod>.+)$");
    private static final Pattern COUNT_LINE = Pattern.compile("^(\\d+) test(s?) failed:?$");
    private static final Pattern THROWN_AT =
            Pattern.compile("^[›\\s]*(?<ex>[A-Za-z_][\\w$]*) thrown at line (?<n>\\d+)\\s*$");
    private static final Pattern FQCN_LINE =
            Pattern.compile("^(?<indent>[ \\t]*)(?<fqcn>[a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)+)$");

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
                while (i < lines.size() && (lines.get(i) == null || lines.get(i).isEmpty())) i++;
                continue;
            }
            out.add(StackTraceHighlight.line(raw));
            i++;
        }
        return out;
    }

    /**
     * Live-stream buffer: accumulates the full failure block so the report paints as one unit
     * (header + rail body + footer).
     */
    public static final class Stream {
        private final List<String> buf = new ArrayList<>();

        public String line(String raw) {
            buf.add(raw);
            return null;
        }

        public List<String> finish() {
            if (buf.isEmpty()) return List.of();
            if (!HEADER_SENTINEL.equals(buf.get(0) != null ? buf.get(0).strip() : null)) {
                buf.add(0, HEADER_SENTINEL);
            }
            List<String> painted = paintLines(buf);
            buf.clear();
            return painted;
        }

        public void reset() {
            buf.clear();
        }
    }

    public static String paintBodyLine(String raw) {
        Stream s = new Stream();
        s.line(HEADER_SENTINEL);
        s.line(raw);
        List<String> painted = s.finish();
        return painted.size() > 1 ? painted.get(1) : (painted.isEmpty() ? raw : painted.get(0));
    }

    /** Styled header fragment (legacy callers). */
    public static String paintHeader() {
        return paintHeaderLine(null, 1, false);
    }

    /**
     * {@code ✘ Test failure in group:artifact › 1 test failed}
     *
     * @param module GA coord or null/blank
     * @param count failure count
     * @param plural {@code true} when count != 1
     */
    public static String paintHeaderLine(String module, int count, boolean plural) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            String m = module == null || module.isBlank() ? "" : " in " + module;
            return "✘ Test failure" + m + " › " + count + " test" + (plural ? "s" : "") + " failed";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Theme.colorize("✘", t.error()))
                .append(' ')
                .append(Theme.colorize("Test failure", t.midGray()));
        if (module != null && !module.isBlank()) {
            sb.append(Theme.colorize(" in ", t.midGray()));
            int colon = module.indexOf(':');
            if (colon > 0 && colon < module.length() - 1) {
                sb.append(Coords.ga(module.substring(0, colon), module.substring(colon + 1)));
            } else {
                sb.append(Theme.colorize(module, t.coordName()));
            }
        }
        sb.append(' ')
                .append(Theme.colorize("›", t.darkGray()))
                .append(' ')
                .append(Theme.colorize(String.valueOf(count), t.focused()))
                .append(Theme.colorize(" test" + (plural ? "s" : "") + " failed", t.midGray()));
        return sb.toString();
    }

    // --- block painting ------------------------------------------------------

    private static final Rgb CONSOLE_BG = new Rgb(11, 17, 22);
    /** rgba(255, 51, 102, 0.3) over console bg → rgb(84, 27, 46). */
    private static final Rgb ERROR_LINE_BG = new Rgb(84, 27, 46);

    private static final String BODY_INDENT = "    ";

    private static List<String> paintBlock(List<String> block) {
        List<String> out = new ArrayList<>(block.size() + 8);
        Theme t = Theme.active();

        // ---- parse header fields --------------------------------------------
        String module = null;
        int count = 1;
        boolean plural = false;
        int i = 0;
        if (i < block.size() && HEADER_SENTINEL.equals(strip(block.get(i)))) {
            i++;
        }
        while (i < block.size()) {
            String raw = block.get(i);
            if (raw == null || raw.isEmpty()) {
                i++;
                break;
            }
            Matcher mod = MODULE_LINE.matcher(raw.strip());
            if (mod.matches()) {
                module = mod.group("mod").strip();
                i++;
                continue;
            }
            Matcher cnt = COUNT_LINE.matcher(raw.strip());
            if (cnt.matches()) {
                count = Integer.parseInt(cnt.group(1));
                plural = "s".equals(cnt.group(2)) || count != 1;
                i++;
                continue;
            }
            // legacy "1 test failed:" or other — stop header parse
            if (raw.strip().startsWith("FAILED ") || raw.startsWith("@@")) break;
            i++;
        }
        out.add(paintHeaderLine(module, count, plural));
        out.add(""); // blank under header

        // ---- body (no thick outer rail) ------------------------------------
        ValueRole nextValue = ValueRole.ACTUAL;
        List<String> assertBuf = new ArrayList<>();
        boolean collectingAssert = false;

        while (i < block.size()) {
            String raw = block.get(i);
            if (raw == null) {
                i++;
                continue;
            }

            if (raw.startsWith("@@source ")) {
                flushAssert(out, assertBuf, collectingAssert, t);
                collectingAssert = false;
                assertBuf.clear();
                int end = i + 1;
                while (end < block.size() && !isSrcEnd(block.get(end))) end++;
                if (end < block.size()) end++;
                out.addAll(paintSourceBlock(block.subList(i, end), t));
                i = end;
                continue;
            }

            Matcher thrown = THROWN_AT.matcher(raw.strip());
            if (thrown.matches()
                    || raw.strip().matches("^[›\\s]*[A-Za-z_][\\w$]* thrown at line \\d+\\s*$")) {
                flushAssert(out, assertBuf, collectingAssert, t);
                collectingAssert = false;
                assertBuf.clear();
                out.add(paintThrownAt(raw, t));
                i++;
                continue;
            }

            Matcher failed = FAILED_LINE.matcher(raw);
            if (failed.matches() || raw.strip().startsWith("FAILED ")) {
                flushAssert(out, assertBuf, collectingAssert, t);
                collectingAssert = false;
                assertBuf.clear();
                String rest = failed.matches() ? failed.group("rest") : raw.strip().substring("FAILED ".length());
                String failedWord = t.isAnsi()
                        ? Theme.colorize("FAILED", t.error().bold())
                        : "FAILED";
                out.add(failedWord + " " + paintShortLabel(rest, t));
                out.add("");
                collectingAssert = true; // assertion body follows until source
                i++;
                continue;
            }

            // Stack frames / legacy bare exception
            String stripped = raw.stripLeading();
            if (stripped.startsWith("at ") || stripped.startsWith("...")) {
                flushAssert(out, assertBuf, collectingAssert, t);
                collectingAssert = false;
                assertBuf.clear();
                out.add(StackTraceHighlight.line(raw));
                i++;
                continue;
            }

            if (collectingAssert || looksLikeAssertionBody(raw)) {
                collectingAssert = true;
                if (assertBuf.isEmpty() && raw.isEmpty()) {
                    i++;
                    continue; // drop leading blank after FAILED
                }
                assertBuf.add(raw);
                i++;
                continue;
            }

            if (raw.isEmpty()) {
                out.add("");
                i++;
                continue;
            }

            if (!t.isAnsi()) {
                out.add(raw);
            } else {
                nextValue = updateValueRole(raw, nextValue);
                out.add(paintFallbackContent(raw, t, nextValue));
            }
            i++;
        }
        flushAssert(out, assertBuf, collectingAssert, t);
        // No ┗━ footer — report ends after the thrown-at / last body line.
        return out;
    }

    private static void flushAssert(List<String> out, List<String> assertBuf, boolean collecting, Theme t) {
        if (!collecting || assertBuf.isEmpty()) return;
        List<String> painted = paintAssertionBody(assertBuf, t);
        for (String line : painted) {
            out.add(line == null ? "" : line);
        }
        out.add(""); // blank after assertion body before source
        assertBuf.clear();
    }

    private static boolean looksLikeAssertionBody(String raw) {
        if (raw == null) return false;
        String t = raw.stripLeading();
        if (t.startsWith("[") && t.contains("]")) return true;
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("expected")
                || lower.startsWith("but was")
                || lower.startsWith("expecting")
                || lower.startsWith("to be");
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    private static boolean isSrcEnd(String line) {
        return line != null && line.equals("@@src-end");
    }

    // --- source block --------------------------------------------------------

    static List<String> paintSourceBlock(List<String> markers, Theme t) {
        if (markers.isEmpty()) return List.of();
        String header = markers.get(0);
        String path = attr(header, "path");
        String lang = attr(header, "lang");
        SyntaxHighlight.Language language = languageOf(lang);

        List<SrcRow> rows = new ArrayList<>();
        int maxCode = 0;
        for (int i = 1; i < markers.size(); i++) {
            String m = markers.get(i);
            if (m == null || m.equals("@@src-end") || !m.startsWith("@@src ")) continue;
            SrcRow row = parseSrcRow(m);
            if (row == null) continue;
            rows.add(row);
            maxCode = Math.max(maxCode, row.code.length());
        }

        List<String> out = new ArrayList<>();
        if (!t.isAnsi()) {
            out.add(BODY_INDENT + path);
            for (SrcRow row : rows) out.add(plainSrcLine(row, maxCode));
            return out;
        }

        out.add(BODY_INDENT + Theme.colorize(path, t.path().underline()));
        Rgb pane = CONSOLE_BG;
        for (SrcRow row : rows) {
            out.add(paintSrcLine(row, maxCode, language, t, pane));
        }
        return out;
    }

    private record SrcRow(String num, boolean error, String code) {}

    private static SrcRow parseSrcRow(String marker) {
        int sp = marker.indexOf(' ');
        int bar = marker.indexOf('|');
        if (sp < 0 || bar < 0) return null;
        String numPart = marker.substring(sp + 1, bar);
        boolean isError = numPart.endsWith("*");
        String num = isError ? numPart.substring(0, numPart.length() - 1) : numPart;
        return new SrcRow(num, isError, marker.substring(bar + 1));
    }

    private static String plainSrcLine(SrcRow row, int maxCode) {
        return String.format("%4s│ %s", row.num, padRight(row.code, maxCode));
    }

    private static String paintSrcLine(
            SrcRow row, int maxCode, SyntaxHighlight.Language language, Theme t, Rgb paneBg) {
        Rgb lineBg = row.error ? ERROR_LINE_BG : paneBg;
        AttributedStyle numStyle = row.error ? t.error() : t.dim();
        String gutter = Theme.colorize(String.format("%4s", row.num), t.withBackground(numStyle, lineBg));
        String gutterRail = Theme.colorize("│", t.withBackground(t.darkGray(), lineBg));
        String gap = Theme.colorize(" ", t.withBackground(AttributedStyle.DEFAULT, lineBg));
        String code = row.code;
        String codePainted = code.isEmpty() ? "" : SyntaxHighlight.highlight(code, language, lineBg);
        int pad = Math.max(0, maxCode - code.length());
        if (code.isEmpty() && pad == 0) pad = 1;
        String padPainted =
                pad > 0 ? Theme.colorize(" ".repeat(pad), t.withBackground(AttributedStyle.DEFAULT, lineBg)) : "";
        return gutter + gutterRail + gap + codePainted + padPainted;
    }

    private static String padRight(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    // --- labels / thrown-at / assertion --------------------------------------

    /** {@code SimpleClass.method()} / {@code SimpleClass.method(Path)} — type + function roles. */
    static String paintShortLabel(String rest, Theme t) {
        if (rest == null || rest.isEmpty()) return "";
        // optional "  [wN]"
        String worker = "";
        String body = rest.strip();
        int w = body.lastIndexOf("  [w");
        if (w > 0 && body.endsWith("]")) {
            worker = body.substring(w);
            body = body.substring(0, w).strip();
        }
        // Split Class.method(…) — dot before '(' if params present
        int paren = body.indexOf('(');
        int dot = body.lastIndexOf('.');
        if (paren >= 0 && dot > paren) {
            // odd case: ignore dots inside params
            dot = body.lastIndexOf('.', paren);
        }
        String painted;
        if (dot > 0 && dot < body.length() - 1) {
            String cls = body.substring(0, dot);
            String method = body.substring(dot + 1);
            painted = Theme.colorize(cls, SyntaxHighlight.styleFor(SyntaxHighlight.Role.TYPE))
                    + Theme.colorize(".", t.darkGray())
                    + Theme.colorize(method, SyntaxHighlight.styleFor(SyntaxHighlight.Role.FUNCTION));
        } else {
            painted = Theme.colorize(body, SyntaxHighlight.styleFor(SyntaxHighlight.Role.FUNCTION));
        }
        if (worker.isEmpty()) return painted;
        return painted + Theme.colorize(worker, t.darkGray());
    }

    private static String paintThrownAt(String raw, Theme t) {
        String s = raw.strip().replaceFirst("^[›\\s]+", "");
        Matcher m = THROWN_AT.matcher(s);
        if (!m.matches()) {
            if (!s.isEmpty() && s.indexOf(' ') < 0) {
                return BODY_INDENT
                        + Theme.colorize(simpleName(s), SyntaxHighlight.styleFor(SyntaxHighlight.Role.TYPE));
            }
            return Theme.colorize(raw, t.midGray());
        }
        String ex = simpleName(m.group("ex"));
        String n = m.group("n");
        // Same indent as the source path line under the rail.
        return BODY_INDENT
                + Theme.colorize(ex, SyntaxHighlight.styleFor(SyntaxHighlight.Role.TYPE))
                + Theme.colorize(" thrown at line ", t.midGray())
                + Theme.colorize(n, t.focused());
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) return "";
        int d = fqcn.lastIndexOf('.');
        return d >= 0 ? fqcn.substring(d + 1) : fqcn;
    }

    static List<String> paintAssertionBody(List<String> body, Theme t) {
        if (body == null || body.isEmpty()) return List.of();
        int lo = 0;
        int hi = body.size() - 1;
        while (lo <= hi && (body.get(lo) == null || body.get(lo).isBlank())) lo++;
        while (hi >= lo && (body.get(hi) == null || body.get(hi).isBlank())) hi--;
        if (lo > hi) return List.of();

        List<String> slice = body.subList(lo, hi + 1);
        String joined = String.join("\n", slice);
        List<String> assertj = tryPaintAssertJ(joined, t);
        if (assertj != null) return assertj;

        List<String> out = new ArrayList<>();
        ValueRole role = ValueRole.ACTUAL;
        for (String raw : slice) {
            if (raw == null) {
                out.add("");
                continue;
            }
            if (!t.isAnsi()) {
                out.add(raw);
                continue;
            }
            role = updateValueRole(raw, role);
            out.add(paintAssertionLine(raw, t, role));
        }
        return out;
    }

    static List<String> tryPaintAssertJ(String joined, Theme t) {
        String desc = null;
        String rest = joined.strip();
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close > 0) {
                desc = rest.substring(1, close).strip();
                rest = rest.substring(close + 1).strip();
            }
        }
        Pattern exp = Pattern.compile("(?i)^expected:\\s*([^\\n]+?)\\s*\\R\\s*but was:\\s*([^\\n]+?)\\s*$");
        Matcher m = exp.matcher(rest);
        if (!m.matches()) {
            Pattern one = Pattern.compile("(?i)^expected:\\s*(.+?)\\s+but was:\\s*(.+?)\\s*$");
            Matcher m1 = one.matcher(rest);
            if (!m1.matches()) return null;
            return paintExpectedButWas(desc, m1.group(1).strip(), m1.group(2).strip(), t);
        }
        return paintExpectedButWas(desc, m.group(1).strip(), m.group(2).strip(), t);
    }

    private static List<String> paintExpectedButWas(String desc, String expected, String actual, Theme t) {
        List<String> out = new ArrayList<>();
        if (!t.isAnsi()) {
            if (desc != null && !desc.isEmpty()) out.add("\"" + desc + "\"");
            out.add(" Expected: " + stripValueQuotes(expected));
            out.add("  But Was: " + stripValueQuotes(actual));
            return out;
        }
        // Flush-left under the rail (no extra indent on the description).
        if (desc != null && !desc.isEmpty()) {
            out.add(Theme.colorize("\"", t.darkGray())
                    + Theme.colorize(desc, t.brightWhite().italic())
                    + Theme.colorize("\"", t.darkGray()));
        }
        String expVal = stripValueQuotes(expected);
        String actVal = stripValueQuotes(actual);
        out.add(Theme.colorize(" Expected: ", t.midGray()) + Theme.colorize(expVal, t.success()));
        out.add(Theme.colorize("  But Was: ", t.midGray()) + Theme.colorize(actVal, t.error()));
        return out;
    }

    private static String stripValueQuotes(String v) {
        if (v == null) return "";
        String s = v.strip();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return s.substring(1, s.length() - 1);
            }
            if (a == '<' && b == '>') return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String attr(String header, String key) {
        String needle = key + "=";
        int i = header.indexOf(needle);
        if (i < 0) return "";
        int s = i + needle.length();
        int e = s;
        while (e < header.length() && !Character.isWhitespace(header.charAt(e))) e++;
        return header.substring(s, e);
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

    static int findBlockEnd(List<String> lines, int start) {
        boolean sawFailed = false;
        for (int i = start + 1; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s != null && s.contains("FAILED ")) sawFailed = true;
            if (s == null || !s.isEmpty()) continue;
            if (!sawFailed) continue;
            if (i + 1 >= lines.size()) return i;
            String next = lines.get(i + 1);
            if (next == null || next.isEmpty() || !isFailureContinuation(next)) {
                return i;
            }
        }
        return lines.size();
    }

    static boolean isFailureContinuation(String line) {
        if (line == null) return false;
        if (line.startsWith(" ") || line.startsWith("\t")) return true;
        if (line.startsWith("@@source ") || line.startsWith("@@src ") || line.equals("@@src-end")) return true;
        String t = line.stripLeading();
        if (t.startsWith("FAILED ") || t.startsWith("module: ") || t.startsWith("at ") || t.startsWith("...")) {
            return true;
        }
        if (t.startsWith("›") || t.contains(" thrown at line ")) return true;
        // Indented exception locus under FAILED
        if (t.matches("[A-Za-z_][\\w$]* thrown at line \\d+")) return true;
        if (t.startsWith("[") && t.contains("]")) return true;
        if (COUNT_LINE.matcher(t).matches()) return true;
        if (FQCN_LINE.matcher(line).matches() && looksLikeExceptionOrClass(t)) return true;
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("expected")
                || lower.startsWith("but was")
                || lower.startsWith("but had")
                || lower.contains("expect")
                || lower.contains("actual")
                || lower.contains("between")
                || lower.contains("but was")
                || lower.contains("but had");
    }

    private static String paintFallbackContent(String raw, Theme t, ValueRole valueRole) {
        if (raw.isEmpty()) return "";
        Matcher cnt = COUNT_LINE.matcher(raw);
        if (cnt.matches()) {
            return Theme.colorize(cnt.group(1), t.focused())
                    + Theme.colorize(" test" + cnt.group(2) + " failed", t.midGray());
        }
        String stripped = raw.stripLeading();
        if (stripped.startsWith("at ") || stripped.startsWith("...")) {
            return StackTraceHighlight.line(raw);
        }
        return paintAssertionLine(raw, t, valueRole);
    }

    static String paintAssertionLine(String raw, Theme t, ValueRole valueRole) {
        String stripped = raw.stripLeading();
        int indentLen = raw.length() - stripped.length();
        String indent = raw.substring(0, indentLen);
        if (indentLen >= 2 && !stripped.isEmpty() && !stripped.endsWith(":")) {
            AttributedStyle v = valueRole == ValueRole.EXPECTED ? t.success() : t.error();
            return Theme.colorize(indent, t.midGray()) + Theme.colorize(stripped, v);
        }
        return Theme.colorize(raw, t.midGray());
    }

    private static boolean looksLikeExceptionOrClass(String fqcn) {
        if (fqcn.indexOf('.') < 0) return false;
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        return Character.isUpperCase(simple.charAt(0));
    }

    public static boolean isHeader(String line) {
        return line != null && HEADER_SENTINEL.equals(line.strip());
    }
}
