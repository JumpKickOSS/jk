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
import cc.jumpkick.host.CodeText;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.TestSuites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    /** The {@code id =} attribute in a structure view of the annotation body (strings blanked). */
    private static final Pattern GUARD_ID_ATTR = Pattern.compile("(?<![\\w.])id\\s*=");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([a-z0-9][a-z0-9-]*)\"");

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
     * id, a guard without {@code why}. Empty means the suite may run.
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
     * Compiled-suite ids from each module's {@code guard-guard.idx}. Freeze and explain use this; a
     * module whose lane has not run yet is absent. Rule-removed uses {@link #declaredIdsInSource}.
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

    /**
     * Every {@code @Guard} id under {@code src/guard} (and the compact {@code guard/src} twin), from
     * source. The model lane runs before compile-guard, so the compiled index is not the declaration.
     */
    public static Set<String> declaredIdsInSource(Path root) throws IOException {
        Set<String> ids = new TreeSet<>();
        Set<Path> modules = new LinkedHashSet<>();
        modules.add(root);
        modules.addAll(WorkspaceModules.of(root));
        for (Path m : modules) {
            if (!Files.isDirectory(m)) continue;
            for (boolean compact : List.of(false, true)) {
                for (Path src : TestSuites.guardSources(m, compact)) {
                    ids.addAll(idsDeclaredIn(Files.readString(src)));
                }
            }
        }
        return ids;
    }

    /** TOML rule ids plus every {@code @Guard} id in source — the set {@code rule-removed} compares. */
    public static Set<String> liveIds(Path root, Set<String> tomlIds) throws IOException {
        Set<String> live = new TreeSet<>(tomlIds);
        live.addAll(declaredIdsInSource(root));
        return live;
    }

    /**
     * {@code @Guard} ids in one Java source; comments and string literals are not declarations. The
     * id must be a string literal: the bytecode path would read a constant, but this scan is what
     * decides whether a baseline entry still has a live rule, so an id it cannot read is an error,
     * not a silent absence.
     */
    static Set<String> idsDeclaredIn(String source) throws IOException {
        String structure = CodeText.blank(source, CodeText.Blank.COMMENTS_AND_STRINGS);
        String keep = CodeText.blank(source, CodeText.Blank.COMMENTS);
        Set<String> ids = new TreeSet<>();
        int i = 0;
        while (i < structure.length()) {
            int at = structure.indexOf("@Guard", i);
            if (at < 0) break;
            int after = at + 6;
            if ((at > 0 && identChar(structure.charAt(at - 1)))
                    || (after < structure.length() && identChar(structure.charAt(after)))) {
                i = after;
                continue;
            }
            int open = after;
            while (open < structure.length() && Character.isWhitespace(structure.charAt(open))) open++;
            if (open >= structure.length() || structure.charAt(open) != '(') {
                i = after;
                continue;
            }
            int close = matchingParen(structure, open);
            if (close < 0) break;
            Matcher attr = GUARD_ID_ATTR.matcher(structure);
            attr.region(open + 1, close);
            if (attr.find()) {
                // The structure view blanks the literal itself, so step over whitespace in the
                // kept view and read the literal there.
                int from = attr.end();
                while (from < close && Character.isWhitespace(keep.charAt(from))) from++;
                Matcher literal = STRING_LITERAL.matcher(keep);
                literal.region(from, close);
                if (!literal.lookingAt()) {
                    int end = keep.indexOf(',', from);
                    String value = keep.substring(from, end < 0 || end > close ? close : end)
                            .strip();
                    throw new IOException("@Guard id must be a string literal, not `" + value + "`");
                }
                ids.add(literal.group(1));
            }
            i = close + 1;
        }
        return ids;
    }

    private static int matchingParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static boolean identChar(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
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

    /** One report line as an evaluation, with the guard's allows applied; bytecode sites spelled under {@code src/main/java}. */
    public static Evaluation evaluate(Rule rule, @Nullable Object line, String module) {
        return evaluate(rule, line, module, null, null);
    }

    /**
     * One report line as an evaluation, with the guard's allows applied. A bytecode site's file is
     * resolved against the source roots under {@code moduleDir} when the lane has it.
     */
    public static Evaluation evaluate(Rule rule, @Nullable Object line, String module, @Nullable Path moduleDir) {
        return evaluate(rule, line, module, moduleDir, null);
    }

    /**
     * One report line as an evaluation, with the guard's allows applied. With {@code owners} — a
     * workspace-scoped suite — a bytecode site resolves against the roots of the member that
     * compiled its class, and an allow naming a module is judged against that member; without,
     * against {@code moduleDir}, the suite's own module.
     */
    @SuppressWarnings("unchecked")
    public static Evaluation evaluate(
            Rule rule, @Nullable Object line, String module, @Nullable Path moduleDir, @Nullable SiteOwners owners) {
        if (line == null)
            return Evaluation.failed(
                    "the guard suite left no report for `" + rule.id() + "`: the run did not reach it");
        String outcome = MiniJson.str(line, "outcome");
        String reportedError = MiniJson.str(line, "error");
        String error = reportedError == null ? "" : reportedError;
        if ("threw".equals(outcome)) return Evaluation.failed("the guard threw: " + error);
        if ("owner-missing".equals(outcome)) return Evaluation.ownerMissing(error);
        if ("skipped".equals(outcome)) return Evaluation.skipped(error);
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        List<Observation> sites = new ArrayList<>();
        Object violations = MiniJson.get(line, "violations");
        int found = 0;
        for (Object v : violations instanceof List<?> l ? l : List.<Object>of()) {
            found++;
            // the report writer names every site's fingerprint and detail; a line without them is not a report
            String fingerprint = Objects.requireNonNull(MiniJson.str(v, "fingerprint"), "fingerprint");
            String detail = Objects.requireNonNull(MiniJson.str(v, "detail"), "detail");
            Placed placed = place(
                    fingerprint,
                    MiniJson.str(v, "file"),
                    "workspace".equals(MiniJson.str(v, "root")),
                    detail,
                    module,
                    moduleDir,
                    owners);
            int at = MiniJson.get(v, "line") instanceof Number n ? n.intValue() : 0;
            Allow allow = allowing(rule.allow(), fingerprint, placed.file(), placed.module());
            if (allow != null) {
                allowUsed.put(allow, true);
                continue;
            }
            Object value = MiniJson.get(v, "value");
            if (value instanceof Number n)
                sites.add(Observation.metric(fingerprint, n.doubleValue(), placed.file(), placed.detail()));
            else sites.add(Observation.site(fingerprint, placed.file(), at, placed.detail()));
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

    /** A site placed: its file from the workspace root (or none), the module its allows are judged by, its detail. */
    private record Placed(@Nullable String file, String module, String detail) {}

    /**
     * Where a reported site is. A text, tool or metric site names its file from the workspace root.
     * A bytecode site names it under a source root: the owning member's when the suite is
     * workspace-scoped and a member's index holds the class, the suite module's own when that root
     * holds the file, else — rather than a path that exists nowhere — no file, and a detail that says
     * which class went unowned.
     */
    private static Placed place(
            String fingerprint,
            @Nullable String reportedFile,
            boolean fromRoot,
            String detail,
            String module,
            @Nullable Path moduleDir,
            @Nullable SiteOwners owners) {
        if (reportedFile == null) return new Placed(null, module, detail);
        if (fromRoot) return new Placed(reportedFile, module, detail);
        if (owners == null) return new Placed(SourcePaths.resolve(module, moduleDir, reportedFile), module, detail);
        String cls = originClass(fingerprint);
        Path ownerDir = owners.dirOf(cls);
        if (ownerDir != null) {
            String owner = WorkspaceModel.rel(owners.root(), ownerDir);
            return new Placed(SourcePaths.resolve(owner, ownerDir, reportedFile), owner, detail);
        }
        if (moduleDir != null && SourcePaths.rootHolding(moduleDir, reportedFile) != null) {
            return new Placed(SourcePaths.resolve(module, moduleDir, reportedFile), module, detail);
        }
        return new Placed(
                null, module, detail + " (no workspace member's facts index holds " + cls + ": source path unknown)");
    }

    /** The class of a bytecode fingerprint's origin half ({@code a.B#m()V -> …} names {@code a.B}). */
    private static String originClass(String fingerprint) {
        int arrow = fingerprint.indexOf(" -> ");
        String origin = arrow < 0 ? fingerprint : fingerprint.substring(0, arrow);
        int hash = origin.indexOf('#');
        return hash < 0 ? origin : origin.substring(0, hash);
    }

    private static @Nullable Allow allowing(
            List<Allow> allows, String fingerprint, @Nullable String file, String module) {
        String cls = originClass(fingerprint);
        for (Allow a : allows) {
            String in = a.in();
            if (in.equals(fingerprint) || Rule.globMatches(in, fingerprint)) return a;
            if (file != null && (in.equals(file) || Rule.globMatches(in, file))) return a;
            if (!module.isEmpty() && (in.equals(module) || Rule.globMatches(in, module))) return a;
            // a class glob against the origin half of a bytecode fingerprint
            if (in.equals(cls) || Rule.globMatches(in, cls)) return a;
        }
        return null;
    }

    private static String first(AnnotationFacts a, String key) {
        List<String> v = a.values().get(key);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    /** Whether any declared guard reads {@code Text}: the lane then keys on the tree's inputs too. */
    public static boolean anyReadsText(List<Declared> declared) {
        for (Declared d : declared) if (d.readsText()) return true;
        return false;
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
