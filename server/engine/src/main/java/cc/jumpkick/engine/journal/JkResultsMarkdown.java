// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.test.MarkdownTestReport;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.DirKeys;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * High-level run report ({@code jk-results.md}) for humans and agents. The first screen is the
 * whole invocation: outcome, exit code, and why it failed — not a JUnit rollup. Compiler errors,
 * test crashes, and failed steps all count. {@code details.jsonl} is the exhaustive step log;
 * this file points at it rather than duplicating it.
 *
 * <p>Written next to {@code details.jsonl} in the journal run dir, and as the latest copy at
 * {@code target/jk-results.md}. Not a replacement for JUnit XML under {@code
 * target/reports/test-results/}.
 */
public final class JkResultsMarkdown {

    public static final String FILE_NAME = ProjectBuilds.RESULTS;

    static final int MAX_ERRORS = 40;
    static final int MAX_WARNINGS = 20;
    static final int MAX_STACK_LINES = 24;
    static final int MAX_SNIPPET_LINES = 16;
    static final int MAX_MESSAGE_CHARS = 2_000;
    static final int MAX_FAILED_TASKS = 40;
    static final int MAX_MODULES = 24;
    static final int MAX_PACKAGES = 80;
    static final int MAX_FAILED_TESTS = 40;
    static final int MAX_WHY = 3;
    static final int MAX_WHY_CHARS = 200;

    private JkResultsMarkdown() {}

    /**
     * Write the report into {@code runDir}/{@link #FILE_NAME} (when {@code runDir} is a directory
     * we can create) and {@code latestPath} (typically {@code target/jk-results.md}). Either path
     * may be {@code null}.
     */
    public static void write(BuildRecord record, @Nullable Path runDir, @Nullable Path latestPath) throws IOException {
        write(record, runDir, latestPath, List.of());
    }

    public static void write(
            BuildRecord record,
            @Nullable Path runDir,
            @Nullable Path latestPath,
            List<MarkdownTestReport.ModuleRun> tests)
            throws IOException {
        if (record == null) return;
        Path details = runDir == null ? null : runDir.resolve(ProjectBuilds.DETAILS);
        String md = render(record, details, latestPath, tests);
        if (runDir != null) {
            AtomicWrites.replace(runDir.resolve(FILE_NAME), md);
        }
        if (latestPath != null) {
            AtomicWrites.replace(latestPath, md);
        }
    }

    public static String render(BuildRecord record) {
        return render(record, null, null, List.of());
    }

    public static String render(BuildRecord record, @Nullable Path detailsPath, @Nullable Path latestPath) {
        return render(record, detailsPath, latestPath, List.of());
    }

    public static String render(
            BuildRecord record,
            @Nullable Path detailsPath,
            @Nullable Path latestPath,
            List<MarkdownTestReport.ModuleRun> tests) {
        if (tests == null) tests = List.of();
        StringBuilder sb = new StringBuilder(2_048);
        String outcome = outcome(record);
        sb.append("# jk results — ").append(outcome).append("\n\n");
        appendHeadline(sb, record, outcome);
        appendWhy(sb, record);
        appendCounts(sb, record, tests);
        appendFiles(sb, record, detailsPath, latestPath, tests);
        appendFailures(sb, record, !tests.isEmpty());
        appendGuards(sb, record);
        JkResultsTestsSection.append(sb, record, tests);
        appendDeliverables(sb, record);
        appendFailedSteps(sb, record);
        appendWarnings(sb, record);
        appendModules(sb, record);
        return sb.toString();
    }

    /** A run another build tool performed and jk journaled ({@code jk mvn}). */
    static boolean isExternalTool(@Nullable String kind) {
        return "mvn".equals(kind);
    }

    private static String outcome(BuildRecord r) {
        if (r.cancelled()) return "CANCELLED";
        return r.success() ? "OK" : "FAIL";
    }

    private static void appendHeadline(StringBuilder sb, BuildRecord r, String outcome) {
        sb.append("**").append(outcome).append("**");
        boolean tool = isExternalTool(r.kind());
        if (notBlank(r.kind()) && !tool) sb.append(" · ").append(r.kind());
        String coord = some(r.coord());
        if (coord != null) sb.append(" · `").append(coord).append('`');
        if (r.buildNumber() > 0) sb.append(" · #").append(r.buildNumber());
        if (r.millis() > 0) sb.append(" · ").append(fmtDuration(r.millis()));
        if (r.exitCode() != 0) sb.append(" · **exit ").append(r.exitCode()).append("**");
        else sb.append(" · exit 0");
        if (r.requestId() > 0) sb.append(" · jid ").append(r.requestId());
        sb.append('\n');
        boolean meta = false;
        if (notBlank(r.trigger())) {
            sb.append("trigger: ").append(r.trigger());
            if (notBlank(r.session())) sb.append(" · session: ").append(r.session());
            meta = true;
        }
        if (tool) {
            if (meta) sb.append(" · ");
            sb.append("tool: ").append(r.kind());
            meta = true;
        }
        String commit = some(r.commit());
        if (commit != null) {
            if (meta) sb.append(" · ");
            sb.append("commit: ").append(commit);
            meta = true;
        }
        if (notBlank(r.jkVersion())) {
            if (meta) sb.append(" · ");
            sb.append("jk ").append(r.jkVersion());
            meta = true;
        }
        if (meta) sb.append('\n');
        sb.append('\n');
    }

    private static void appendWhy(StringBuilder sb, BuildRecord r) {
        List<String> why = whyLines(r);
        if (why.isEmpty()) return;
        for (String line : why) sb.append("- ").append(line).append('\n');
        sb.append('\n');
    }

    /**
     * At most {@link #MAX_WHY} lines naming what failed: error diagnostics first, then failed
     * steps, then {@code cancelled} or {@code failed (exit N)}. Empty on a successful run.
     */
    static List<String> whyLines(BuildRecord r) {
        if (r == null || (r.success() && !r.cancelled())) return List.of();
        List<String> out = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (!isError(d) || isGuard(d)) continue;
            out.add(whyLine(d));
            if (out.size() >= MAX_WHY) return List.copyOf(out);
        }
        if (out.isEmpty()) {
            for (Row row : failedSteps(r)) {
                String who = row.module.isEmpty() ? "" : "`" + row.module + "` ";
                out.add(who + "`" + row.task.name() + "` failed");
                if (out.size() >= MAX_WHY) return List.copyOf(out);
            }
        }
        if (!out.isEmpty()) return List.copyOf(out);
        if (r.cancelled()) return List.of("cancelled");
        if (r.exitCode() != 0) return List.of("failed (exit " + r.exitCode() + ")");
        return List.of("failed");
    }

    private static String whyLine(BuildRecord.Diag d) {
        String mod = moduleLabel(d);
        String step = some(d.step());
        String msg = clipOneLine(firstLine(d.message()).strip(), MAX_WHY_CHARS);
        StringBuilder b = new StringBuilder();
        if (!mod.isEmpty()) b.append('`').append(mod).append("` ");
        if (step != null) b.append('`').append(step).append("`");
        if (notBlank(msg)) {
            if (b.length() > 0) b.append(": ");
            b.append(msg);
        } else if (b.length() == 0) {
            return "failed";
        }
        return b.toString().strip();
    }

    private static void appendCounts(StringBuilder sb, BuildRecord r, List<MarkdownTestReport.ModuleRun> tests) {
        List<BuildRecord.Module> modules = r.modules();
        if (modules.size() > 1) {
            long failed = modules.stream().filter(m -> !m.success()).count();
            sb.append("Modules: ").append(modules.size());
            if (failed > 0) sb.append(" (**").append(failed).append(" failed**)");
            else sb.append(" (all ok)");
            sb.append('\n');
        }
        boolean testsLine = JkResultsTestsSection.appendCount(sb, r, tests);
        int errors = 0, warnings = 0;
        boolean coverTests = JkResultsTestsSection.hasTestEntries(tests);
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (isError(d)) {
                if (coverTests && isTest(d)) continue;
                errors++;
            } else if (isWarning(d)) warnings++;
        }
        if (errors > 0 || warnings > 0) {
            sb.append("Diagnostics: ");
            if (errors > 0) sb.append("**").append(errors).append(errors == 1 ? " error**" : " errors**");
            if (errors > 0 && warnings > 0) sb.append(", ");
            if (warnings > 0) sb.append(warnings).append(warnings == 1 ? " warning" : " warnings");
            sb.append('\n');
        }
        BuildRecord.CacheBenefit b = r.benefit();
        if (b != null && b.savedMillis() > 0) {
            sb.append("Cache saved ~").append(fmtDuration(b.savedMillis())).append('\n');
        }
        if (testsLine || errors > 0 || warnings > 0 || modules.size() > 1 || (b != null && b.savedMillis() > 0)) {
            sb.append('\n');
        }
    }

    /** {@code true} when a Tests count line was written. */
    private static void appendFiles(
            StringBuilder sb,
            BuildRecord r,
            @Nullable Path detailsPath,
            @Nullable Path latestPath,
            List<MarkdownTestReport.ModuleRun> tests) {
        sb.append("## Files\n\n");
        sb.append("- High-level report (this file): `")
                .append(pathOr(latestPath, "target/" + FILE_NAME))
                .append("`\n");
        sb.append("- Step-by-step transcript: `")
                .append(pathOr(detailsPath, "details.jsonl"))
                .append("` — JSONL, same shape as `--output json`\n");
        boolean ranTests = JkResultsTestsSection.hasTestEntries(tests)
                || (r.tests() != null && r.tests().total() > 0);
        if (ranTests) {
            sb.append("- JUnit XML: `target/reports/test-results/`\n");
        }
        sb.append('\n');
    }

    private static void appendFailures(StringBuilder sb, BuildRecord r, boolean testsSectionCoversTests) {
        List<BuildRecord.Diag> errors = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (!isError(d)) continue;
            if (testsSectionCoversTests && isTest(d)) continue;
            if (isGuard(d)) continue; // rendered under ## Guards
            errors.add(d);
        }
        if (errors.isEmpty()) return;
        sb.append("## Failures\n\n");
        int shown = 0;
        String lastHeader = "";
        for (BuildRecord.Diag d : errors) {
            if (shown >= MAX_ERRORS) {
                sb.append("_+").append(errors.size() - shown).append(" more errors — see `details.jsonl`._\n\n");
                break;
            }
            String header = failureHeader(d);
            if (!header.equals(lastHeader)) {
                sb.append("### ").append(header).append('\n');
                lastHeader = header;
            }
            appendDiagBody(sb, d, r.dir());
            shown++;
        }
    }

    /** How many guard sites the section names before pointing at the full list. */
    static final int MAX_GUARD_SITES = 10;

    /** A diagnostic a guard lane emitted: its step is one of the lane task names. */
    static boolean isGuard(BuildRecord.Diag d) {
        String step = some(d.step());
        return step != null
                && (step.equals(TaskNames.GUARD)
                        || step.startsWith(TaskNames.GUARD + "-")
                        || step.startsWith(TaskNames.GUARD + ":"));
    }

    /**
     * {@code ## Guards}: the agent view of the house rules. One line when every lane came back
     * clean; on failure, at most {@value #MAX_GUARD_SITES} sites grouped by rule with {@code why}
     * once per group and {@code instead} per site, then a pointer at the full list. Budget ~1,000
     * tokens; the trailer says how to exempt and what never to do.
     */
    private static void appendGuards(StringBuilder sb, BuildRecord r) {
        List<BuildRecord.Task> lanes = new ArrayList<>();
        for (BuildRecord.Task t : r.steps()) {
            String n = t.name();
            if (n.equals(TaskNames.GUARD) || n.startsWith(TaskNames.GUARD + "-")) lanes.add(t);
        }
        List<BuildRecord.Diag> red = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) if (isError(d) && isGuard(d)) red.add(d);
        if (lanes.isEmpty() && red.isEmpty()) return;
        sb.append("## Guards\n\n");
        if (red.isEmpty()) {
            long cached = lanes.stream()
                    .filter(t -> "SKIPPED".equalsIgnoreCase(t.status()))
                    .count();
            sb.append("Guards: clean · ").append(lanes.size()).append(lanes.size() == 1 ? " lane" : " lanes");
            if (cached > 0) sb.append(" (").append(cached).append(" cached)");
            sb.append("\n\n");
            return;
        }
        Map<String, List<BuildRecord.Diag>> byRule = new LinkedHashMap<>();
        for (BuildRecord.Diag d : red)
            byRule.computeIfAbsent(d.code(), k -> new ArrayList<>()).add(d);
        sb.append("**").append(byRule.size()).append(byRule.size() == 1 ? " rule broken**" : " rules broken**");
        sb.append(" (")
                .append(red.size())
                .append(red.size() == 1 ? " site" : " sites")
                .append(")\n\n");
        int shown = 0;
        outer:
        for (var e : byRule.entrySet()) {
            List<BuildRecord.Diag> sites = e.getValue();
            String why = guardField(sites.get(0).message(), "Why:");
            int seen = 0;
            for (BuildRecord.Diag d : sites) seen = Math.max(seen, thrashRuns(d.message()));
            sb.append("### ").append(e.getKey());
            if (!why.isEmpty()) sb.append(" — ").append(why);
            sb.append("  (").append(sites.size()).append(sites.size() == 1 ? " site" : " sites");
            if (seen > 0) sb.append(", seen ").append(seen).append(" builds running");
            sb.append(")\n");
            for (BuildRecord.Diag d : sites) {
                if (shown >= MAX_GUARD_SITES) {
                    sb.append("\n_+").append(red.size() - shown).append(" more — target/jk-guards/_\n");
                    break outer;
                }
                String first = firstLine(d.message()).strip();
                sb.append("- ");
                if (!d.file().isEmpty()) {
                    sb.append('`').append(d.file());
                    if (d.line() > 0) sb.append(':').append(d.line());
                    sb.append("`  ");
                    int colon = first.indexOf(": ");
                    if (colon > 0 && first.startsWith(d.file())) first = first.substring(colon + 2);
                }
                sb.append(first).append('\n');
                String instead = guardField(d.message(), "Instead:");
                if (!instead.isEmpty()) sb.append("  → ").append(instead).append('\n');
                int runs = thrashRuns(d.message());
                if (runs > 0) {
                    sb.append("  This site has failed on ")
                            .append(runs)
                            .append(
                                    " consecutive builds. Stop and ask the user whether an `allow` with a reason is right here.\n");
                }
                shown++;
            }
            sb.append('\n');
        }
        sb.append("To exempt a site, stop and ask the user to add an `allow` entry with a reason to jk-guards.toml. ");
        sb.append(
                "Never edit jk-guards-baseline.toml by hand; never add a suppression comment. Explain: `jk guard explain <rule>`.\n\n");
    }

    /** The consecutive-build count a {@code Thrash:} line carries, or 0 when the site is not thrashing. */
    static int thrashRuns(String message) {
        String thrash = guardField(message, "Thrash:");
        if (thrash.isEmpty()) return 0;
        Matcher n = Pattern.compile("(\\d+) consecutive").matcher(thrash);
        return n.find() ? Integer.parseInt(n.group(1)) : 0;
    }

    /** The value of an indented {@code Label:  value} line in a guard diagnostic, or {@code ""}. */
    static String guardField(String message, String label) {
        for (String line : message.split("\n")) {
            String s = line.strip();
            if (s.startsWith(label)) return s.substring(label.length()).strip();
        }
        return "";
    }

    private static String failureHeader(BuildRecord.Diag d) {
        if (isTest(d)) return "Tests";
        String step = some(d.step());
        if (step == null) step = "error";
        String where = moduleLabel(d);
        return where.isEmpty() ? step : step + " — " + where;
    }

    private static void appendDiagBody(StringBuilder sb, BuildRecord.Diag d, String projectDir) {
        if (isTest(d)) {
            sb.append("- ");
            String ident = testIdentity(d);
            if (!ident.isEmpty()) sb.append('`').append(ident).append("`");
            String thrown = some(d.exceptionClass());
            if (thrown != null) {
                if (!ident.isEmpty()) sb.append(" — ");
                sb.append(thrown);
            }
            sb.append('\n');
        }
        String loc = locus(d, projectDir);
        if (!loc.isEmpty()) sb.append(loc).append('\n');
        String msg = clip(d.message(), MAX_MESSAGE_CHARS);
        if (notBlank(msg) && !isRedundantMessage(d, msg)) {
            fence(sb, msg);
        }
        if (d.snippet() != null && !d.snippet().isEmpty()) {
            List<String> snip = d.snippet();
            int n = Math.min(snip.size(), MAX_SNIPPET_LINES);
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) body.append('\n');
                body.append(snip.get(i));
            }
            if (snip.size() > n) body.append("\n…");
            fence(sb, body.toString());
        }
        String stack = some(d.stack());
        if (stack != null) {
            fence(sb, clipLines(stack, MAX_STACK_LINES));
        }
        sb.append('\n');
    }

    private static boolean isRedundantMessage(BuildRecord.Diag d, String msg) {
        // Test identity already printed; a one-line message that duplicates the exception is noise.
        if (!isTest(d)) return false;
        String one = firstLine(msg);
        String thrown = some(d.exceptionClass());
        return thrown != null && one.equals(thrown);
    }

    private static void appendDeliverables(StringBuilder sb, BuildRecord r) {
        List<Row> rows = new ArrayList<>();
        for (BuildRecord.Task t : r.steps()) {
            if (isDeliverable(t.name())) rows.add(new Row("", t));
        }
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (isDeliverable(t.name())) rows.add(new Row(moduleLabel(m), t));
            }
        }
        if (rows.isEmpty()) return;
        sb.append("## Deliverables\n\n");
        sb.append("| Module | Task | Status | Time |\n|---|---|---|---|\n");
        for (Row row : rows) {
            sb.append("| ")
                    .append(escCell(row.module))
                    .append(" | `")
                    .append(escCell(row.task.name()))
                    .append("` | ")
                    .append(status(row.task.status()))
                    .append(" | ")
                    .append(fmtDuration(row.task.millis()))
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private static void appendFailedSteps(StringBuilder sb, BuildRecord r) {
        List<Row> rows = failedSteps(r);
        int skipped = countSkipped(r);
        if (rows.isEmpty() && skipped == 0) return;
        if (!rows.isEmpty()) {
            sb.append("## Failed steps\n\n");
            sb.append("| Module | Task | Status | Time |\n|---|---|---|---|\n");
            int shown = 0;
            for (Row row : rows) {
                if (shown >= MAX_FAILED_TASKS) {
                    sb.append("| … | +").append(rows.size() - shown).append(" more | | |\n");
                    break;
                }
                sb.append("| ")
                        .append(escCell(row.module))
                        .append(" | `")
                        .append(escCell(row.task.name()))
                        .append("` | ")
                        .append(status(row.task.status()))
                        .append(" | ")
                        .append(fmtDuration(row.task.millis()))
                        .append(" |\n");
                shown++;
            }
            sb.append('\n');
        }
        if (skipped > 0 && (!r.success() || r.cancelled())) {
            sb.append("_").append(skipped).append(" tasks skipped (cache)._\n\n");
        }
    }

    /** FAIL / CANCELLED steps that are not deliverables, root then modules. */
    static List<Row> failedSteps(BuildRecord r) {
        List<Row> rows = new ArrayList<>();
        if (r == null) return rows;
        for (BuildRecord.Task t : r.steps()) {
            if (isFailedStatus(t.status()) && !isDeliverable(t.name())) rows.add(new Row("", t));
        }
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (isFailedStatus(t.status()) && !isDeliverable(t.name())) {
                    rows.add(new Row(moduleLabel(m), t));
                }
            }
        }
        return rows;
    }

    private static int countSkipped(BuildRecord r) {
        int n = 0;
        for (BuildRecord.Task t : r.steps()) {
            if (isSkipped(t.status()) && !isDeliverable(t.name())) n++;
        }
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (isSkipped(t.status()) && !isDeliverable(t.name())) n++;
            }
        }
        return n;
    }

    private static void appendWarnings(StringBuilder sb, BuildRecord r) {
        List<BuildRecord.Diag> warnings = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (isWarning(d)) warnings.add(d);
        }
        if (warnings.isEmpty()) return;
        sb.append("## Warnings\n\n");
        int shown = 0;
        for (BuildRecord.Diag d : warnings) {
            if (shown >= MAX_WARNINGS) {
                sb.append("- _+").append(warnings.size() - shown).append(" more — see `details.jsonl`._\n");
                break;
            }
            sb.append("- ");
            String step = some(d.step());
            if (step != null) sb.append('`').append(step).append("` ");
            String loc = locus(d, r.dir());
            if (!loc.isEmpty()) {
                sb.append(loc);
                if (notBlank(d.message())) sb.append(" — ");
            }
            if (notBlank(d.message())) sb.append(firstLine(clip(d.message(), 400)));
            sb.append('\n');
            shown++;
        }
        sb.append('\n');
    }

    private static void appendModules(StringBuilder sb, BuildRecord r) {
        List<BuildRecord.Module> modules = r.modules();
        if (modules.size() <= 1) return;
        long failed = modules.stream().filter(m -> !m.success()).count();
        if (failed == 0 && modules.size() > MAX_MODULES) return;
        sb.append("## Modules\n\n");
        sb.append("| Module | Outcome | Time |\n|---|---|---|\n");
        int shown = 0;
        // Failed first so a large workspace still surfaces the problem.
        List<BuildRecord.Module> ordered = new ArrayList<>(modules.size());
        for (BuildRecord.Module m : modules) if (!m.success()) ordered.add(m);
        for (BuildRecord.Module m : modules) if (m.success()) ordered.add(m);
        for (BuildRecord.Module m : ordered) {
            if (shown >= MAX_MODULES) {
                sb.append("| … | +").append(modules.size() - shown).append(" more | |\n");
                break;
            }
            sb.append("| ")
                    .append(escCell(moduleLabel(m)))
                    .append(" | ")
                    .append(m.success() ? "OK" : "FAIL")
                    .append(" | ")
                    .append(fmtDuration(m.millis()))
                    .append(" |\n");
            shown++;
        }
        sb.append('\n');
    }

    static boolean isDeliverable(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return "install".equals(n)
                || "publish".equals(n)
                || TaskNames.NATIVE_IMAGE.equals(n)
                || TaskNames.WRITE_IMAGE.equals(n)
                || TaskNames.PACKAGE_JAR.equals(n)
                || TaskNames.PACKAGE_ASSEMBLY.equals(n)
                || TaskNames.PACKAGE_MINIFIED.equals(n)
                || TaskNames.CACHE_INSTALL.equals(n)
                || n.contains(TaskNames.NATIVE_IMAGE)
                || (n.endsWith("-image") && n.contains("write"));
    }

    static boolean isFailedStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String u = status.trim().toUpperCase(Locale.ROOT);
        return "FAIL".equals(u) || "FAILED".equals(u) || "CANCELLED".equals(u) || "CANCELED".equals(u);
    }

    private static boolean isSkipped(String status) {
        return status != null && "SKIPPED".equalsIgnoreCase(status.trim());
    }

    /** True when a {@code run-tests} step failed — including a worker that died before JUnit results. */
    static boolean runTestsFailed(BuildRecord r) {
        if (r == null) return false;
        for (BuildRecord.Task t : r.steps()) {
            if (isRunTestsFail(t)) return true;
        }
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (isRunTestsFail(t)) return true;
            }
        }
        return false;
    }

    private static boolean isRunTestsFail(BuildRecord.Task t) {
        return t != null
                && TaskNames.RUN_TESTS.equals(t.name())
                && t.status() != null
                && "FAIL".equalsIgnoreCase(t.status().trim());
    }

    static boolean isError(BuildRecord.Diag d) {
        return d != null && d.severity() != null && "error".equalsIgnoreCase(d.severity());
    }

    static boolean isWarning(BuildRecord.Diag d) {
        return d != null && d.severity() != null && "warning".equalsIgnoreCase(d.severity());
    }

    static boolean isTest(BuildRecord.Diag d) {
        if (d == null) return false;
        if ("test-failure".equals(d.code())) return true;
        return some(d.test()) != null || some(d.className()) != null || some(d.method()) != null;
    }

    private static String testIdentity(BuildRecord.Diag d) {
        String className = some(d.className());
        String method = some(d.method());
        if (className != null || method != null) {
            StringBuilder b = new StringBuilder();
            if (className != null) b.append(className);
            if (method != null) {
                if (className != null) b.append('.');
                b.append(method);
            }
            String module = some(d.module());
            // TestFailureInfo.label owns the module separator — the markdown must not spell it itself.
            return TestFailureInfo.label(module == null ? "" : module, b.toString(), 0);
        }
        String test = some(d.test());
        return test == null ? "" : test;
    }

    private static String moduleLabel(BuildRecord.Diag d) {
        String module = some(d.module());
        if (module != null) return module;
        return leaf(d.dir());
    }

    private static String moduleLabel(BuildRecord.Module m) {
        String coord = some(m.coord());
        if (coord != null) return coord;
        return leaf(m.dir());
    }

    private static String locus(BuildRecord.Diag d, String projectDir) {
        String file = displayFile(d.file(), projectDir);
        if (file.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        b.append('`').append(file);
        if (d.line() > 0) {
            b.append(':').append(d.line());
            if (d.col() > 0) b.append(':').append(d.col());
        }
        b.append('`');
        return b.toString();
    }

    static String displayFile(String file, String projectDir) {
        if (file == null || file.isBlank()) return "";
        if (projectDir != null && !projectDir.isBlank() && file.startsWith(projectDir)) {
            String rel = file.substring(projectDir.length());
            if (rel.startsWith("/") || rel.startsWith("\\")) rel = rel.substring(1);
            return rel.isEmpty() ? file : rel;
        }
        return file;
    }

    private static String status(String s) {
        if (s == null || s.isBlank()) return "";
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private static String pathOr(@Nullable Path path, String fallback) {
        // Forward slashes in the markdown report so display paths match across OSes; a POSIX
        // backslash name renders verbatim (DirKeys rewrites only real Windows paths).
        return path == null ? fallback : DirKeys.slashes(path.toString());
    }

    private static String leaf(String dir) {
        if (dir == null || dir.isBlank()) return "";
        int slash = Math.max(dir.lastIndexOf('/'), dir.lastIndexOf('\\'));
        return slash < 0 ? dir : dir.substring(slash + 1);
    }

    static String fmtDuration(long ms) {
        if (ms < 0) return "";
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        long sec = Math.round(ms / 1000.0);
        return (sec / 60) + "m " + String.format(Locale.ROOT, "%02ds", sec % 60);
    }

    static void fence(StringBuilder sb, String body) {
        if (body == null || body.isBlank()) return;
        String fence = body.contains("```") ? "~~~~" : "```";
        sb.append(fence).append('\n').append(body);
        if (!body.endsWith("\n")) sb.append('\n');
        sb.append(fence).append('\n');
    }

    static String clip(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n…";
    }

    static String clipOneLine(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "…";
    }

    static String clipLines(String s, int maxLines) {
        if (s == null || s.isEmpty()) return "";
        String[] lines = s.split("\n", -1);
        if (lines.length <= maxLines) return s.trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) b.append('\n');
            b.append(lines[i]);
        }
        b.append("\n…");
        return b.toString();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }

    static String escCell(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }

    /**
     * {@code s} when it has content, else {@code null}. A boolean emptiness predicate tells a
     * reader the value is usable but tells the nullness checker nothing, so the checker has to be
     * given the value back to narrow on.
     */
    private static @Nullable String some(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    private record Row(String module, BuildRecord.Task task) {}
}
