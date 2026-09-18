// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.CodeText;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code metric}: a number per unit against a cap (or a floor), ratcheted through the baseline
 * when {@code baseline = true}. A unit over its line is a violation whose value is the observation;
 * the baseline keeps one entry per tolerated unit and the engine lowers it as the number falls — a
 * ratchet never blocks progress and never rots.
 *
 * <p>Text measures ({@code lines}, {@code fqcn}, {@code matches:<rule>}, {@code comment-lines}) run
 * in the tree lane over the corpus, every rule of the lane sharing one pass that reads each file
 * once; facts measures ({@code methods}, {@code params}, {@code public-members}, {@code
 * cyclomatic}) run in the module lane over the facts index; output measures are the output lane's.
 */
final class MetricEvaluator implements BatchEvaluator {

    @Override
    public Map<String, Evaluation> evaluateAll(List<Rule> rules, EvalContext ctx) throws IOException {
        Map<String, Evaluation> out = new LinkedHashMap<>();
        List<TextRun> runs = new ArrayList<>();
        for (Rule rule : rules) {
            TomlTable t = rule.table();
            String measure = String.valueOf(t.getString("measure"));
            String per = t.isString("per") ? String.valueOf(t.getString("per")) : defaultPer(measure);
            Bound bound = Bound.of(t);
            if (bound == null) {
                out.put(rule.id(), Evaluation.failed("one of `cap` or `min` is required"));
                continue;
            }
            switch (measure) {
                case "lines", "fqcn", "comment-lines" -> runs.add(new MeasureRun(rule, measure, per, bound));
                case "methods", "params", "public-members", "cyclomatic" ->
                    out.put(rule.id(), single(rule, () -> factsMeasure(rule, ctx, measure, per, bound)));
                default -> {
                    if (measure.startsWith("matches:")) {
                        TextRun run = matchesRun(rule, ctx, measure.substring("matches:".length()), per, bound);
                        if (run.failure != null) out.put(rule.id(), Evaluation.failed(run.failure));
                        else runs.add(run);
                    } else if (measure.startsWith("coverage.")
                            || measure.startsWith("lint.")
                            || measure.equals("jar-size")
                            || measure.equals("native-size")) {
                        out.put(rule.id(), single(rule, () -> outputMeasure(rule, ctx, measure, bound)));
                    } else {
                        out.put(rule.id(), Evaluation.failed("unknown measure `" + measure + "`"));
                    }
                }
            }
        }
        if (!runs.isEmpty()) {
            Path moduleDir = ctx.moduleDir();
            Path root = ctx.lane() == Lane.MODULE && moduleDir != null ? moduleDir : ctx.root();
            List<TextRun> here = new ArrayList<>();
            for (TextFiles.Entry f : TextFiles.corpus(root)) {
                here.clear();
                for (TextRun run : runs) if (run.failure == null && run.wants(f)) here.add(run);
                if (here.isEmpty()) continue;
                String text = TextFiles.read(f.file());
                if (text == null) continue;
                for (TextRun run : here) {
                    try {
                        run.see(f, text);
                    } catch (DeadlineCharSequence.Expired e) {
                        run.failure = e.getMessage() + " on " + f.rel();
                    } catch (StackOverflowError e) {
                        run.failure = "regex overflowed the stack on " + f.rel();
                    }
                }
            }
            for (TextRun run : runs) {
                out.put(run.rule.id(), run.failure != null ? Evaluation.failed(run.failure) : run.finish());
            }
        }
        return out;
    }

    /** One rule's own measure: a throw is that rule's scanner-failed, and the lane's other rules still run. */
    private static Evaluation single(Rule rule, EvalContext.IoSupplier<Evaluation> body) {
        try {
            return body.get();
        } catch (Exception e) {
            return Evaluation.failed(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private static String defaultPer(String measure) {
        return switch (measure) {
            case "methods", "public-members" -> "class";
            case "params", "cyclomatic" -> "method";
            case "comment-lines" -> "comment";
            default -> "file";
        };
    }

    /** {@code cap} / {@code min}, scalar or per-language table. */
    record Bound(boolean isCap, @Nullable Double scalar, Map<String, Double> byLanguage) {
        static @Nullable Bound of(TomlTable t) {
            boolean cap = t.contains("cap");
            if (!cap && !t.contains("min")) return null;
            String key = cap ? "cap" : "min";
            Object v = t.get(List.of(key));
            if (v instanceof Number n) return new Bound(cap, n.doubleValue(), Map.of());
            if (v instanceof TomlTable table) {
                Map<String, Double> by = new TreeMap<>();
                for (var e : table.toMap().entrySet())
                    if (e.getValue() instanceof Number n) by.put(e.getKey().toLowerCase(Locale.ROOT), n.doubleValue());
                return new Bound(cap, null, by);
            }
            return null;
        }

        @Nullable
        Double limitFor(String language) {
            if (scalar != null) return scalar;
            return byLanguage.get(language);
        }

        boolean breached(double value, double limit) {
            return isCap ? value > limit : value < limit;
        }

        String describe(double limit) {
            return (isCap ? "cap " : "floor ") + number(limit);
        }
    }

    // ---- text ---------------------------------------------------------------------------------

    /**
     * One text rule's share of the lane's single corpus pass: which files it wants, what it does
     * with each one's text, and the evaluation once every file has been seen.
     */
    private abstract static class TextRun {
        final Rule rule;
        final List<String> files;
        final Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        final List<Observation> out = new ArrayList<>();
        long units;

        @Nullable
        String failure;

        TextRun(Rule rule, List<String> files) {
            this.rule = rule;
            this.files = files;
            for (Allow a : rule.allow()) allowUsed.put(a, false);
        }

        /** Under {@code files} when the rule names some, else any file under a source root. */
        boolean inScope(TextFiles.Entry f) {
            return files.isEmpty()
                    ? f.rel().contains("/src/") || f.rel().startsWith("src/")
                    : TextEvaluator.matchesAny(files, f.rel());
        }

        abstract boolean wants(TextFiles.Entry f);

        abstract void see(TextFiles.Entry f, String text);

        abstract Evaluation finish();
    }

    /** {@code lines}, {@code fqcn} and {@code comment-lines}: a number per file, method, comment or module. */
    private static final class MeasureRun extends TextRun {
        final String measure;
        final String per;
        final Bound bound;
        final Map<String, Double> perModule = new TreeMap<>();
        final Map<String, String> moduleLanguage = new TreeMap<>();

        MeasureRun(Rule rule, String measure, String per, Bound bound) {
            super(rule, ForbidEvaluator.strings(rule.table(), "files"));
            this.measure = measure;
            this.per = per;
            this.bound = bound;
        }

        @Override
        boolean wants(TextFiles.Entry f) {
            // a language the cap table does not name is not measured
            return f.language().code && inScope(f) && bound.limitFor(extension(f.rel())) != null;
        }

        @Override
        void see(TextFiles.Entry f, String text) {
            String ext = extension(f.rel());
            double limit = Objects.requireNonNull(bound.limitFor(ext), "limit");
            Allow allow = allowing(rule.allow(), f.rel());
            if (allow != null) allowUsed.put(allow, true);
            if (measure.equals("comment-lines")) {
                units += commentBlocks(f, text, limit, bound, allow, out);
                return;
            }
            if (measure.equals("lines") && per.equals("method")) {
                units += methodBodies(f, text, ext, limit, bound, allow, out);
                return;
            }
            double value = measure.equals("lines") ? CodeText.codeLines(text, ext) : fqcns(text, ext);
            if (per.equals("module")) {
                String module = moduleOf(f.rel());
                perModule.merge(module, value, Double::sum);
                moduleLanguage.putIfAbsent(module, ext);
                return;
            }
            units++;
            if (allow == null && bound.breached(value, limit)) {
                out.add(Observation.metric(
                        f.rel(), value, f.rel(), measure + " = " + number(value) + " (" + bound.describe(limit) + ")"));
            }
        }

        @Override
        Evaluation finish() {
            if (per.equals("module")) {
                for (var e : perModule.entrySet()) {
                    units++;
                    Double limit = bound.limitFor(moduleLanguage.getOrDefault(e.getKey(), ""));
                    if (limit != null
                            && bound.breached(e.getValue(), limit)
                            && allowing(rule.allow(), e.getKey()) == null) {
                        out.add(Observation.metric(
                                e.getKey(),
                                e.getValue(),
                                null,
                                measure + " = " + number(e.getValue()) + " over the module (" + bound.describe(limit)
                                        + ")"));
                    }
                }
            }
            return MetricEvaluator.finish(rule, units, out, allowUsed);
        }
    }

    /**
     * Package-qualified references in code: comments and strings blanked, the file's own {@code
     * import} and {@code package} lines left out — those declare, they do not reference.
     */
    static int fqcns(String text, String ext) {
        String blanked =
                CodeText.blank(text, CodeText.Blank.COMMENTS_AND_STRINGS, ext.equals("js") || ext.equals("mjs"));
        int n = 0;
        for (String line : blanked.split("\n", -1)) {
            String s = line.stripLeading();
            if (s.startsWith("import ") || s.startsWith("package ")) continue;
            n += countMatches(CodeText.FQCN, line);
        }
        return n;
    }

    /**
     * {@code lines} per method or constructor body ({@link CodeText#methodSpans}): the unit is
     * {@code <file>#<name>/<arity>} so an entry survives the member moving within its file; the
     * value counts code lines from the opening brace to the closing one, nested lambdas and local
     * classes included — they are the member's complexity, whoever compiles them.
     */
    private static long methodBodies(
            TextFiles.Entry f,
            String text,
            String ext,
            double limit,
            Bound bound,
            @Nullable Allow allow,
            List<Observation> out) {
        if (!ext.equals("java")) return 0;
        long units = 0;
        for (CodeText.MethodSpan span : CodeText.methodSpans(text)) {
            units++;
            double value = CodeText.codeLines(text.substring(span.open(), span.close() + 1), ext);
            if (allow == null && bound.breached(value, limit)) {
                String unit = f.rel() + "#" + span.name() + "/" + span.arity();
                out.add(Observation.metric(
                        unit,
                        value,
                        f.rel(),
                        span.name() + " is " + number(value) + " code lines from line "
                                + CodeText.lineAt(text, span.open()) + " (" + bound.describe(limit) + ")"));
            }
        }
        return units;
    }

    /** {@code comment-lines} per contiguous comment block: {@code //} runs and each block comment. */
    private static long commentBlocks(
            TextFiles.Entry f, String text, double limit, Bound bound, @Nullable Allow allow, List<Observation> out) {
        if (!f.language().lexable) return 0;
        String comments = CodeText.blank(text, CodeText.Blank.CODE);
        String[] lines = comments.split("\n", -1);
        long blocks = 0;
        int start = -1;
        int count = 0;
        for (int i = 0; i <= lines.length; i++) {
            boolean comment = i < lines.length && !lines[i].isBlank();
            if (comment) {
                if (start < 0) start = i;
                count++;
            } else if (start >= 0) {
                blocks++;
                if (allow == null && bound.breached(count, limit)) {
                    String body = String.join("\n", Arrays.copyOfRange(lines, start, start + count));
                    String key = f.rel() + " | comment "
                            + Hashing.sha256Hex(body.strip()).substring(0, 16);
                    out.add(Observation.metric(
                            key,
                            count,
                            f.rel(),
                            "a " + count + "-line comment block at line " + (start + 1) + " (" + bound.describe(limit)
                                    + ")"));
                }
                start = -1;
                count = 0;
            }
        }
        return blocks;
    }

    /** {@code matches:<rule>}: the named text rule's hits per file, module or tree; its {@code files} scope the pass. */
    private static TextRun matchesRun(Rule rule, EvalContext ctx, String targetId, String per, Bound bound) {
        Rule target = ctx.rules().rule(targetId).orElse(null);
        List<String> files = target == null ? List.of() : ForbidEvaluator.strings(target.table(), "files");
        MatchesRun run = new MatchesRun(rule, files, targetId, per, bound);
        if (target == null) {
            run.failure = "matches:" + targetId + " names no rule in jk-guards.toml";
        } else if (target.kind() != Kind.TEXT) {
            run.failure = "matches:" + targetId + " must name a text rule";
        } else {
            TomlTable tt = target.table();
            if (tt.isString("pattern")) run.patterns.add(Pattern.compile(String.valueOf(tt.getString("pattern"))));
            for (String p : ForbidEvaluator.strings(tt, "patterns")) run.patterns.add(Pattern.compile(p));
            run.blank = TextFiles.blankMode(tt.isString("blank") ? tt.getString("blank") : null);
        }
        return run;
    }

    private static final class MatchesRun extends TextRun {
        final String targetId;
        final String per;
        final Bound bound;
        final List<Pattern> patterns = new ArrayList<>();
        final Map<String, Double> perUnit = new TreeMap<>();
        CodeText.Blank blank = CodeText.Blank.COMMENTS;

        MatchesRun(Rule rule, List<String> files, String targetId, String per, Bound bound) {
            super(rule, files);
            this.targetId = targetId;
            this.per = per;
            this.bound = bound;
        }

        @Override
        boolean wants(TextFiles.Entry f) {
            return inScope(f);
        }

        @Override
        void see(TextFiles.Entry f, String text) {
            String view =
                    f.language().lexable ? CodeText.blank(text, blank, f.language() == TextFiles.Language.JS) : text;
            double n = 0;
            for (Pattern p : patterns) n += countMatches(p, view);
            String unit = per.equals("module") ? moduleOf(f.rel()) : per.equals("tree") ? "tree" : f.rel();
            perUnit.merge(unit, n, Double::sum);
            if (!per.equals("file")) perUnit.putIfAbsent(unit, 0.0);
        }

        @Override
        Evaluation finish() {
            Double limit = bound.limitFor("");
            if (limit == null && bound.scalar == null && !bound.byLanguage.isEmpty())
                limit = bound.byLanguage.values().iterator().next();
            for (var e : perUnit.entrySet()) {
                Allow allow = allowing(rule.allow(), e.getKey());
                if (allow != null) allowUsed.put(allow, true);
                if (limit != null && allow == null && bound.breached(e.getValue(), limit)) {
                    out.add(Observation.metric(
                            e.getKey(),
                            e.getValue(),
                            per.equals("file") ? e.getKey() : null,
                            "matches:" + targetId + " = " + number(e.getValue()) + " (" + bound.describe(limit) + ")"));
                }
            }
            return MetricEvaluator.finish(rule, perUnit.size(), out, allowUsed);
        }
    }

    // ---- facts --------------------------------------------------------------------------------

    private Evaluation factsMeasure(Rule rule, EvalContext ctx, String measure, String per, Bound bound) {
        Double limit = bound.limitFor("");
        if (limit == null) return Evaluation.failed("facts measures take a scalar cap or min");
        List<Observation> out = new ArrayList<>();
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        long units = 0;
        for (ClassFacts c : ctx.facts().classList()) {
            Allow allow = allowing(rule.allow(), c.binaryName());
            if (allow != null) allowUsed.put(allow, true);
            if (measure.equals("methods") || measure.equals("public-members")) {
                units++;
                double value = measure.equals("methods")
                        ? c.methods().stream()
                                .filter(m -> !m.name().startsWith("<"))
                                .count()
                        : c.methods().stream()
                                        .filter(m -> (m.access() & 1) != 0
                                                && !m.name().startsWith("<"))
                                        .count()
                                + c.fields().stream()
                                        .filter(f -> (f.access() & 1) != 0)
                                        .count();
                if (allow == null && bound.breached(value, limit)) {
                    out.add(Observation.metric(
                            c.binaryName(),
                            value,
                            ForbidEvaluator.source(ctx, c),
                            measure + " = " + number(value) + " (" + bound.describe(limit) + ")"));
                }
                continue;
            }
            for (MethodFacts m : c.methods()) {
                if (m.name().startsWith("<")) continue;
                units++;
                double value = measure.equals("params") ? m.parameterCount() : m.branches() + 1;
                if (allow == null && bound.breached(value, limit)) {
                    out.add(Observation.metric(
                            c.binaryName() + "#" + m.member(),
                            value,
                            ForbidEvaluator.source(ctx, c),
                            measure + " = " + number(value) + " in " + c.binaryName() + "#" + m.name() + " ("
                                    + bound.describe(limit) + ")"));
                }
            }
        }
        return finish(rule, units, out, allowUsed, ctx.facts(), ctx);
    }

    // ---- output -------------------------------------------------------------------------------

    /**
     * {@code jar-size} / {@code native-size} in bytes, {@code coverage.line} / {@code coverage.branch}
     * in percent and {@code lint.findings} / {@code lint.<tool>} as a count, one unit per module,
     * read from what the build packaged and the coverage and lint XML it left. A module with no
     * artefact is skipped; no artefact anywhere is {@code not-evaluated}, naming the path looked
     * for, so a missing report can never read as a passing floor.
     */
    private Evaluation outputMeasure(Rule rule, EvalContext ctx, String measure, Bound bound) throws IOException {
        Double limit = bound.limitFor("");
        if (limit == null) return Evaluation.failed("output measures take a scalar cap or min");
        List<OutputArtifacts.Module> modules = OutputArtifacts.of(
                ctx.root(), ctx.modules(), ctx.rules().config().coverageReport());
        List<Observation> out = new ArrayList<>();
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        long units = 0;
        String lookedFor = "";
        for (OutputArtifacts.Module m : modules) {
            Allow allow = allowing(rule.allow(), m.label());
            if (allow != null) allowUsed.put(allow, true);
            Path file;
            double value;
            String what;
            switch (measure) {
                case "jar-size" -> {
                    file = m.existingJar();
                    if (file == null) {
                        lookedFor = m.jar().toString();
                        continue;
                    }
                    value = Files.size(file);
                    what = "jar-size = " + number(value) + " bytes";
                }
                case "native-size" -> {
                    file = m.existingNative();
                    if (file == null) {
                        lookedFor = m.nativeBinary().toString();
                        continue;
                    }
                    value = Files.size(file);
                    what = "native-size = " + number(value) + " bytes";
                }
                default -> {
                    if (measure.startsWith("lint.")) {
                        LintCount count = lintFindings(m, measure.substring("lint.".length()));
                        if (count.file == null) {
                            lookedFor = count.lookedFor;
                            continue;
                        }
                        file = count.file;
                        value = count.findings;
                        what = measure + " = " + number(value);
                        break;
                    }
                    file = m.existingCoverage();
                    if (file == null) {
                        lookedFor = m.coverage().toString();
                        continue;
                    }
                    Double pct = CoverageReport.percent(file, measure.substring("coverage.".length()));
                    if (pct == null) {
                        // A report with no counter of this kind is a module with nothing to cover
                        // — a resources-only module's JaCoCo XML has no classes and no counters.
                        lookedFor = file + " (no " + measure.substring("coverage.".length()) + " counter)";
                        continue;
                    }
                    value = pct;
                    what = measure + " = " + number(value) + "%";
                }
            }
            units++;
            if (allow == null && bound.breached(value, limit)) {
                out.add(Observation.metric(
                        m.label(),
                        value,
                        OutputArtifacts.rel(ctx.root(), file),
                        what + " in " + m.label() + " (" + bound.describe(limit) + ")"));
            }
        }
        if (units == 0) {
            String what = measure.startsWith("coverage.")
                    ? "coverage report"
                    : measure.startsWith("lint.") ? "lint report" : "artefact";
            return Evaluation.notEvaluated("no " + what + " this build; looked for "
                    + (lookedFor.isEmpty() ? "a module manifest" : lookedFor));
        }
        return finish(rule, units, out, allowUsed);
    }

    /** One module's lint count: the file it was read from (the first report, for the sum), or what was looked for. */
    private record LintCount(@Nullable Path file, double findings, String lookedFor) {}

    /**
     * {@code lint.findings} sums every tool's report the module has; {@code lint.<tool>} reads that
     * tool's alone. A module with no report is skipped, naming the first report looked for.
     */
    private static LintCount lintFindings(OutputArtifacts.Module m, String which) throws IOException {
        List<String> tools = which.equals("findings") ? OutputArtifacts.LINT_TOOLS : List.of(which);
        Path first = null;
        double total = 0;
        for (String tool : tools) {
            Path report = m.existingLintReport(tool);
            if (report == null) continue;
            Integer count = LintReport.findings(report);
            if (count == null) {
                throw new IOException(report + " is not a report of " + tool + "'s");
            }
            if (first == null) first = report;
            total += count;
        }
        return new LintCount(first, total, m.lintReport(tools.getFirst()).toString());
    }

    // ---- shared -------------------------------------------------------------------------------

    private static Evaluation finish(Rule rule, long units, List<Observation> out, Map<Allow, Boolean> allowUsed) {
        return finish(rule, units, out, allowUsed, null, null);
    }

    /** Facts measures run per module: an allow naming another module's class is not stale here. */
    private static Evaluation finish(
            Rule rule,
            long units,
            List<Observation> out,
            Map<Allow, Boolean> allowUsed,
            @Nullable FactsIndex facts,
            @Nullable EvalContext ctx) {
        Map<String, Long> population = Map.of("units", units);
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet()) {
            if (e.getValue()) continue;
            if (facts != null && ctx != null && !ForbidEvaluator.appliesHere(e.getKey(), facts, ctx)) continue;
            stale.add(e.getKey().in());
        }
        if (!stale.isEmpty() && units > 0) {
            return new Evaluation(
                    Outcome.STALE_ALLOW, population, out, "allow entries matched nothing: " + String.join(", ", stale));
        }
        return Evaluation.of(population, out);
    }

    static int countMatches(Pattern p, CharSequence text) {
        Matcher m = p.matcher(new DeadlineCharSequence(text, TextEvaluator.DEADLINE_MILLIS));
        int n = 0;
        while (m.find()) {
            if (m.end() == m.start()) {
                if (m.end() >= text.length()) break;
                continue;
            }
            n++;
        }
        return n;
    }

    private static String extension(String rel) {
        int dot = rel.lastIndexOf('.');
        return dot < 0 ? "" : rel.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** {@code a/b/src/...} → {@code a/b}; a root-level file → {@code ""}. */
    static String moduleOf(String rel) {
        int i = rel.indexOf("/src/");
        return i < 0 ? "" : rel.substring(0, i);
    }

    private static @Nullable Allow allowing(List<Allow> allow, String unit) {
        for (Allow a : allow)
            if (Rule.globMatches(a.in(), unit) || unit.equals(a.in()) || unit.startsWith(a.in() + "/")) return a;
        return null;
    }

    static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }
}
