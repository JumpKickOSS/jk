// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.diagnostic.CompilerLocus;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a javac / kotlinc / groovyc diagnostic block as a test-failure-shaped report: aligned
 * {@code key: value} rows, a dashboard-linked path, and an editor-style source window. The caret
 * line is consumed for its column and not printed — the error token is red + bold + underline.
 */
public final class CompilerDiagnostic {

    private CompilerDiagnostic() {}

    /** Header and caret shapes come from the shared locus parser — one definition repo-wide. */
    private static final Pattern HEADER = CompilerLocus.HEADER;

    private static final Pattern CARET = CompilerLocus.CARET;

    /**
     * An indented {@code label: value} trailer ({@code symbol:}, {@code location:}, {@code
     * required:}, {@code found:}, …). The key is everything before the first colon.
     */
    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([^:]+):(.*)$");

    /** Colorize a raw compiler block (one diagnostic, or kotlinc's whole batch). */
    public static String render(String rawBlock) {
        return render(rawBlock, "error");
    }

    /** As {@link #render(String)}; {@code severity} keys a colon-less header rest. */
    public static String render(String rawBlock, String severity) {
        if (rawBlock == null || rawBlock.isEmpty()) return "";
        String[] lines = rawBlock.split("\n", -1);
        List<Integer> headers = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (HEADER.matcher(lines[i]).matches()) headers.add(i);
        }
        if (headers.isEmpty()) return rawBlock;
        StringBuilder out = new StringBuilder();
        // One read per file per render pass: a multi-unit block (kotlinc batch, javac's
        // per-file error volley) otherwise re-slurps the same source once per unit.
        Map<String, List<String>> sources = new HashMap<>();
        for (int h = 0; h < headers.size(); h++) {
            int start = headers.get(h);
            int end = h + 1 < headers.size() ? headers.get(h + 1) : lines.length;
            if (out.length() > 0) out.append('\n');
            paintUnit(out, lines, start, end, severity, sources);
        }
        return out.toString();
    }

    private static void paintUnit(
            StringBuilder out,
            String[] lines,
            int start,
            int end,
            String severity,
            Map<String, List<String>> sources) {
        Matcher header = HEADER.matcher(lines[start]);
        if (!header.matches()) {
            out.append(lines[start]);
            return;
        }
        String file = header.group("file");
        int lineNo = CompilerLocus.parsePositive(header.group("line"));
        int col1 = CompilerLocus.parsePositive(header.group("col"));
        String rest = header.group("rest") == null ? "" : header.group("rest").strip();

        List<Kv> kvs = new ArrayList<>();
        if (!rest.isEmpty()) kvs.add(splitKv(rest, severity));
        int caretCol0 = -1;
        String snippet = null;
        List<String> extras = new ArrayList<>();
        for (int i = start + 1; i < end; i++) {
            String line = lines[i];
            if (CARET.matcher(line).matches()) {
                caretCol0 = line.indexOf('^');
                continue;
            }
            if (i + 1 < end && CARET.matcher(lines[i + 1]).matches()) {
                snippet = line;
                continue;
            }
            Matcher kv = KEY_VALUE.matcher(line);
            if (kv.matches() && looksLikeTrailer(kv.group(1))) {
                kvs.add(new Kv(kv.group(1).strip(), kv.group(2).strip()));
                continue;
            }
            if (line != null && !line.isBlank()) extras.add(line);
        }
        int markCol = caretCol0 >= 0 ? caretCol0 : (col1 > 0 ? col1 - 1 : -1);
        int linkCol = markCol >= 0 ? markCol + 1 : col1;

        Theme t = Theme.active();
        int keyWidth = 0;
        for (Kv kv : kvs) keyWidth = Math.max(keyWidth, kv.key.length());
        boolean first = true;
        for (Kv kv : kvs) {
            if (!first) out.append('\n');
            first = false;
            out.append(paintKv(kv, keyWidth, t));
        }
        for (String extra : extras) {
            if (!first) out.append('\n');
            first = false;
            out.append(t.isAnsi() ? Theme.colorize(extra, t.midGray()) : extra);
        }

        String display = PathDisplay.of(Path.of(file));
        SyntaxHighlight.Language lang = languageOf(file);
        List<String> fileLines = readSource(file, sources);
        if (fileLines == null && snippet != null) {
            fileLines = List.of(snippet);
        }
        if (!first) out.append('\n');
        out.append('\n');
        if (fileLines == null) {
            if (t.isAnsi()) {
                out.append("    ")
                        .append(TestFailureHighlight.paintSourcePath(
                                display, file, lineNo, linkCol, t, formatNote(kvs, extras)));
            } else {
                out.append("    ").append(TestFailureHighlight.locusLabel(display, lineNo, linkCol));
            }
            return;
        }
        String note = formatNote(kvs, extras);
        List<String> window =
                TestFailureHighlight.paintSourceWindow(display, file, fileLines, lineNo, markCol, lang, note);
        for (int i = 0; i < window.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(window.get(i));
        }
    }

    private static String paintKv(Kv kv, int keyWidth, Theme t) {
        String key = padLeft(kv.key, keyWidth);
        if (!t.isAnsi()) return key + ": " + kv.value;
        return Theme.colorize(key, t.midGray())
                + Theme.colorize(":", t.darkGray())
                + " "
                + Theme.colorize(kv.value, t.brightWhite());
    }

    /**
     * Trailer keys are a single token (possibly hyphenated). A source line like {@code foo(a: Int)}
     * has spaces before the colon and is not a trailer.
     */
    static boolean looksLikeTrailer(String rawKey) {
        if (rawKey == null) return false;
        String k = rawKey.strip();
        if (k.isEmpty() || k.length() > 24) return false;
        return k.matches("[A-Za-z][A-Za-z0-9_-]*");
    }

    static String formatNote(List<Kv> kvs, List<String> extras) {
        StringBuilder sb = new StringBuilder();
        if (kvs != null) {
            for (Kv kv : kvs) {
                if (kv == null || kv.key.isEmpty()) continue;
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(kv.key).append(": ").append(kv.value == null ? "" : kv.value);
            }
        }
        if (extras != null) {
            for (String extra : extras) {
                if (extra == null || extra.isBlank()) continue;
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(extra.strip());
            }
        }
        return sb.toString();
    }

    /** A colon-less rest keys under the block's own severity, not a hardcoded {@code error}. */
    static Kv splitKv(String rest, String severity) {
        int colon = rest.indexOf(':');
        if (colon <= 0) return new Kv(severity, rest.strip());
        return new Kv(
                rest.substring(0, colon).strip(), rest.substring(colon + 1).strip());
    }

    /** Bound for a source file slurped to paint a five-line window. */
    static final long MAX_SOURCE_BYTES = 1 << 20;

    /** Lines of {@code file}, memoized per render pass — failed reads are memoized too. */
    static List<String> readSource(String file, Map<String, List<String>> memo) {
        if (memo.containsKey(file)) return memo.get(file);
        List<String> lines = readSource(file);
        memo.put(file, lines);
        return lines;
    }

    static List<String> readSource(String file) {
        Path p = resolveSource(file);
        if (p == null) return null;
        try {
            if (Files.size(p) > MAX_SOURCE_BYTES) return null;
            return Files.readAllLines(p);
        } catch (Exception e) {
            return null;
        }
    }

    static Path resolveSource(String file) {
        if (file == null || file.isBlank()) return null;
        String f = file;
        if (f.startsWith("file:")) {
            try {
                f = Path.of(URI.create(f)).toString();
            } catch (RuntimeException ignored) {
                f = f.substring("file:".length());
                if (f.startsWith("//")) f = f.substring(2);
            }
        }
        try {
            Path p = Path.of(f);
            if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize();
            DashboardCodeLink.Scope scope = DashboardCodeLink.current();
            Path base = scope != null && scope.checkoutDir() != null
                    ? scope.checkoutDir()
                    : Path.of("").toAbsolutePath();
            Path rel = base.resolve(f);
            if (Files.isRegularFile(rel)) return rel.toAbsolutePath().normalize();
            if (scope != null && scope.moduleDir() != null) {
                Path m = scope.moduleDir().resolve(f);
                if (Files.isRegularFile(m)) return m.toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // leave unread
        }
        return null;
    }

    static SyntaxHighlight.Language languageOf(String file) {
        if (file == null) return SyntaxHighlight.Language.JAVA;
        if (file.endsWith(".kt") || file.endsWith(".kts")) return SyntaxHighlight.Language.KOTLIN;
        if (file.endsWith(".groovy") || file.endsWith(".gvy") || file.endsWith(".gy")) {
            return SyntaxHighlight.Language.GROOVY;
        }
        return SyntaxHighlight.Language.JAVA;
    }

    private static String padLeft(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    record Kv(String key, String value) {}
}
