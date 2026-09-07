// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.rules.RuleSource;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;

/**
 * A compiled {@code src/guard} suite as the engine sees it before and after running it. Before: the
 * facts index of the suite's classes says which guards it declares (ids, why, instead, allows,
 * fixture, scope, parameters) — enough for the load checks and to give each guard a {@link Rule}
 * the lane reconciles like a TOML rule. After: the report the runtime appended
 * ({@code target/<module>/guard/report.jsonl}) is what the {@code test} kind's evaluator reads.
 */
public final class GuardSuites {

    static final String SUITE = "cc.jumpkick.guard.api.GuardSuite";
    static final String GUARD = "cc.jumpkick.guard.api.Guard";
    static final String ALLOW = "cc.jumpkick.guard.api.Allow";
    static final String FIXTURE = "cc.jumpkick.guard.api.Fixture";
    static final String TEXT_PARAM = "Lcc/jumpkick/guard/api/Text;";
    public static final String REPORT = "report.jsonl";

    private GuardSuites() {}

    /** One {@code @Guard} as declared. */
    public record Declared(
            String id,
            String why,
            String instead,
            String className,
            String method,
            int line,
            boolean workspace,
            List<Allow> allows,
            @Nullable String fixture,
            boolean readsText) {

        public String source() {
            return className + "#" + method;
        }
    }

    /** The guards a suite index declares, in class then method order. */
    public static List<Declared> declared(FactsIndex suite) {
        List<Declared> out = new ArrayList<>();
        for (ClassFacts c : suite.classList()) {
            boolean workspace = false;
            boolean isSuite = false;
            for (AnnotationFacts a : c.annotations()) {
                if (a.typeName().equals(SUITE)) {
                    isSuite = true;
                    List<String> scope = a.values().getOrDefault("scope", List.of());
                    workspace = !scope.isEmpty() && scope.get(0).equalsIgnoreCase("WORKSPACE");
                }
            }
            if (!isSuite) continue;
            for (MethodFacts m : c.methods()) {
                AnnotationFacts guard = null;
                List<Allow> allows = new ArrayList<>();
                String fixture = null;
                for (AnnotationFacts a : m.annotations()) {
                    if (a.typeName().equals(GUARD)) guard = a;
                    else if (a.typeName().equals(ALLOW)) allows.add(new Allow(first(a, "in"), first(a, "reason")));
                    else if (a.typeName().equals(FIXTURE)) fixture = first(a, "value");
                }
                if (guard == null) continue;
                out.add(new Declared(
                        first(guard, "id"),
                        first(guard, "why"),
                        first(guard, "instead"),
                        c.binaryName(),
                        m.name(),
                        m.firstLine(),
                        workspace,
                        List.copyOf(allows),
                        fixture,
                        m.desc().contains(TEXT_PARAM)));
            }
        }
        return out;
    }

    /**
     * Load errors for a suite against the TOML rules: an id already taken, an id that is not a rule
     * id, a {@code MODULE} suite asking for {@code Text}. Empty means the suite may run.
     */
    public static List<String> loadErrors(List<Declared> declared, RuleSet toml) {
        List<String> errors = new ArrayList<>();
        Map<String, String> seen = new TreeMap<>();
        for (Declared d : declared) {
            if (d.id().isBlank() || !d.id().matches("[a-z0-9][a-z0-9-]*")) {
                errors.add(d.source() + ": @Guard id `" + d.id() + "` must be lower-case letters, digits and hyphens");
                continue;
            }
            if (toml.rules().containsKey(d.id())) {
                errors.add(d.source() + ": @Guard id `" + d.id() + "` is already [guards." + d.id()
                        + "] in jk-guards.toml; one id, one owner");
            }
            String other = seen.put(d.id(), d.source());
            if (other != null) errors.add(d.source() + ": @Guard id `" + d.id() + "` is also declared by " + other);
            if (d.readsText() && !d.workspace()) {
                errors.add(d.source() + ": @Guard `" + d.id()
                        + "` in a Scope.MODULE suite cannot inject Text; the module lane is keyed on its facts, not its"
                        + " sources — declare @GuardSuite(scope = Scope.WORKSPACE)");
            }
            if (d.why().isBlank())
                errors.add(d.source() + ": @Guard `" + d.id()
                        + "` needs why (one sentence: the defect this rule prevents)");
        }
        return errors;
    }

    /** The synthetic rule for a declared guard: kind {@code test}, source = the suite's file and line. */
    public static Rule rule(Declared d, Path root, String module) {
        Path file = root.resolve(module.isEmpty() ? "" : module)
                .resolve("src/guard/java")
                .resolve(d.className().replace('.', '/').replaceAll("\\$.*$", "") + ".java");
        var table = Toml.parse("lane = \"" + (d.workspace() ? "workspace" : "module") + "\"\nsource = \"" + d.source()
                + "\"\nmodule = \"" + module + "\"\n");
        return new Rule(
                d.id(),
                Kind.TEST,
                d.why(),
                d.instead().isBlank() ? null : d.instead(),
                List.of(),
                "guard",
                d.allows(),
                true,
                d.fixture(),
                table,
                new RuleSource(file, Math.max(d.line(), 1), RuleSource.Layer.MODULE));
    }

    /** A declared guard and the module whose suite declares it. */
    public record Located(Declared declared, String module) {}

    /**
     * Every guard the workspace's compiled suites declare, by id, from the {@code guard-guard.idx}
     * each module lane left behind — what freeze, explain and the rule-removed check need without a
     * JVM. A module whose lane has not run yet declares nothing here.
     */
    public static Map<String, Located> declaredAcrossWorkspace(Path root) throws IOException {
        Map<String, Located> out = new TreeMap<>();
        for (Path m : WorkspaceModules.of(root)) {
            Path idx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(root, m), "guard");
            if (!Files.isRegularFile(idx)) continue;
            String rel = WorkspaceModel.rel(root, m);
            for (Declared d : declared(FactsFormat.read(idx))) out.putIfAbsent(d.id(), new Located(d, rel));
        }
        return out;
    }

    /** The module a synthetic rule's suite lives in. */
    public static String moduleOf(Rule rule) {
        String m = rule.table().getString("module");
        return m == null ? "" : m;
    }

    /** Where a module's suite report lives. */
    public static Path report(Path moduleTargetDir) {
        return moduleTargetDir.resolve("guard").resolve(REPORT);
    }

    /** The report's lines by guard id; empty when the suite has not run. */
    public static Map<String, Object> readReport(Path report) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(report)) return out;
        for (String line : Files.readAllLines(report)) {
            if (line.isBlank()) continue;
            Object node = MiniJson.parse(line);
            out.put(MiniJson.str(node, "id"), node);
        }
        return out;
    }

    /** One report line as an evaluation, with the guard's allows applied. */
    @SuppressWarnings("unchecked")
    public static Evaluation evaluate(Rule rule, @Nullable Object line, String module) {
        if (line == null)
            return Evaluation.failed(
                    "the guard suite left no report for `" + rule.id() + "`: the run did not reach it");
        String outcome = MiniJson.str(line, "outcome");
        String error = MiniJson.get(line, "error") == null ? "" : MiniJson.str(line, "error");
        if (outcome.equals("threw")) return Evaluation.failed("the guard threw: " + error);
        if (outcome.equals("owner-missing")) return Evaluation.ownerMissing(error);
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        List<Observation> sites = new ArrayList<>();
        Object violations = MiniJson.get(line, "violations");
        int found = 0;
        for (Object v : violations instanceof List<?> l ? l : List.<Object>of()) {
            found++;
            String fingerprint = MiniJson.str(v, "fingerprint");
            String file = MiniJson.get(v, "file") == null ? null : sourcePath(module, MiniJson.str(v, "file"));
            int at = MiniJson.get(v, "line") instanceof Number n ? n.intValue() : 0;
            String detail = MiniJson.str(v, "detail");
            Allow allow = allowing(rule.allow(), fingerprint, file, module);
            if (allow != null) {
                allowUsed.put(allow, true);
                continue;
            }
            Object value = MiniJson.get(v, "value");
            if (value instanceof Number n) sites.add(Observation.metric(fingerprint, n.doubleValue(), file, detail));
            else sites.add(Observation.site(fingerprint, file, at, detail));
        }
        long examined = MiniJson.get(line, "population") instanceof Number n ? n.longValue() : found;
        Map<String, Long> population = Map.of("examined", examined);
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet())
            if (!e.getValue()) stale.add(e.getKey().in());
        if (!stale.isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    sites,
                    "@Allow matched nothing: " + String.join(", ", stale),
                    true);
        }
        Evaluation ev = Evaluation.of(population, sites);
        return rule.fixture() != null ? ev.withBite(true) : ev.withBite(examined > 0 || found > 0);
    }

    private static @Nullable Allow allowing(
            List<Allow> allows, String fingerprint, @Nullable String file, String module) {
        for (Allow a : allows) {
            String in = a.in();
            if (in.equals(fingerprint) || Rule.globMatches(in, fingerprint)) return a;
            if (file != null && (in.equals(file) || Rule.globMatches(in, file))) return a;
            if (!module.isEmpty() && (in.equals(module) || Rule.globMatches(in, module))) return a;
            // a class glob against the origin half of a bytecode fingerprint
            int arrow = fingerprint.indexOf(" -> ");
            String origin = arrow < 0 ? fingerprint : fingerprint.substring(0, arrow);
            int hash = origin.indexOf('#');
            String cls = hash < 0 ? origin : origin.substring(0, hash);
            if (in.equals(cls) || Rule.globMatches(in, cls)) return a;
        }
        return null;
    }

    static String sourcePath(String module, String sourceRootRelative) {
        return (module.isEmpty() ? "" : module + "/") + "src/main/java/" + sourceRootRelative;
    }

    private static String first(AnnotationFacts a, String key) {
        List<String> v = a.values().get(key);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    /** Whether any declared guard needs every module's facts. */
    public static boolean anyWorkspace(List<Declared> declared) {
        for (Declared d : declared) if (d.workspace()) return true;
        return false;
    }

    /** Ids a suite declares. */
    public static Set<String> ids(List<Declared> declared) {
        Set<String> out = new TreeSet<>();
        for (Declared d : declared) out.add(d.id());
        return out;
    }
}
