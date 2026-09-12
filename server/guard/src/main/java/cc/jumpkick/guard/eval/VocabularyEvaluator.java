// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.host.CodeText;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code vocabulary}: the owner's constants, banned as literals everywhere else. "Read the ban list
 * from the owner, never re-type it."
 *
 * <p>Hybrid by necessity: javac inlines a {@code static final String} at every use, so bytecode
 * cannot tell {@code TaskNames.COMPILE_MAIN} from the literal {@code "compile-main"}. The derive
 * half is bytecode (the owner's {@code ConstantValue}s, plus the literals its own {@code <clinit>}
 * hands to its constructor — an enum's names); the scan half is text over every module's sources,
 * each read once for every vocabulary rule of the lane. It runs in the tree lane, after every
 * module lane: the owner is read from whichever module's index holds it, and by then every index
 * is current — a module lane would read a sibling's index before that sibling recompiled. A
 * module-scoped context (tests) scans that one module. {@code inverse = true} adds the arm the
 * owner-derived ban cannot have: literals of the vocabulary's <em>shape</em> that no owner declares
 * ("eight step names had no owner at all").
 */
final class VocabularyEvaluator implements BatchEvaluator {

    private static final Pattern HYPHENATED = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)+");

    /** One rule, resolved against its owner and ready to scan. */
    private static final class Prepared {
        final Rule rule;
        final String owner;
        final boolean inverse;
        final long minLength;
        final Set<String> homonyms;
        final @Nullable Pattern shape;
        final Map<String, String> vocabulary;
        final ClassFacts ownerFacts;
        final boolean all;
        final Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        final Set<Allow> applicable = new HashSet<>();
        final List<Observation> sites = new ArrayList<>();
        final Map<String, Integer> ordinals = new LinkedHashMap<>();
        final Map<String, Observation> unowned = new TreeMap<>();
        long files;
        long literals;

        Prepared(
                Rule rule,
                String owner,
                boolean inverse,
                long minLength,
                Set<String> homonyms,
                @Nullable Pattern shape,
                Map<String, String> vocabulary,
                ClassFacts ownerFacts) {
            this.rule = rule;
            this.owner = owner;
            this.inverse = inverse;
            this.minLength = minLength;
            this.homonyms = homonyms;
            this.shape = shape;
            this.vocabulary = vocabulary;
            this.ownerFacts = ownerFacts;
            this.all = rule.sourceSet().equals("all");
            for (Allow a : rule.allow()) allowUsed.put(a, false);
        }
    }

    @Override
    public Map<String, Evaluation> evaluateAll(List<Rule> rules, EvalContext ctx) throws IOException {
        Map<String, Evaluation> out = new LinkedHashMap<>();
        Path moduleDir = ctx.moduleDir();
        List<Path> moduleDirs = moduleDir != null ? List.of(moduleDir) : ctx.modules();
        if (moduleDirs.isEmpty()) {
            for (Rule r : rules) out.put(r.id(), Evaluation.notEvaluated("no module to scan"));
            return out;
        }
        List<Prepared> live = new ArrayList<>();
        for (Rule rule : rules) {
            Prepared p = prepare(rule, ctx);
            if (p == null) continue;
            live.add(p);
        }
        for (Rule rule : rules) {
            if (live.stream().noneMatch(p -> p.rule == rule)) out.put(rule.id(), ownerProblem(rule, ctx));
        }
        if (live.isEmpty()) return out;

        for (Path m : moduleDirs) {
            String module = moduleDir != null ? ctx.module() : relModule(ctx.root(), m);
            List<Prepared> here = new ArrayList<>();
            Map<Prepared, @Nullable String> ownerSources = new LinkedHashMap<>();
            for (Prepared p : live) {
                if (!p.rule.applies(module)) continue;
                here.add(p);
                ownerSources.put(p, ownerSourceRel(p.ownerFacts, m));
            }
            if (here.isEmpty()) continue;
            // One pass over the module's sources for every rule of the lane.
            for (TextFiles.Entry f : TextFiles.corpus(m)) {
                if (!f.language().code || !f.language().lexable) continue;
                String text = null;
                List<CodeText.Literal> literals = List.of();
                for (Prepared p : here) {
                    if (!inSourceSet(f.rel(), p.all) || f.rel().equals(ownerSources.get(p))) continue;
                    if (text == null) {
                        text = TextFiles.read(f.file());
                        if (text == null) break;
                        literals = CodeText.literals(text);
                    }
                    scan(p, f, text, literals, module, moduleDir == null);
                }
            }
        }
        for (Prepared p : live) out.put(p.rule.id(), finish(p, ctx));
        return out;
    }

    private static String relModule(Path root, Path m) {
        Path r = root.toAbsolutePath().normalize();
        Path mm = m.toAbsolutePath().normalize();
        return r.equals(mm) ? "" : r.relativize(mm).toString().replace('\\', '/');
    }

    private static @Nullable Prepared prepare(Rule rule, EvalContext ctx) {
        TomlTable t = rule.table();
        String owner = String.valueOf(t.getString("owner"));
        String shape = t.isString("shape") ? String.valueOf(t.getString("shape")) : "exact";
        boolean inverse = Boolean.TRUE.equals(t.getBoolean("inverse"));
        long minLength = t.isLong("min-length") ? Long.parseLong(String.valueOf(t.getLong("min-length"))) : 1;
        Set<String> homonyms = new TreeSet<>(ForbidEvaluator.strings(t, "homonyms"));
        Pattern shapePattern = shapePattern(shape);
        ClassFacts ownerFacts = ownerFacts(owner, ctx);
        if (ownerFacts == null) return null;
        Map<String, String> vocabulary = new TreeMap<>();
        for (var e : constants(ownerFacts).entrySet()) {
            String value = e.getKey();
            if (value.length() < minLength || homonyms.contains(value)) continue;
            if (shapePattern != null && !shapePattern.matcher(value).matches()) continue;
            vocabulary.put(value, e.getValue());
        }
        if (vocabulary.isEmpty()) return null;
        return new Prepared(rule, owner, inverse, minLength, homonyms, shapePattern, vocabulary, ownerFacts);
    }

    /** Why a rule could not be prepared: the owner is unknown, or yields no constant of the shape. */
    private static Evaluation ownerProblem(Rule rule, EvalContext ctx) {
        TomlTable t = rule.table();
        String owner = String.valueOf(t.getString("owner"));
        String shape = t.isString("shape") ? String.valueOf(t.getString("shape")) : "exact";
        if (ownerFacts(owner, ctx) == null) {
            return Evaluation.ownerMissing(
                    "owner " + owner + " resolves on neither this module, its classpath, the JDK nor any module index");
        }
        return Evaluation.ownerMissing("owner " + owner + " yields no constant of shape `" + shape
                + "`; the rule has lost the owner it reads");
    }

    private static void scan(
            Prepared p, TextFiles.Entry f, String text, List<CodeText.Literal> literals, String module, boolean tree) {
        p.files++;
        Allow allow = allowing(p.rule.allow(), f.rel(), module);
        if (allow != null) p.applicable.add(allow);
        // The tree lane names files from the workspace root; a module context keeps module-relative paths.
        String where = tree && !module.isEmpty() ? module + "/" + f.rel() : f.rel();
        for (CodeText.Literal l : literals) {
            p.literals++;
            String value = l.body();
            String constant = p.vocabulary.get(value);
            if (constant != null) {
                if (allow != null) {
                    p.allowUsed.put(allow, true);
                    continue;
                }
                String base = where + " | " + Hashing.sha256Hex(value).substring(0, 16);
                int ordinal = p.ordinals.merge(base, 1, Integer::sum);
                p.sites.add(Observation.site(
                        base + " | " + ordinal,
                        where,
                        CodeText.lineAt(text, l.start()),
                        "\"" + value + "\" typed as a literal; the owner spells it " + simpleName(p.owner) + "."
                                + constant));
            } else if (p.inverse
                    && p.shape != null
                    && p.shape.matcher(value).matches()
                    && !p.homonyms.contains(value)
                    && value.length() >= p.minLength) {
                if (allow != null) {
                    p.allowUsed.put(allow, true);
                    continue;
                }
                p.unowned.putIfAbsent(
                        value,
                        Observation.site(
                                "unowned | " + value,
                                where,
                                CodeText.lineAt(text, l.start()),
                                "\"" + value
                                        + "\" has the shape of the vocabulary but no owner declares it — declare it in "
                                        + simpleName(p.owner) + " or name it a homonym"));
            }
        }
    }

    private static Evaluation finish(Prepared p, EvalContext ctx) {
        List<Observation> sites = new ArrayList<>(p.sites);
        sites.addAll(p.unowned.values());
        Map<String, Long> population =
                Map.of("files", p.files, "literals", p.literals, "constants", (long) p.vocabulary.size());
        List<String> stale = new ArrayList<>();
        for (var e : p.allowUsed.entrySet()) {
            // An allow naming a file of another module is judged there, not stale here.
            if (!e.getValue() && p.applicable.contains(e.getKey()))
                stale.add(e.getKey().in());
        }
        if (!stale.isEmpty() && p.files > 0) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    sites,
                    "allow entries matched nothing: " + String.join(", ", stale));
        }
        if (p.files == 0) return new Evaluation(Outcome.BLIND, population, List.of(), "no sources scanned");
        return Evaluation.of(population, sites);
    }

    /** {@code value → constant name}: {@code static final} constants plus enum-constructor literals in {@code <clinit>}. */
    static Map<String, String> constants(ClassFacts owner) {
        Map<String, String> out = new TreeMap<>();
        for (FieldFacts f : owner.fields()) {
            String v = f.constantValue();
            if (v != null && f.desc().equals("Ljava/lang/String;")) out.put(v, f.name());
        }
        for (MethodFacts m : owner.methods()) {
            if (!m.name().equals("<clinit>")) continue;
            for (CallSite s : m.calls()) {
                if (s.owner().equals(owner.name()) && s.name().equals("<init>")) {
                    for (String lit : s.literals()) out.putIfAbsent(lit, "<enum constant>");
                }
            }
        }
        return out;
    }

    /**
     * The owner's facts: this module's, its classpath's, the JDK's — or, since the ban list is the
     * owner's and the scan is this module's text, any module's main index in the workspace. A module
     * that does not depend on the owner can still type its literals.
     */
    private static @Nullable ClassFacts ownerFacts(String owner, EvalContext ctx) {
        String internal = Descriptors.internalName(owner);
        ClassFacts own = ctx.facts().classes().get(internal);
        if (own != null) return own;
        byte[] bytes = ctx.hierarchy().bytesOf(internal);
        if (bytes != null) return FactsExtractor.extract(bytes);
        try {
            return WorkspaceFacts.lookup(ctx.root(), WorkspaceModules.of(ctx.root()), internal)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** The owner's own source file inside module {@code moduleDir}, when it lives there: it is exempt. */
    private static @Nullable String ownerSourceRel(ClassFacts owner, Path moduleDir) {
        String tail = SourcePaths.tail(owner);
        if (tail == null) return null;
        String root = SourcePaths.rootHolding(moduleDir, tail);
        return root == null ? null : root + "/" + tail;
    }

    private static boolean inSourceSet(String rel, boolean all) {
        if (rel.startsWith("src/main/")) return true;
        if (rel.startsWith("src/")
                && !rel.startsWith("src/test/")
                && !rel.contains("/test/")
                && rel.indexOf('/', 4) < 0) return true; // simple layout: src/<File>
        if (rel.startsWith("src/") && rel.split("/").length > 1 && !rel.startsWith("src/test/") && !isSuiteRoot(rel))
            return true;
        return all && (rel.startsWith("src/test/") || rel.startsWith("test/"));
    }

    private static boolean isSuiteRoot(String rel) {
        String second = rel.split("/")[1];
        return second.equals("test")
                || second.equals("integration")
                || second.equals("e2e")
                || second.equals("guard")
                || second.equals("fixtures");
    }

    private static @Nullable Pattern shapePattern(String shape) {
        if (shape.equals("hyphenated")) return HYPHENATED;
        if (shape.startsWith("regex:")) return Pattern.compile(shape.substring("regex:".length()));
        return null;
    }

    /** By module-relative path ({@code src/main/java/…}) or workspace-relative ({@code shared/host/src/…}). */
    private static @Nullable Allow allowing(List<Allow> allow, String rel, String module) {
        String full = module.isEmpty() ? rel : module + "/" + rel;
        for (Allow a : allow) {
            for (String p : List.of(rel, full)) {
                if (Rule.globMatches(a.in(), p) || p.equals(a.in()) || p.startsWith(a.in() + "/")) return a;
            }
            if (a.in().equals(module)) return a;
        }
        return null;
    }

    private static String simpleName(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? binaryName : binaryName.substring(dot + 1);
    }
}
