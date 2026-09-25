// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.diagnostic.CompilerLocus;
import cc.jumpkick.test.MarkdownTestReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Terse run report for an agent. Same inputs as {@link JkResultsMarkdown} — the finished {@link
 * BuildRecord}, the suite entries, nothing parsed back out of the markdown. One headline, then
 * one problem per line, capped.
 */
public final class JkResultsAgent {

    /** Written next to {@code jk-results.md}. The bytes are this renderer and nothing else. */
    public static final String FILE_NAME = ProjectBuilds.AGENT;

    /** Problems on the headline report. The rest is one continuation line. */
    public static final int MAX_PROBLEMS = 5;

    /** Project stack frames under one test failure. */
    public static final int MAX_FRAMES = 3;

    /** Snippet lines when a caller asks for the lines around an error, not only the offending one. */
    static final int MAX_SNIPPET = 3;

    /** Tool name in the continuation line. The card is {@code diagnostics}. */
    public static final String DIAGNOSTICS_TOOL = "diagnostics";

    private static final Pattern FRAME =
            Pattern.compile("^at\\s+(?:[\\w.$]+/)?([\\w.$]+)\\.([\\w$<>]+)\\(([^():]+):(\\d+)\\)");

    /** Frames whose class is not the project's. A prefix, so {@code org.junit.} does not hide {@code org.acme}. */
    private static final List<String> LIBRARY = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.junit.",
            "org.opentest4j.",
            "org.hamcrest.",
            "org.assertj.",
            "org.mockito.",
            "org.springframework.",
            "org.apache.",
            "org.gradle.",
            "org.codehaus.",
            "kotlin.",
            "kotlinx.",
            "net.bytebuddy.",
            "com.google.");

    private static final Pattern SOURCE_ROOT =
            Pattern.compile("(?:^|/)src/(?:main|test)/(?:java|kotlin|groovy|scala)/(.+)/[^/]+$");

    private static final Pattern DEPS = Pattern.compile("deps\\((add|remove|pin),\\s*([^)]+)\\)");

    /** A coordinate: {@code jk add} in prose ({@code jk add the dependency}) is not one. */
    private static final Pattern JK_ADD = Pattern.compile("jk add\\s+([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)");

    private JkResultsAgent() {}

    /** The headline report. An OK run is that one line. */
    public static String render(BuildRecord record) {
        return render(record, List.of());
    }

    /**
     * As {@link #render(BuildRecord)}, also reading {@code tests} for a failure the record's
     * diagnostics do not already carry.
     */
    public static String render(BuildRecord record, @Nullable List<MarkdownTestReport.ModuleRun> tests) {
        return render(record, tests, Options.summary());
    }

    /**
     * Problems past the headline cap, or the problems in one file. {@code fullSnippets} prints up
     * to three source lines; the headline report prints only the offending line.
     */
    public static String renderDetails(BuildRecord record, @Nullable String file, int limit, boolean fullSnippets) {
        if (record == null) return "0 diagnostics\n";
        Options opt = new Options(false, fullSnippets, file, Math.max(1, limit));
        String body = body(record, loci(record), List.of(), opt);
        return body.isBlank() ? "0 diagnostics\n" : body;
    }

    /** {@code record.json} as a map (the journal row MCP already parsed). {@code null} when it is not a record. */
    public static @Nullable BuildRecord recordOf(@Nullable Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Json.readMap(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String render(BuildRecord record, @Nullable List<MarkdownTestReport.ModuleRun> tests, Options opt) {
        if (record == null) return "";
        List<Locus> loci = loci(record);
        StringBuilder sb = new StringBuilder();
        sb.append(headline(record, loci, tests)).append('\n');
        if (record.success() && !record.cancelled()) return sb.toString();
        sb.append(body(record, loci, tests, opt));
        String delta = deltaLine(record);
        if (delta != null) sb.append(delta).append('\n');
        return sb.toString();
    }

    private record Options(
            boolean headlineCap,
            boolean fullSnippets,
            @Nullable String file,
            int limit) {
        static Options summary() {
            return new Options(true, false, null, MAX_PROBLEMS);
        }
    }

    private static String headline(
            BuildRecord r, List<Locus> loci, @Nullable List<MarkdownTestReport.ModuleRun> tests) {
        StringBuilder sb = new StringBuilder();
        sb.append(outcome(r)).append(' ').append(kind(r));
        String name = name(r);
        if (!name.isEmpty()) sb.append(' ').append(name);
        String counts = counts(r, loci, tests);
        if (!counts.isEmpty()) sb.append(" · ").append(counts);
        if (r.millis() > 0) sb.append(" · ").append(JkResultsMarkdown.fmtDuration(r.millis()));
        return sb.toString();
    }

    private static String outcome(BuildRecord r) {
        if (r.cancelled()) return "CANCELLED";
        return r.success() ? "OK" : "FAIL";
    }

    private static String kind(BuildRecord r) {
        String k = r.kind();
        return k == null || k.isBlank() ? "build" : k.trim();
    }

    /** Artifact of the single failed module, else the project's artifact, else the directory leaf. */
    private static String name(BuildRecord r) {
        List<BuildRecord.Module> failed = new ArrayList<>();
        for (BuildRecord.Module m : r.modules()) {
            if (!m.success() && !JkResultsStopped.stoppedModule(r, m)) failed.add(m);
        }
        if (failed.size() == 1) {
            String one = artifact(failed.get(0).coord());
            if (!one.isEmpty()) return one;
        }
        String project = artifact(r.coord());
        if (!project.isEmpty()) return project;
        return leaf(r.dir());
    }

    /** {@code g:artifact} or {@code g:artifact:version} → {@code artifact}. */
    private static String artifact(@Nullable String coord) {
        if (coord == null || coord.isBlank()) return "";
        String[] p = coord.split(":");
        if (p.length >= 2 && !p[1].isBlank()) return p[1];
        return p[0].isBlank() ? "" : p[0];
    }

    private static String counts(BuildRecord r, List<Locus> loci, @Nullable List<MarkdownTestReport.ModuleRun> tests) {
        int failed = 0;
        int total = 0;
        BuildRecord.Tests summary = r.tests();
        if (summary != null && summary.total() > 0) {
            failed = (int) summary.failed();
            total = (int) summary.total();
        } else if (tests != null) {
            for (MarkdownTestReport.ModuleRun run : tests) {
                if (run == null || run.entries() == null) continue;
                for (MarkdownTestReport.Entry e : run.entries()) {
                    total++;
                    if (e.isFail()) failed++;
                }
            }
        }
        if (total == 0) {
            int fromDiags = 0;
            for (Locus d : loci) if (d.test()) fromDiags++;
            if (fromDiags > 0) {
                failed = fromDiags;
                total = fromDiags;
            }
        }
        int errors = 0;
        for (Locus d : loci) if (!d.test()) errors++;
        if (total > 0 && failed > 0) return failed + " of " + total + " failed";
        if (errors > 0) return errors + (errors == 1 ? " error" : " errors");
        if (total > 0 && "test".equals(kind(r))) return total + (total == 1 ? " test" : " tests");
        return "";
    }

    private static String body(
            BuildRecord r, List<Locus> loci, @Nullable List<MarkdownTestReport.ModuleRun> tests, Options opt) {
        List<String> blocks = new ArrayList<>();
        List<String> files = new ArrayList<>();
        seenCompile(r, loci, opt, blocks, files);
        seenTests(r, loci, tests, opt, blocks, files);
        seenSteps(r, loci, opt, blocks, files);
        if (blocks.isEmpty()) return "";
        int cap = opt.headlineCap ? MAX_PROBLEMS : opt.limit;
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(cap, blocks.size());
        for (int i = 0; i < shown; i++) sb.append(blocks.get(i));
        int hidden = blocks.size() - shown;
        if (hidden > 0) {
            String file = "";
            for (int i = shown; i < files.size(); i++) {
                if (!files.get(i).isEmpty()) {
                    file = files.get(i);
                    break;
                }
            }
            sb.append('+').append(hidden).append(" more: ").append(DIAGNOSTICS_TOOL);
            if (!file.isEmpty()) sb.append("(file=").append(file).append(')');
            sb.append('\n');
        }
        return sb.toString();
    }

    /** A diagnostic with its locus filled in from the message when the record left the fields empty. */
    private record Locus(
            BuildRecord.Diag diag, String file, int line, int col, String message, boolean test, String step) {}

    private static List<Locus> loci(BuildRecord r) {
        List<Locus> out = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (!JkResultsMarkdown.isError(d) || JkResultsStopped.collateral(r, d)) continue;
            String message = d.message() == null ? "" : d.message();
            String file = d.file() == null ? "" : d.file();
            int line = d.line();
            int col = d.col();
            if (file.isBlank() || line <= 0) {
                CompilerLocus loc = CompilerLocus.parse(message);
                if (loc != null) {
                    if (file.isBlank()) file = loc.file();
                    if (line <= 0) line = loc.line();
                    if (col <= 0) col = loc.col();
                }
            }
            file = rel(file, r.dir());
            String text = JkResultsHints.firstLine(message);
            boolean test = JkResultsMarkdown.isTest(d);
            String step = d.step() == null ? "" : d.step();
            out.add(new Locus(d, file, line, col, text, test, step));
        }
        return out;
    }

    private static void seenCompile(
            BuildRecord r, List<Locus> loci, Options opt, List<String> blocks, List<String> files) {
        List<Locus> src = new ArrayList<>();
        for (Locus d : loci) {
            if (d.test() || d.line() <= 0 || d.file().isEmpty()) continue;
            if (!matches(d.file(), opt.file)) continue;
            src.add(d);
        }
        boolean[] used = new boolean[src.size()];
        for (int i = 0; i < src.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            Locus head = src.get(i);
            List<Locus> more = new ArrayList<>();
            String pkg = pkg(head.file());
            for (int j = i + 1; j < src.size(); j++) {
                if (used[j]) continue;
                Locus other = src.get(j);
                if (!head.message().equals(other.message())) continue;
                boolean sameFile = head.file().equals(other.file());
                boolean samePkg = !pkg.isEmpty() && pkg.equals(pkg(other.file()));
                if (!sameFile && !samePkg) continue;
                used[j] = true;
                more.add(other);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("E ").append(head.file());
            sb.append(':').append(head.line());
            if (head.col() > 0) sb.append(':').append(head.col());
            if (!head.message().isEmpty()) sb.append(' ').append(one(head.message(), r.dir()));
            sb.append('\n');
            appendSnippet(sb, head, opt.fullSnippets, r.dir());
            if (!more.isEmpty()) {
                boolean sameFile = true;
                for (Locus m : more) if (!m.file().equals(head.file())) sameFile = false;
                String where = sameFile ? leaf(head.file()) : pkg;
                if (where.isEmpty()) where = leaf(head.file());
                sb.append("  +")
                        .append(more.size())
                        .append(" more in ")
                        .append(where)
                        .append('\n');
            }
            appendFix(sb, head.diag());
            blocks.add(sb.toString());
            files.add(head.file());
        }
    }

    private static void seenTests(
            BuildRecord r,
            List<Locus> loci,
            @Nullable List<MarkdownTestReport.ModuleRun> tests,
            Options opt,
            List<String> blocks,
            List<String> files) {
        List<String> seen = new ArrayList<>();
        for (Locus d : loci) {
            if (!d.test()) continue;
            String id = testId(d.diag());
            if (id.isEmpty()) continue;
            if (!matches(d.file(), opt.file) && !matches(id, opt.file)) continue;
            seen.add(id);
            blocks.add(testBlock(
                    id, d.message(), d.diag().stack(), d.diag().exceptionClass(), d.file(), d.line(), r.dir()));
            files.add(d.file());
        }
        if (tests == null) return;
        for (MarkdownTestReport.ModuleRun run : tests) {
            if (run == null || run.entries() == null) continue;
            for (MarkdownTestReport.Entry e : run.entries()) {
                if (e == null || !e.isFail()) continue;
                String id = testId(e.className(), e.displayName());
                if (id.isEmpty() || seen.contains(id)) continue;
                String message = e.failureMessage() == null ? "" : e.failureMessage();
                blocks.add(testBlock(id, JkResultsHints.firstLine(message), e.failureStack(), null, "", 0, r.dir()));
                files.add("");
            }
        }
    }

    private static void seenSteps(
            BuildRecord r, List<Locus> loci, Options opt, List<String> blocks, List<String> files) {
        if (opt.file != null && !opt.file.isBlank()) return;
        List<String> covered = new ArrayList<>();
        for (Locus d : loci) if (!d.step().isEmpty()) covered.add(d.step());
        for (Locus d : loci) {
            if (d.test() || d.line() > 0) continue;
            String step = d.step().isEmpty() ? "error" : d.step();
            blocks.add(stepBlock(step, d.diag(), r.dir()));
            files.add("");
            covered.add(step);
        }
        for (var row : JkResultsMarkdown.failedSteps(r)) {
            String step = row.task().name();
            if (step == null || step.isBlank() || covered.contains(step)) continue;
            String who = row.module().isEmpty() ? step : row.module() + " " + step;
            blocks.add("E " + who + ": failed\n");
            files.add("");
        }
    }

    private static String testBlock(
            String id,
            String message,
            @Nullable String stack,
            @Nullable String exception,
            String file,
            int line,
            String projectDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("T ").append(id).append('\n');
        String detail = one(message, projectDir);
        if (detail.isEmpty() && exception != null && !exception.isBlank()) detail = exception.strip();
        if (!detail.isEmpty()) sb.append("  ").append(detail).append('\n');
        int frames = 0;
        if (stack != null && !stack.isBlank()) {
            for (String raw : stack.split("\n", -1)) {
                if (frames >= MAX_FRAMES) break;
                Matcher m = FRAME.matcher(raw.strip());
                if (!m.find()) continue;
                String cls = m.group(1);
                String src = m.group(3);
                int at = Integer.parseInt(m.group(4));
                if (!projectFrame(cls, src)) continue;
                sb.append("  at ").append(leaf(src)).append(':').append(at).append('\n');
                frames++;
            }
        }
        if (frames == 0 && line > 0 && !file.isEmpty()) {
            sb.append("  at ").append(leaf(file)).append(':').append(line).append('\n');
        }
        if (stack != null) {
            String hint = JkResultsCause.hint(stack);
            if (hint != null) sb.append("FIX ").append(one(hint, projectDir)).append('\n');
        }
        return sb.toString();
    }

    private static String stepBlock(String step, BuildRecord.Diag d, String projectDir) {
        String message = d.message() == null ? "" : d.message();
        String rawStack = d.stack();
        String stack = rawStack == null || rawStack.isBlank() ? null : rawStack;
        String cause = JkResultsHints.firstLine(message);
        if (stack != null) {
            String root = JkResultsCause.rootCause(stack);
            if (root != null && !root.isBlank()) cause = root;
        } else if (message.indexOf('\n') >= 0) {
            String better = firstRealLine(message);
            if (!better.isEmpty()) cause = better;
        }
        if (cause.isEmpty()) cause = "failed";
        StringBuilder sb = new StringBuilder();
        sb.append("E ").append(step).append(": ").append(one(cause, projectDir)).append('\n');
        String action = stack == null ? null : JkResultsCause.hint(stack);
        if (action == null) action = secondLine(message, cause);
        if (action != null && !action.isBlank())
            sb.append("  ").append(one(action, projectDir)).append('\n');
        JkResultsHints.Hint hint = JkResultsHints.forDiag(d);
        String fix = hint == null ? null : fixLine(hint, projectDir);
        if (fix != null) sb.append(fix).append('\n');
        return sb.toString();
    }

    /** The line after a banner ({@code Cannot resolve dependencies:}) that names the failure. */
    private static String firstRealLine(String message) {
        String[] lines = message.split("\n", -1);
        String first = "";
        for (String raw : lines) {
            String s = raw.strip();
            if (s.isEmpty()) continue;
            if (first.isEmpty()) {
                first = s;
                if (!s.endsWith(":")) return s;
                continue;
            }
            return s;
        }
        return first;
    }

    private static @Nullable String secondLine(String message, String cause) {
        boolean passed = false;
        for (String raw : message.split("\n", -1)) {
            String s = raw.strip();
            if (s.isEmpty() || s.startsWith("at ")) continue;
            if (!passed) {
                passed = true;
                if (s.equals(cause) || s.endsWith(":")) continue;
                return null;
            }
            if (s.equals(cause)) continue;
            return s;
        }
        return null;
    }

    private static void appendSnippet(StringBuilder sb, Locus d, boolean full, String projectDir) {
        List<String> snip = d.diag().snippet();
        if (snip == null || snip.isEmpty()) {
            if (d.line() <= 0) return;
            if (!full) {
                String line = readSourceLine(projectDir, d.file(), d.line());
                if (line != null)
                    sb.append("  ").append(d.line()).append("| ").append(line).append('\n');
                return;
            }
            int from = Math.max(1, d.line() - 1);
            for (int n = from; n < from + MAX_SNIPPET; n++) {
                String line = readSourceLine(projectDir, d.file(), n);
                if (line == null) break;
                sb.append("  ").append(n).append("| ").append(line).append('\n');
            }
            return;
        }
        int start = d.diag().snippetStart();
        int idx = 0;
        if (start > 0 && d.line() >= start) idx = d.line() - start;
        if (idx < 0 || idx >= snip.size()) idx = 0;
        if (!full) {
            String line = sourceLine(snip.get(idx));
            if (!line.isEmpty())
                sb.append("  ").append(d.line()).append("| ").append(line).append('\n');
            return;
        }
        int from = Math.max(0, idx - 1);
        int to = Math.min(snip.size(), from + MAX_SNIPPET);
        for (int i = from; i < to; i++) {
            int n = start > 0 ? start + i : d.line();
            String line = sourceLine(snip.get(i));
            if (line.isEmpty()) continue;
            sb.append("  ").append(n).append("| ").append(line).append('\n');
        }
    }

    /** The one source line a compile error points at, when the diagnostic stored no snippet. */
    private static @Nullable String readSourceLine(String projectDir, String file, int line) {
        if (line <= 0 || file.isEmpty() || projectDir == null || projectDir.isBlank()) return null;
        Path path = Path.of(projectDir, file);
        if (!Files.isRegularFile(path)) return null;
        try {
            String raw;
            try (var lines = Files.lines(path)) {
                raw = lines.skip(line - 1L).findFirst().orElse(null);
            }
            if (raw == null) return null;
            String text = one(raw, "");
            return text.isEmpty() ? null : text;
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    /** A snippet row is either the source itself or {@code @@src N|source} / {@code @@src N*|source}. */
    private static String sourceLine(String raw) {
        if (raw == null) return "";
        String s = raw.stripTrailing();
        if (s.startsWith("@@")) return "";
        int bar = s.indexOf('|');
        if (s.startsWith("@@src ") && bar > 0) s = s.substring(bar + 1);
        return one(s.strip(), "");
    }

    private static void appendFix(StringBuilder sb, BuildRecord.Diag d) {
        JkResultsHints.Hint hint = JkResultsHints.forDiag(d);
        String line = hint == null ? null : fixLine(hint, "");
        if (line != null) sb.append(line).append('\n');
    }

    /**
     * The hint as one action, or two when it names two coordinates. {@code deps(add|remove|pin, …)}
     * is already the edit; {@code jk add g:a} is the CLI spelling of an add. A hint that only says
     * to add "the dependency" names no coordinate and is not a {@code FIX}.
     */
    private static @Nullable String fixLine(JkResultsHints.Hint hint, String projectDir) {
        String raw = hint.text().replace("`", "");
        String deps = depsLines(raw);
        if (deps != null) return deps;
        List<String> added = new ArrayList<>();
        Matcher add = JK_ADD.matcher(raw);
        while (add.find() && added.size() < 2) {
            if (!added.contains(add.group(1))) added.add(add.group(1));
        }
        if (added.size() == 1) return "FIX deps(add, " + added.get(0) + ")";
        if (added.size() == 2) return "FIX deps(add, " + added.get(0) + ")\nFIX deps(add, " + added.get(1) + ")";
        if (raw.contains("jk add")) return null;
        return "FIX " + one(hint.text().replace('`', ' '), projectDir);
    }

    /** {@code FIX deps(action, coord)} lines already spelled in the hint, at most two. */
    private static @Nullable String depsLines(String raw) {
        List<String> lines = new ArrayList<>();
        Matcher m = DEPS.matcher(raw);
        while (m.find() && lines.size() < 2) {
            String line = "FIX deps(" + m.group(1) + ", " + m.group(2).strip() + ")";
            if (!lines.contains(line)) lines.add(line);
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static String testId(BuildRecord.Diag d) {
        return testId(d.className(), d.method() != null && !d.method().isBlank() ? d.method() : d.test());
    }

    private static String testId(@Nullable String className, @Nullable String method) {
        String cls = className == null ? "" : className.strip();
        String m = method == null ? "" : method.strip();
        if (m.endsWith("()")) m = m.substring(0, m.length() - 2);
        if (cls.isEmpty()) return m;
        if (m.isEmpty()) return cls;
        return cls + "#" + m;
    }

    private static boolean projectFrame(String className, String file) {
        if (file.indexOf(".jar") >= 0) return false;
        for (String prefix : LIBRARY) if (className.startsWith(prefix)) return false;
        String lower = file.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java")
                || lower.endsWith(".kt")
                || lower.endsWith(".kts")
                || lower.endsWith(".groovy")
                || lower.endsWith(".scala");
    }

    /**
     * {@code new N · fixed M} once something was fixed: it tells an agent its last change moved
     * the tree. A first failure says nothing the problem lines do not.
     */
    private static @Nullable String deltaLine(BuildRecord r) {
        JobDelta d = r.delta();
        if (d == null || d.quiet()) return null;
        int neu = count(d.appeared()) + count(d.broke()) + count(d.added());
        int fixed = count(d.gone()) + count(d.fixed());
        if (fixed == 0) return null;
        return "new " + neu + " · fixed " + fixed;
    }

    private static int count(JobDelta.@Nullable Rows rows) {
        return rows == null ? 0 : rows.count();
    }

    private static boolean matches(String file, @Nullable String filter) {
        if (filter == null || filter.isBlank()) return true;
        if (file.isEmpty()) return false;
        String f = filter.replace('\\', '/');
        return file.contains(f);
    }

    /** Package of a source path, or empty when the path is not under a language root. */
    private static String pkg(String file) {
        Matcher m = SOURCE_ROOT.matcher(file.replace('\\', '/'));
        if (!m.find()) return "";
        return m.group(1).replace('/', '.');
    }

    static String rel(String file, @Nullable String projectDir) {
        if (file == null || file.isBlank()) return "";
        String shown = JkResultsMarkdown.displayFile(file, projectDir == null ? "" : projectDir);
        shown = shown.replace('\\', '/');
        if (isAbsolute(shown)) return leaf(shown);
        return shown;
    }

    private static boolean isAbsolute(String path) {
        return path.startsWith("/") || path.matches("[A-Za-z]:/.*");
    }

    private static String one(String s, String projectDir) {
        if (s == null) return "";
        String t =
                s.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
        if (projectDir != null && !projectDir.isBlank()) {
            String dir = projectDir.replace('\\', '/');
            if (!dir.endsWith("/")) dir = dir + "/";
            t = t.replace(dir, "").replace(projectDir, "");
        }
        if (t.length() > 240) t = t.substring(0, 240).strip() + "…";
        return t;
    }

    private static String leaf(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
