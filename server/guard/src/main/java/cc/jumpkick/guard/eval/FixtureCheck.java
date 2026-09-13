// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.guard.schema.Substrate;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Must-bite by fixture: {@code fixture = "guard-fixtures/<id>"} (TOML) or {@code @Fixture} names a
 * directory whose {@code Bad*} files must produce at least one violation and whose {@code Ok*}
 * files none — Semgrep's {@code ruleid:}/{@code ok:} as directories. Bytecode kinds compile their
 * fixtures (the engine does that, once per owning module) and are judged over the fixture's classes
 * alone; text kinds are judged over the snippet text. A guard test may instead hold {@code Bad*}
 * and {@code Ok*} <em>directories</em>: each is a tree the guard runs over as the checkout root,
 * laid over the files beside the cases. Nothing here reads the real tree.
 */
public final class FixtureCheck {

    private FixtureCheck() {}

    /** One rule or guard test and its fixture directory. */
    public record Case(Rule rule, Path dir, String module, boolean guardTest) {
        public String id() {
            return rule.id();
        }

        public boolean compiled() {
            return rule.kind().substrate() != Substrate.TEXT;
        }
    }

    /**
     * A fixture source: a {@code Bad*}/{@code Ok*} file and the classes it declares (outermost binary
     * names) for attribution, or a {@code Bad*}/{@code Ok*} directory — one case that is a tree.
     */
    public record Source(Path file, boolean bad, Set<String> classes) {
        /** A case that is a tree: the guard runs over it as if it were the checkout root. */
        public boolean tree() {
            return Files.isDirectory(file);
        }
    }

    /**
     * What one case came to. {@code skipped} is the guard's own notice — the tool it shells out to is
     * not on this machine — carried through as the lane carries it: not proven, not red.
     */
    public record Verdict(String id, String outcome, String note) {
        public static final String SKIPPED = "skipped";

        public boolean ok() {
            return outcome.equals("bites") || skipped();
        }

        public boolean skipped() {
            return outcome.equals(SKIPPED);
        }
    }

    /** Every TOML rule with a {@code fixture} key and every guard test with {@code @Fixture}. */
    public static List<Case> cases(Path root, RuleSet toml, Map<String, GuardSuites.Located> guards) {
        List<Case> out = new ArrayList<>();
        for (Rule r : toml.rules().values()) {
            if (r.fixture() == null) continue;
            out.add(new Case(r, fixtureDir(root, r), owningModule(r), false));
        }
        for (GuardSuites.Located g : guards.values()) {
            if (g.declared().fixture() == null) continue;
            Rule r = GuardSuites.rule(g.declared(), root, g.module());
            out.add(new Case(r, root.resolve(g.declared().fixture()), g.module(), true));
        }
        return out;
    }

    /**
     * Where a rule's fixture lives: beside a pack's fragment for a pack rule, under the member for a
     * member's rule when it has one there, else under the root as written.
     */
    static Path fixtureDir(Path root, Rule r) {
        String fixture = Objects.requireNonNull(r.fixture(), "fixture");
        return switch (r.source().layer()) {
            case PACK ->
                Objects.requireNonNull(r.source().file().getParent(), "pack dir")
                        .resolve(fixture);
            case MODULE -> {
                Path inMember = r.source().file().getParent() == null
                        ? root.resolve(fixture)
                        : r.source().file().getParent().resolve(fixture);
                yield Files.isDirectory(inMember) ? inMember : root.resolve(fixture);
            }
            case ROOT -> root.resolve(fixture);
        };
    }

    /** The module whose classpath a fixture compiles against: the rule's first exact scope, else the root. */
    static String owningModule(Rule rule) {
        for (String s : rule.scope()) if (!s.contains("*")) return s;
        return "";
    }

    /**
     * {@code Bad*} and {@code Ok*} under a fixture directory: a directory so named is a tree case; a
     * file is judged on its own, with the classes it declares. Anything else is a stub type, or part
     * of the tree every case starts from.
     */
    public static List<Source> sources(Path dir) throws IOException {
        List<Source> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        // One walk: a case directory is noted and pruned, so its files are its tree's, not the fixture's.
        List<Path> cases = new ArrayList<>();
        PathUtil.forEachRegularFile(
                dir,
                d -> {
                    if (!isCase(dir, d)) return false;
                    cases.add(d);
                    return true;
                },
                (f, attrs) -> {
                    String name = f.getFileName().toString();
                    boolean bad = name.startsWith("Bad");
                    boolean ok = name.startsWith("Ok");
                    if (!bad && !ok) return;
                    Set<String> classes = name.endsWith(".java")
                            ? declaredClasses(Files.readString(f, StandardCharsets.UTF_8))
                            : Set.of();
                    out.add(new Source(f, bad, classes));
                });
        for (Path d : cases) out.add(new Source(d, d.getFileName().toString().startsWith("Bad"), Set.of()));
        out.sort((a, b) -> a.file().compareTo(b.file()));
        return out;
    }

    /** A directory the walk met that is a direct child of the fixture directory, named {@code Bad*} or {@code Ok*}. */
    private static boolean isCase(Path dir, Path d) {
        String name = d.getFileName().toString();
        return dir.equals(d.getParent()) && (name.startsWith("Bad") || name.startsWith("Ok"));
    }

    /** Why the sources cannot be judged as trees, or null: a fixture is Bad/Ok files or Bad/Ok directories, not both. */
    public static @Nullable String treeProblem(List<Source> sources) {
        boolean trees = false;
        boolean files = false;
        for (Source s : sources) {
            if (s.tree()) trees = true;
            else files = true;
        }
        return trees && files
                ? "the fixture mixes Bad/Ok files with Bad/Ok directories; a fixture is one form or the other"
                : null;
    }

    /**
     * A tree case as the guard sees it, rebuilt under {@code into}: the files beside the cases — the
     * tree every case starts from — then the case's own laid over them.
     */
    public static void caseTree(Path dir, Source c, Path into) throws IOException {
        PathUtil.copyTree(dir, into, d -> isCase(dir, d), PathUtil.Copy.CLEAN_TARGET);
        PathUtil.copyTree(c.file(), into, PathUtil.Copy.OVERWRITE_ALWAYS);
    }

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|strictfp\\s+)*(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)");

    /** Top-level types a Java source declares, as binary names; nested types share the outermost name. */
    public static Set<String> declaredClasses(String source) {
        Matcher pm = PACKAGE.matcher(source);
        String pkg = pm.find() ? pm.group(1) + "." : "";
        Set<String> out = new TreeSet<>();
        Matcher tm = TYPE.matcher(source);
        while (tm.find()) out.add(pkg + tm.group(1));
        return out;
    }

    /** The slice of a compiled index belonging to {@code classes} (outermost names), nested classes included. */
    public static FactsIndex slice(FactsIndex all, Set<String> classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : all.classList()) {
            String outer = Descriptors.binaryName(Descriptors.outermost(c.name()));
            if (classes.contains(outer)) m.put(c.name(), c);
        }
        return new FactsIndex(m, Map.of(), all.bodyDigest() + ":" + classes.hashCode());
    }

    /** Sites a bytecode rule finds in a fixture slice, judged as that module's lane would. */
    public static Evaluation evaluate(Case c, Path root, FactsIndex slice, @Nullable FactsIndex testSlice) {
        Path moduleDir = c.module().isEmpty() ? root : root.resolve(c.module());
        EvalContext ctx = new EvalContext(
                Lane.MODULE, root, c.module(), moduleDir, List.of(moduleDir), () -> slice, () -> testSlice, List::of);
        try {
            return Evaluators.forKind(c.rule().kind()).evaluate(c.rule(), ctx);
        } catch (Throwable t) {
            return Evaluation.failed(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** The verdict for a text rule over its snippet files. */
    public static Verdict textVerdict(Case c, List<Source> sources) throws IOException {
        if (c.rule().kind() != Kind.TEXT) {
            return new Verdict(
                    c.id(),
                    "error",
                    "fixtures for " + c.rule().kind().id() + " are not supported; give the rule an owner or a hit");
        }
        int badHits = 0;
        int okHits = 0;
        int bad = 0;
        int ok = 0;
        for (Source s : sources) {
            int n = TextEvaluator.snippetHits(
                    c.rule(),
                    Files.readString(s.file(), StandardCharsets.UTF_8),
                    s.file().getFileName().toString());
            if (n < 0) return new Verdict(c.id(), "error", "the rule's pattern does not compile");
            if (s.bad()) {
                bad++;
                badHits += n;
            } else {
                ok++;
                okHits += n;
            }
        }
        return verdict(c.id(), bad, badHits, ok, okHits);
    }

    /** The verdict from what Bad and Ok produced. */
    public static Verdict verdict(String id, int badFiles, int badSites, int okFiles, int okSites) {
        if (badFiles == 0)
            return new Verdict(id, "error", "the fixture has no Bad case; a fixture that cannot fail proves nothing");
        if (badSites == 0)
            return new Verdict(id, "Bad silent", "Bad produced no violation: the rule does not bite where it should");
        if (okSites > 0)
            return new Verdict(id, "Ok fires", okSites + " violation(s) in Ok: the rule is wider than it says");
        return new Verdict(id, "bites", "Bad " + badSites + (okFiles > 0 ? ", Ok 0" : ""));
    }

    /** One line per verdict, then a summary; the text {@code jk guard test} prints. */
    public static String render(List<Verdict> verdicts, List<String> loadErrors) {
        StringBuilder sb = new StringBuilder();
        for (String e : loadErrors) sb.append("load error  ").append(e).append('\n');
        int idW = 4;
        for (Verdict v : verdicts) idW = Math.max(idW, v.id().length());
        for (Verdict v : verdicts) {
            sb.append(v.id());
            sb.append(" ".repeat(idW - v.id().length() + 2))
                    .append(pad(v.outcome(), 12))
                    .append(v.note())
                    .append('\n');
        }
        long failing = verdicts.stream().filter(v -> !v.ok()).count();
        long skipped = verdicts.stream().filter(Verdict::skipped).count();
        if (verdicts.isEmpty() && loadErrors.isEmpty())
            sb.append(
                    "no fixtures: no rule names one (fixture = \"guard-fixtures/<id>\") and no @Guard carries @Fixture\n");
        else
            sb.append('\n')
                    .append(verdicts.size())
                    .append(verdicts.size() == 1 ? " fixture" : " fixtures")
                    .append(failing == 0 ? ", every rule bites" : ", " + failing + " not proven")
                    .append(skipped == 0 ? "" : "; " + skipped + " skipped on this machine")
                    .append(loadErrors.isEmpty() ? "" : "; " + loadErrors.size() + " load error(s)")
                    .append('\n');
        return sb.toString();
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s + " " : s + " ".repeat(w - s.length());
    }
}
