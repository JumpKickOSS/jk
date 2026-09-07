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
 * in the tree lane over the corpus; facts measures ({@code methods}, {@code params}, {@code
 * public-members}, {@code cyclomatic}) run in the module lane over the facts index; output measures
 * are the output lane's.
 */
final class MetricEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        TomlTable t = rule.table();
        String measure = String.valueOf(t.getString("measure"));
        String per = t.isString("per") ? String.valueOf(t.getString("per")) : defaultPer(measure);
        Bound bound = Bound.of(t);
        if (bound == null) return Evaluation.failed("one of `cap` or `min` is required");
        return switch (measure) {
            case "lines", "fqcn", "comment-lines" -> textMeasure(rule, ctx, measure, per, bound);
            case "methods", "params", "public-members", "cyclomatic" -> factsMeasure(rule, ctx, measure, per, bound);
            default -> {
                if (measure.startsWith("matches:"))
                    yield matchesMeasure(rule, ctx, measure.substring("matches:".length()), per, bound);
                if (measure.startsWith("coverage.") || measure.equals("jar-size") || measure.equals("native-size"))
                    yield outputMeasure(rule, ctx, measure, bound);
                yield Evaluation.failed("unknown measure `" + measure + "`");
            }
        };
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

    private Evaluation textMeasure(Rule rule, EvalContext ctx, String measure, String per, Bound bound)
            throws IOException {
        List<String> files = ForbidEvaluator.strings(rule.table(), "files");
        Path moduleDir = ctx.moduleDir();
        Path root = ctx.lane() == Lane.MODULE && moduleDir != null ? moduleDir : ctx.root();
        Map<String, Double> perModule = new TreeMap<>();
        Map<String, String> moduleLanguage = new TreeMap<>();
        List<Observation> out = new ArrayList<>();
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        long units = 0;
        for (TextFiles.Entry f : TextFiles.corpus(root)) {
            if (!f.language().code) continue;
            if (files.isEmpty()
                    ? !(f.rel().contains("/src/") || f.rel().startsWith("src/"))
                    : !TextEvaluator.matchesAny(files, f.rel())) continue;
            String ext = extension(f.rel());
            Double limit = bound.limitFor(ext);
            if (limit == null) continue; // a language the cap table does not name
            String text = TextFiles.read(f.file());
            if (text == null) continue;
            Allow allow = allowing(rule.allow(), f.rel());
            if (allow != null) allowUsed.put(allow, true);
            if (measure.equals("comment-lines")) {
                units += commentBlocks(rule, f, text, limit, bound, allow, out);
                continue;
            }
            if (measure.equals("lines") && per.equals("method")) {
                units += methodBodies(f, text, ext, limit, bound, allow, out);
                continue;
            }
            double value = measure.equals("lines") ? CodeText.codeLines(text, ext) : fqcns(text, ext);
            if (per.equals("module")) {
                String module = moduleOf(f.rel());
                perModule.merge(module, value, Double::sum);
                moduleLanguage.putIfAbsent(module, ext);
                continue;
            }
            units++;
            if (allow == null && bound.breached(value, limit)) {
                out.add(Observation.metric(
                        f.rel(), value, f.rel(), measure + " = " + number(value) + " (" + bound.describe(limit) + ")"));
            }
        }
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
        return finish(rule, units, out, allowUsed);
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
            Rule rule,
            TextFiles.Entry f,
            String text,
            double limit,
            Bound bound,
            @Nullable Allow allow,
            List<Observation> out) {
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

    private static Evaluation matchesMeasure(Rule rule, EvalContext ctx, String targetId, String per, Bound bound)
            throws IOException {
        Rule target = ctx.rules().rule(targetId).orElse(null);
        if (target == null) return Evaluation.failed("matches:" + targetId + " names no rule in jk-guards.toml");
        if (target.kind() != Kind.TEXT) return Evaluation.failed("matches:" + targetId + " must name a text rule");
        List<Pattern> patterns = new ArrayList<>();
        TomlTable tt = target.table();
        if (tt.isString("pattern")) patterns.add(Pattern.compile(String.valueOf(tt.getString("pattern"))));
        for (String p : ForbidEvaluator.strings(tt, "patterns")) patterns.add(Pattern.compile(p));
        CodeText.Blank blank = TextFiles.blankMode(tt.isString("blank") ? tt.getString("blank") : null);
        List<String> files = ForbidEvaluator.strings(tt, "files");
        Path moduleDir = ctx.moduleDir();
        Path root = ctx.lane() == Lane.MODULE && moduleDir != null ? moduleDir : ctx.root();
        Map<String, Double> perUnit = new TreeMap<>();
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        for (TextFiles.Entry f : TextFiles.corpus(root)) {
            if (files.isEmpty()
                    ? !(f.rel().contains("/src/") || f.rel().startsWith("src/"))
                    : !TextEvaluator.matchesAny(files, f.rel())) continue;
            String text = TextFiles.read(f.file());
            if (text == null) continue;
            String view =
                    f.language().lexable ? CodeText.blank(text, blank, f.language() == TextFiles.Language.JS) : text;
            double n = 0;
            for (Pattern p : patterns) n += countMatches(p, view);
            String unit = per.equals("module") ? moduleOf(f.rel()) : per.equals("tree") ? "tree" : f.rel();
            perUnit.merge(unit, n, Double::sum);
            if (!per.equals("file")) perUnit.putIfAbsent(unit, 0.0);
        }
        List<Observation> out = new ArrayList<>();
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
        return finish(rule, perUnit.size(), out, allowUsed);
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
        return finish(rule, units, out, allowUsed, ctx.facts(), ctx.module());
    }

    // ---- output -------------------------------------------------------------------------------

    /**
     * {@code jar-size} / {@code native-size} in bytes and {@code coverage.line} / {@code coverage.branch}
     * in percent, one unit per module, read from what the build packaged and the coverage XML it
     * left. A module with no artefact is skipped; no artefact anywhere is {@code not-evaluated},
     * naming the path looked for, so a missing report can never read as a passing floor.
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
                    file = m.existingCoverage();
                    if (file == null) {
                        lookedFor = m.coverage().toString();
                        continue;
                    }
                    Double pct = CoverageReport.percent(file, measure.substring("coverage.".length()));
                    if (pct == null)
                        return Evaluation.failed(file + " has no " + measure.substring("coverage.".length())
                                + " counter; is it a JaCoCo XML report?");
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
            return Evaluation.notEvaluated("no " + (measure.startsWith("coverage.") ? "coverage report" : "artefact")
                    + " this build; looked for " + (lookedFor.isEmpty() ? "a module manifest" : lookedFor));
        }
        return finish(rule, units, out, allowUsed);
    }

    // ---- shared -------------------------------------------------------------------------------

    private static Evaluation finish(Rule rule, long units, List<Observation> out, Map<Allow, Boolean> allowUsed) {
        return finish(rule, units, out, allowUsed, null, "");
    }

    /** Facts measures run per module: an allow naming another module's class is not stale here. */
    private static Evaluation finish(
            Rule rule,
            long units,
            List<Observation> out,
            Map<Allow, Boolean> allowUsed,
            @Nullable FactsIndex facts,
            String module) {
        Map<String, Long> population = Map.of("units", units);
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet()) {
            if (e.getValue()) continue;
            if (facts != null && !ForbidEvaluator.appliesHere(e.getKey(), facts, module)) continue;
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
