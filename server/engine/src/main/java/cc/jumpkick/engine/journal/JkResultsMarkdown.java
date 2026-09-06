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
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * High-level run report ({@code jk-results.md}) for humans and agents. Compact by design: outcome,
 * failed/skipped work, compiler and test diagnostics, notable deliverables. The live JSONL
 * transcript ({@code details.jsonl}) is the exhaustive step log — this file points at it rather
 * than duplicating it.
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
        appendCounts(sb, record, tests);
        appendFiles(sb, record, detailsPath, latestPath, tests);
        appendFailures(sb, record, !tests.isEmpty());
        appendGuards(sb, record);
        appendTests(sb, tests);
        appendDeliverables(sb, record);
        appendFailedTasks(sb, record);
        appendWarnings(sb, record);
        appendModules(sb, record);
        return sb.toString();
    }

    private static String outcome(BuildRecord r) {
        if (r.cancelled()) return "CANCELLED";
        return r.success() ? "OK" : "FAIL";
    }

    private static void appendHeadline(StringBuilder sb, BuildRecord r, String outcome) {
        sb.append("**").append(outcome).append("**");
        if (notBlank(r.kind())) sb.append(" · ").append(r.kind());
        String coord = some(r.coord());
        if (coord != null) sb.append(" · `").append(coord).append('`');
        if (r.buildNumber() > 0) sb.append(" · #").append(r.buildNumber());
        if (r.millis() > 0) sb.append(" · ").append(fmtDuration(r.millis()));
        sb.append(" · exit ").append(r.exitCode());
        if (r.requestId() > 0) sb.append(" · jid ").append(r.requestId());
        sb.append('\n');
        boolean meta = false;
        if (notBlank(r.trigger())) {
            sb.append("trigger: ").append(r.trigger());
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

    private static void appendCounts(StringBuilder sb, BuildRecord r, List<MarkdownTestReport.ModuleRun> tests) {
        if (hasTestEntries(tests)) {
            TestRollup roll = rollup(tests);
            int passRate = passRate(roll);
            sb.append("Tests: **").append(passRate).append("%** pass");
            if (roll.fail > 0) sb.append(" · **").append(roll.fail).append(" failed**");
            sb.append(" · ").append(roll.pass).append(" passed");
            if (roll.skip > 0) sb.append(", ").append(roll.skip).append(" skipped");
            sb.append(" (").append(roll.total).append(" total)");
            if (roll.ms > 0) sb.append(" · _took ").append(fmtDuration(roll.ms)).append('_');
            sb.append('\n');
        } else {
            BuildRecord.Tests t = r.tests();
            if (t != null && t.total() > 0) {
                sb.append("Tests: ");
                if (t.failed() > 0) sb.append("**").append(t.failed()).append(" failed**, ");
                sb.append(t.succeeded()).append(" passed");
                if (t.skipped() > 0) sb.append(", ").append(t.skipped()).append(" skipped");
                sb.append(" (").append(t.total()).append(" total)\n");
            }
        }
        int errors = 0, warnings = 0;
        boolean coverTests = hasTestEntries(tests);
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
        List<BuildRecord.Module> modules = r.modules();
        if (modules.size() > 1) {
            long failed = modules.stream().filter(m -> !m.success()).count();
            sb.append("Modules: ").append(modules.size());
            if (failed > 0) sb.append(" (**").append(failed).append(" failed**)");
            else sb.append(" (all ok)");
            sb.append('\n');
        }
        BuildRecord.CacheBenefit b = r.benefit();
        if (b != null && b.savedMillis() > 0) {
            sb.append("Cache saved ~").append(fmtDuration(b.savedMillis())).append('\n');
        }
        boolean testsLine =
                hasTestEntries(tests) || (r.tests() != null && r.tests().total() > 0);
        if (testsLine || errors > 0 || warnings > 0 || modules.size() > 1 || (b != null && b.savedMillis() > 0)) {
            sb.append('\n');
        }
    }

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
        boolean ranTests =
                hasTestEntries(tests) || (r.tests() != null && r.tests().total() > 0);
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
            sb.append("### ").append(e.getKey());
            if (!why.isEmpty()) sb.append(" — ").append(why);
            sb.append("  (")
                    .append(sites.size())
                    .append(sites.size() == 1 ? " site" : " sites")
                    .append(")\n");
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
                shown++;
            }
            sb.append('\n');
        }
        sb.append("To exempt a site, stop and ask the user to add an `allow` entry with a reason to jk-guards.toml. ");
        sb.append(
                "Never edit jk-guards-baseline.toml by hand; never add a suppression comment. Explain: `jk guard explain <rule>`.\n\n");
    }

    /** The value of an indented {@code Label:  value} line in a guard diagnostic, or {@code ""}. */
    static String guardField(String message, String label) {
        for (String line : message.split("\n")) {
            String s = line.strip();
            if (s.startsWith(label)) return s.substring(label.length()).strip();
        }
        return "";
    }

    private static void appendTests(StringBuilder sb, List<MarkdownTestReport.ModuleRun> tests) {
        if (!hasTestEntries(tests)) return;
        TestRollup roll = rollup(tests);
        sb.append("## Tests\n\n");
        int passRate = passRate(roll);
        sb.append("**").append(passRate).append("%** pass rate · ");
        if (roll.fail == 0) {
            sb.append("No failures for **").append(roll.total).append("** ").append(roll.total == 1 ? "test" : "tests");
        } else if (roll.total == 1) {
            sb.append("**1 failure** out of **1** test");
        } else {
            sb.append("**")
                    .append(roll.fail)
                    .append(roll.fail == 1 ? " failure**" : " failures**")
                    .append(" out of **")
                    .append(roll.total)
                    .append("** tests");
        }
        if (roll.ms > 0) sb.append(" · _took ").append(fmtDuration(roll.ms)).append('_');
        sb.append("\n\n");

        boolean multi = tests.size() > 1;
        sb.append(
                multi
                        ? "| Module | Package | Fail | Skip | Pass | Total |\n|---|---|---|---|---|---|\n"
                        : "| Package | Fail | Skip | Pass | Total |\n|---|---|---|---|---|\n");
        Map<String, long[]> byPkg = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (MarkdownTestReport.ModuleRun run : tests) {
            String mod = run.label().isBlank() ? run.scopeKey() : run.label();
            for (MarkdownTestReport.Entry e : run.entries()) {
                String pkg = packageOf(e.className());
                String key = multi ? mod + "\0" + pkg : pkg;
                long[] c = byPkg.computeIfAbsent(key, k -> {
                    order.add(k);
                    return new long[4];
                });
                c[3]++;
                if (e.isFail()) c[0]++;
                else if (e.isSkip()) c[1]++;
                else c[2]++;
            }
        }
        int shownPkg = 0;
        for (String key : order) {
            if (shownPkg >= MAX_PACKAGES) {
                sb.append("| … | +").append(order.size() - shownPkg).append(" more | | |");
                if (multi) sb.append(" |");
                sb.append(" |\n");
                break;
            }
            // `order` only ever holds keys computeIfAbsent put in byPkg, so a miss is a
            // broken invariant rather than a missing row — say so instead of rendering zeros.
            long[] c = Objects.requireNonNull(byPkg.get(key), key);
            if (multi) {
                int split = key.indexOf('\0');
                sb.append("| ")
                        .append(escCell(split < 0 ? key : key.substring(0, split)))
                        .append(" | ")
                        .append(escCell(split < 0 ? key : key.substring(split + 1)));
            } else {
                sb.append("| ").append(escCell(key));
            }
            sb.append(" | ")
                    .append(c[0])
                    .append(" | ")
                    .append(c[1])
                    .append(" | ")
                    .append(c[2])
                    .append(" | ")
                    .append(c[3])
                    .append(" |\n");
            shownPkg++;
        }

        if (roll.fail > 0) {
            sb.append("\n### Failed tests\n");
            int shown = 0;
            for (MarkdownTestReport.ModuleRun run : tests) {
                Map<String, List<MarkdownTestReport.Entry>> byClass = new LinkedHashMap<>();
                for (MarkdownTestReport.Entry e : run.entries()) {
                    if (!e.isFail()) continue;
                    byClass.computeIfAbsent(e.className(), k -> new ArrayList<>())
                            .add(e);
                }
                if (byClass.isEmpty()) continue;
                for (var kv : byClass.entrySet()) {
                    if (shown >= MAX_FAILED_TESTS) break;
                    sb.append("#### ").append(kv.getKey());
                    if (multi && !run.label().isBlank()) sb.append(" — ").append(run.label());
                    sb.append('\n');
                    for (MarkdownTestReport.Entry e : kv.getValue()) {
                        if (shown >= MAX_FAILED_TESTS) break;
                        sb.append("##### `")
                                .append(e.displayName() == null ? "" : e.displayName())
                                .append("`");
                        if (e.durationMs() > 0) {
                            sb.append(" — _took ")
                                    .append(fmtDuration(e.durationMs()))
                                    .append('_');
                        }
                        sb.append('\n');
                        String detail =
                                e.failureStack() != null && !e.failureStack().isBlank()
                                        ? e.failureStack().trim()
                                        : (e.failureMessage() != null
                                                ? e.failureMessage().trim()
                                                : "");
                        if (!detail.isEmpty()) fence(sb, clipLines(detail, MAX_STACK_LINES));
                        shown++;
                    }
                }
            }
            if (roll.fail > shown) {
                sb.append("_+").append(roll.fail - shown).append(" more failed tests — see `details.jsonl`._\n");
            }
        }
        sb.append('\n');
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

    private static void appendFailedTasks(StringBuilder sb, BuildRecord r) {
        List<Row> rows = new ArrayList<>();
        for (BuildRecord.Task t : r.steps()) {
            if (isBadStatus(t.status()) && !isDeliverable(t.name())) rows.add(new Row("", t));
        }
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (isBadStatus(t.status()) && !isDeliverable(t.name())) {
                    rows.add(new Row(moduleLabel(m), t));
                }
            }
        }
        if (rows.isEmpty()) return;
        sb.append("## Failed / skipped tasks\n\n");
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

    static boolean isBadStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String u = status.trim().toUpperCase(Locale.ROOT);
        return "FAIL".equals(u)
                || "FAILED".equals(u)
                || "CANCELLED".equals(u)
                || "CANCELED".equals(u)
                || "SKIPPED".equals(u);
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

    static boolean hasTestEntries(List<MarkdownTestReport.ModuleRun> tests) {
        if (tests == null || tests.isEmpty()) return false;
        for (MarkdownTestReport.ModuleRun r : tests) {
            if (r != null && r.entries() != null && !r.entries().isEmpty()) return true;
        }
        return false;
    }

    private static int passRate(TestRollup roll) {
        if (roll.total == 0) return 100;
        return (int) Math.round((double) (roll.total - roll.fail) / roll.total * 100);
    }

    private static TestRollup rollup(List<MarkdownTestReport.ModuleRun> tests) {
        long fail = 0, skip = 0, pass = 0, ms = 0;
        for (MarkdownTestReport.ModuleRun run : tests) {
            for (MarkdownTestReport.Entry e : run.entries()) {
                if (e.isFail()) fail++;
                else if (e.isSkip()) skip++;
                else pass++;
                ms += Math.max(0, e.durationMs());
            }
        }
        return new TestRollup(fail, skip, pass, fail + skip + pass, ms);
    }

    private static String packageOf(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return "(unknown)";
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(0, dot);
    }

    private record TestRollup(long fail, long skip, long pass, long total, long ms) {}

    static String fmtDuration(long ms) {
        if (ms < 0) return "";
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        long sec = Math.round(ms / 1000.0);
        return (sec / 60) + "m " + String.format(Locale.ROOT, "%02ds", sec % 60);
    }

    private static void fence(StringBuilder sb, String body) {
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

    private static String escCell(String s) {
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
