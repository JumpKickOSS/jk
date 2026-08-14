// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Badge;
import cc.jumpkick.cli.tui.RichText;
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
 * <p>Painted shape:
 *
 * <pre>
 * Test Failure in group:artifact › 1 test failed
 *  ┃
 *  ┃ FAILED SimpleClass.method()
 *  ┃
 *  ┃ "description"
 *  ┃  Expected: 42
 *  ┃   But Was: 41
 *  ┃
 *  ┃     path/to/File.java   ← OSC-8 deep link into the dashboard code editor when known
 *  ┃   19│ …
 *  ┃     AssertionFailedError thrown at line 23
 *  ┗━
 * </pre>
 */
public final class TestFailureHighlight {

    /** Engine sentinel title — must stay byte-identical to {@code TestSupport.renderFailures}. */
    public static final String HEADER_SENTINEL = "Test Failure";

    /** Engine closer — must stay byte-identical to {@code TestSupport.renderFailures}. */
    public static final String FOOTER_SENTINEL = "Test Failure end";

    /** Heavy vertical box-drawing used as the failure rail (U+2503). */
    public static final String RAIL = "┃";

    private static final Pattern FAILED_LINE = Pattern.compile("^(?<indent>[ \\t]*)FAILED (?<rest>.+)$");
    private static final Pattern MODULE_LINE = Pattern.compile("^module: (?<mod>.+)$");
    private static final Pattern COUNT_LINE = Pattern.compile("^(\\d+) test(s?) failed:?$");
    private static final Pattern THROWN_AT =
            Pattern.compile("^[›\\s]*(?<ex>[A-Za-z_][\\w$]*) thrown at line (?<n>\\d+)\\s*$");

    /** The no-snippet exception locus {@code TestSupport} emits: four spaces + simple type name. */
    private static final Pattern BARE_EXCEPTION = Pattern.compile("^ {4}[A-Z][\\w$]*$");

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
                if (i < lines.size() && FOOTER_SENTINEL.equals(strip(lines.get(i)))) i++;
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

    /**
     * {@code Test Failure in group:artifact › 1 test failed}
     *
     * @param module GA coord or null/blank
     * @param count failure count
     * @param plural {@code true} when count != 1
     */
    public static String paintHeaderLine(String module, int count, boolean plural) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            String m = module == null || module.isBlank() ? "" : " in " + module;
            // ASCII only: plain mode's consumers (dumb terminals, CI logs) are why it exists (JK-1910).
            return "[Test] Failure" + m + " > " + count + " test" + (plural ? "s" : "") + " failed";
        }
        // Same red/white chip as DiagnosticReport Compile Java failures.
        AttributedStyle body = t.withBackground(t.bright(255, 255, 255), t.planFailColor());
        AttributedStyle caps = t.bright(t.planFailColor());
        String pill = Badge.pill("Test", GlobalConfig.nerdFont().pill(), body, caps);
        StringBuilder sb = new StringBuilder();
        // "Failure" is mid-gray — the FAILED badge carries the error color.
        sb.append(pill).append(' ').append(Theme.colorize("Failure", t.midGray()));
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
        out.add(rail("", t)); // blank under header

        // ---- body (red rail around the existing content) -------------------
        ValueRole nextValue = ValueRole.ACTUAL;
        List<String> assertBuf = new ArrayList<>();
        boolean collectingAssert = false;

        while (i < block.size()) {
            String raw = block.get(i);
            if (raw == null) {
                i++;
                continue;
            }

            if (FOOTER_SENTINEL.equals(raw.strip())) {
                break;
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
            if (thrown.matches() || raw.strip().matches("^[›\\s]*[A-Za-z_][\\w$]* thrown at line \\d+\\s*$")) {
                flushAssert(out, assertBuf, collectingAssert, t);
                collectingAssert = false;
                assertBuf.clear();
                out.add(rail(paintThrownAt(raw, t), t));
                i++;
                continue;
            }

            Matcher failed = FAILED_LINE.matcher(raw);
            if (failed.matches() || raw.strip().startsWith("FAILED ")) {
                flushAssert(out, assertBuf, collectingAssert, t);
                collectingAssert = false;
                assertBuf.clear();
                String rest =
                        failed.matches() ? failed.group("rest") : raw.strip().substring("FAILED ".length());
                String failedWord =
                        t.isAnsi() ? Theme.colorize("FAILED", t.error().bold()) : "FAILED";
                out.add(rail(failedWord + " " + paintShortLabel(rest, t), t));
                out.add(rail("", t));
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
                out.add(rail(StackTraceHighlight.line(raw), t));
                i++;
                continue;
            }

            // Bare exception locus on the no-snippet path ("    AssertionFailedError" between the
            // assertion body and the stack frames). It must flush the assertion buffer — buffered,
            // it defeats the AssertJ reformat and paints as an actual-value line (JK-1883). Only a
            // blank-line boundary qualifies, so an indented capitalized token inside a multi-line
            // assertion value stays part of the body.
            if (BARE_EXCEPTION.matcher(raw).matches()
                    && (!collectingAssert
                            || assertBuf.isEmpty()
                            || strip(assertBuf.get(assertBuf.size() - 1)).isEmpty())) {
                while (!assertBuf.isEmpty()
                        && strip(assertBuf.get(assertBuf.size() - 1)).isEmpty()) {
                    assertBuf.remove(assertBuf.size() - 1);
                }
                flushAssert(out, assertBuf, collectingAssert, t);
                collectingAssert = false;
                assertBuf.clear();
                out.add(rail(paintThrownAt(raw, t), t));
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
                out.add(rail("", t));
                i++;
                continue;
            }

            if (!t.isAnsi()) {
                out.add(railPlain(raw));
            } else {
                nextValue = updateValueRole(raw, nextValue);
                out.add(rail(paintFallbackContent(raw, t, nextValue), t));
            }
            i++;
        }
        flushAssert(out, assertBuf, collectingAssert, t);
        out.add(DiagnosticReport.errorFooter());
        return out;
    }

    private static void flushAssert(List<String> out, List<String> assertBuf, boolean collecting, Theme t) {
        if (!collecting || assertBuf.isEmpty()) return;
        List<String> painted = paintAssertionBody(assertBuf, t);
        for (String line : painted) {
            out.add(rail(line == null ? "" : line, t));
        }
        out.add(rail("", t)); // blank after assertion body before source
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
        String path = pathAttr(header);
        String lang = attr(header, "lang");
        SyntaxHighlight.Language language = languageOf(lang);

        // Clamp to the terminal: one over-long source line otherwise pads EVERY row past the
        // width, wrapping continuation rows without the rail and spilling the band (JK-1914).
        // Tabs expand first — the pad math is column-based, and a raw '\t' misaligns the band end.
        int budget = Math.max(40, cc.jumpkick.cli.tui.TerminalSize.columns() - ROW_OVERHEAD);
        List<SrcRow> rows = new ArrayList<>();
        int maxCode = 0;
        for (int i = 1; i < markers.size(); i++) {
            String m = markers.get(i);
            if (m == null || m.equals("@@src-end") || !m.startsWith("@@src ")) continue;
            SrcRow row = parseSrcRow(m);
            if (row == null) continue;
            String code = clampCode(expandTabs(row.code), budget, t.isAnsi());
            row = new SrcRow(row.num, row.error, code);
            rows.add(row);
            maxCode = Math.max(maxCode, row.code.length());
        }

        List<String> out = new ArrayList<>();
        if (!t.isAnsi()) {
            out.add(railPlain(BODY_INDENT + path));
            for (SrcRow row : rows) out.add(railPlain(plainSrcLine(row, maxCode)));
            return out;
        }

        out.add(rail(BODY_INDENT + paintSourcePath(path, header, t), t));
        Rgb pane = CONSOLE_BG;
        for (SrcRow row : rows) {
            out.add(rail(paintSrcLine(row, maxCode, language, t, pane), t));
        }
        return out;
    }

    /**
     * Path color + underline; when the dashboard HTTP surface and project id are known, wrap in an
     * OSC-8 deep link ({@code [link url][path underline]…[/][/]}) to the Monaco files pane.
     */
    static String paintSourcePath(String path, String sourceHeader, Theme t) {
        if (path == null || path.isEmpty()) return "";
        int line = parsePositiveInt(attr(sourceHeader, "line"));
        String url = DashboardCodeLink.urlForSnippet(path, line);
        if (url != null && !url.isBlank()) {
            // RichText owns OSC-8; path + underline match the unlinked Theme.colorize form.
            return RichText.parse("[link " + url + "][path underline]" + RichText.escape(path) + "[/][/]")
                    .render();
        }
        return Theme.colorize(path, t.path().underline());
    }

    private static int parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int n = Integer.parseInt(raw.strip());
            return n > 0 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Visible columns a painted row spends before code: rail {@code " ┃ "} + gutter + bar + gap. */
    private static final int ROW_OVERHEAD = 9;

    private static String expandTabs(String code) {
        if (code == null || code.indexOf('\t') < 0) return code == null ? "" : code;
        StringBuilder sb = new StringBuilder(code.length() + 8);
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\t') {
                do {
                    sb.append(' ');
                } while (sb.length() % 4 != 0);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String clampCode(String code, int budget, boolean ansi) {
        if (code.length() <= budget) return code;
        // Plain mode stays pure ASCII (JK-1910/JK-1949): the clamp ran before the ANSI/plain fork
        // and re-leaked U+2026 into output the ASCII pass had just cleaned. Reserve the marker's
        // own columns, and never cut a surrogate pair in half.
        String ellipsis = ansi ? "…" : "...";
        int cut = Math.max(1, budget - ellipsis.length());
        if (Character.isHighSurrogate(code.charAt(cut - 1))) cut = Math.max(1, cut - 1);
        return code.substring(0, cut) + ellipsis;
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
        return String.format("%4s| %s", row.num, padRight(row.code, maxCode));
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

    /**
     * Human-facing member label: drop package FQCNs so clients never paint wire-shaped names.
     * {@code cc.jumpkick.FooTest.bar(java.nio.file.Path)} → {@code FooTest.bar(Path)}.
     * Preserves a trailing {@code  [wN]} worker tag when present. Leaves ordinary prose, versions,
     * and jar names untouched.
     */
    public static String shortDisplayLabel(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        String worker = "";
        String body = raw.strip();
        int w = body.lastIndexOf("  [w");
        if (w > 0 && body.endsWith("]")) {
            worker = body.substring(w);
            body = body.substring(0, w).strip();
        }
        if (!looksLikeJavaishLabel(body)) {
            return worker.isEmpty() ? body : body + worker;
        }
        body = simplifyMethodParams(body);
        int paren = body.indexOf('(');
        int searchEnd = paren >= 0 ? paren : body.length();
        int dot = body.lastIndexOf('.', searchEnd - 1);
        if (dot > 0 && dot < body.length() - 1) {
            String after = body.substring(dot + 1, searchEnd);
            // method / <init> after the last pre-paren dot → class is everything before it
            if (!after.isEmpty()
                    && (Character.isLowerCase(after.charAt(0)) || after.charAt(0) == '_' || after.startsWith("<"))) {
                String cls = simpleName(body.substring(0, dot));
                String method = simplifyMethodParams(body.substring(dot + 1));
                body = cls.isEmpty() ? method : cls + "." + method;
            } else {
                // package.Class or package.Class(…) — keep suffix from '(' onward
                body = simpleName(body.substring(0, searchEnd)) + body.substring(searchEnd);
            }
        }
        return worker.isEmpty() ? body : body + worker;
    }

    /**
     * True for Java member / type labels we may shorten — not versions, jar names, or free prose.
     * Accepts already-simple {@code FooTest.bar(Path)} and wire-shaped FQCNs.
     */
    static boolean looksLikeJavaishLabel(String body) {
        if (body == null || body.isEmpty()) return false;
        // Spaces are only allowed inside a trailing param list: Foo.bar(A, B).
        int open = body.indexOf('(');
        String head = open >= 0 ? body.substring(0, open) : body;
        if (head.indexOf(' ') >= 0) return false;
        if (open >= 0) {
            int close = body.lastIndexOf(')');
            if (close < open) return false;
            if (close + 1 < body.length() && body.substring(close + 1).indexOf(' ') >= 0) return false;
        }
        char c0 = body.charAt(0);
        if (!(Character.isLetter(c0) || c0 == '_' || c0 == '$')) return false;
        // Param list with a package-looking token, or a package.Class segment.
        if (open >= 0) {
            // method(...) — shorten when params contain dots or the receiver is a type name
            return body.indexOf('.') >= 0 || Character.isUpperCase(c0);
        }
        // Type or Class.method without params: require either a capital segment (type) after a
        // package, or a simple Capitalized identifier / Class.method form.
        if (body.indexOf('.') < 0) {
            return Character.isUpperCase(c0); // FooTest / AssertionFailedError
        }
        // package.Class / package.Class.method / FooTest.bar
        return body.matches("(?:[a-z][\\w$]*\\.)*[A-Z][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?");
    }

    /**
     * Keep {@code (…)} but strip package prefixes inside params: {@code (java.nio.file.Path)} →
     * {@code (Path)}. Arrays / varargs suffixes are preserved ({@code String[]}, {@code Path...}).
     */
    static String simplifyMethodParams(String method) {
        if (method == null || method.isEmpty()) return "";
        int open = method.indexOf('(');
        int close = method.lastIndexOf(')');
        if (open < 0 || close <= open) return method.strip();
        String name = method.substring(0, open).strip();
        String inside = method.substring(open + 1, close).strip();
        String after = method.substring(close + 1);
        if (inside.isEmpty()) return name + "()" + after;
        StringBuilder simplified = new StringBuilder();
        for (String part : inside.split(",")) {
            String p = part.strip();
            String arraySuffix = "";
            while (p.endsWith("...") || p.endsWith("[]")) {
                if (p.endsWith("...")) {
                    arraySuffix = "..." + arraySuffix;
                    p = p.substring(0, p.length() - 3).strip();
                } else {
                    arraySuffix = "[]" + arraySuffix;
                    p = p.substring(0, p.length() - 2).strip();
                }
            }
            int d = p.lastIndexOf('.');
            if (d >= 0) p = p.substring(d + 1);
            if (!simplified.isEmpty()) simplified.append(", ");
            simplified.append(p).append(arraySuffix);
        }
        return name + "(" + simplified + ")" + after;
    }

    /** {@code SimpleClass.method()} / {@code SimpleClass.method(Path)} — type + function roles. */
    static String paintShortLabel(String rest, Theme t) {
        if (rest == null || rest.isEmpty()) return "";
        String shortened = shortDisplayLabel(rest);
        String worker = "";
        String body = shortened;
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
            // Paint method name vs params separately so Path stays a type role when highlighted.
            painted = Theme.colorize(cls, SyntaxHighlight.styleFor(SyntaxHighlight.Role.TYPE))
                    + Theme.colorize(".", t.darkGray())
                    + paintMethodWithParams(method, t);
        } else {
            painted = paintMethodWithParams(body, t);
        }
        if (worker.isEmpty()) return painted;
        return painted + Theme.colorize(worker, t.darkGray());
    }

    /** {@code name(Path, String)} — function name + type-colored simple param names. */
    private static String paintMethodWithParams(String method, Theme t) {
        if (method == null || method.isEmpty()) return "";
        int open = method.indexOf('(');
        int close = method.lastIndexOf(')');
        if (open < 0 || close < open) {
            return Theme.colorize(method, SyntaxHighlight.styleFor(SyntaxHighlight.Role.FUNCTION));
        }
        String name = method.substring(0, open);
        String inside = method.substring(open + 1, close);
        StringBuilder sb = new StringBuilder();
        sb.append(Theme.colorize(name, SyntaxHighlight.styleFor(SyntaxHighlight.Role.FUNCTION)));
        sb.append(Theme.colorize("(", t.darkGray()));
        if (!inside.isEmpty()) {
            String[] parts = inside.split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append(Theme.colorize(",", t.darkGray()));
                String p = parts[i];
                // preserve one leading space after comma when present
                int start = 0;
                while (start < p.length() && p.charAt(start) == ' ') {
                    sb.append(' ');
                    start++;
                }
                String tok = p.substring(start).strip();
                sb.append(Theme.colorize(tok, SyntaxHighlight.styleFor(SyntaxHighlight.Role.TYPE)));
            }
        }
        sb.append(Theme.colorize(")", t.darkGray()));
        if (close + 1 < method.length()) {
            sb.append(Theme.colorize(
                    method.substring(close + 1), SyntaxHighlight.styleFor(SyntaxHighlight.Role.FUNCTION)));
        }
        return sb.toString();
    }

    private static String paintThrownAt(String raw, Theme t) {
        String s = raw.strip().replaceFirst("^[›\\s]+", "");
        Matcher m = THROWN_AT.matcher(s);
        if (!m.matches()) {
            if (!s.isEmpty() && s.indexOf(' ') < 0) {
                return BODY_INDENT + Theme.colorize(simpleName(s), SyntaxHighlight.styleFor(SyntaxHighlight.Role.TYPE));
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

    static String simpleName(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) return "";
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

    /**
     * The {@code path=} value runs to end-of-line (the emitter puts it last so paths with spaces
     * survive the space-delimited header — JK-1905). Old-format headers carried path mid-line;
     * detect the trailing {@code line=} attr and fall back to the first-whitespace cut.
     */
    private static String pathAttr(String header) {
        String needle = "path=";
        int i = header.indexOf(needle);
        if (i < 0) return "";
        String tail = header.substring(i + needle.length());
        if (tail.matches("\\S+ line=\\d+.*")) {
            int sp = tail.indexOf(' ');
            return tail.substring(0, sp);
        }
        return tail.strip();
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
     * End of the report: {@link #FOOTER_SENTINEL} (exclusive), the next {@link #HEADER_SENTINEL},
     * or EOF. A blank-line heuristic would truncate assertion bodies that contain a blank followed
     * by an unindented value, leaking {@code @@source} markers into the terminal.
     */
    static int findBlockEnd(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            String s = strip(lines.get(i));
            if (FOOTER_SENTINEL.equals(s) || HEADER_SENTINEL.equals(s)) return i;
        }
        return lines.size();
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

    private static String rail(String paintedContent, Theme t) {
        if (!t.isAnsi()) return railPlain(paintedContent);
        return " " + Theme.colorize(RAIL, t.error()) + " " + (paintedContent == null ? "" : paintedContent);
    }

    private static String railPlain(String raw) {
        return " | " + (raw == null ? "" : raw);
    }

    public static boolean isHeader(String line) {
        return line != null && HEADER_SENTINEL.equals(line.strip());
    }
}
