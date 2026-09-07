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
import java.nio.file.Files;
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
 * hands to its constructor — an enum's names); the scan half is text over this module's own
 * sources. {@code inverse = true} adds the arm the owner-derived ban cannot have: literals of the
 * vocabulary's <em>shape</em> that no owner declares ("eight step names had no owner at all").
 */
final class VocabularyEvaluator implements Evaluator {

    private static final Pattern HYPHENATED = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)+");

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        TomlTable t = rule.table();
        String owner = String.valueOf(t.getString("owner"));
        String shape = t.isString("shape") ? String.valueOf(t.getString("shape")) : "exact";
        boolean inverse = Boolean.TRUE.equals(t.getBoolean("inverse"));
        long minLength = t.isLong("min-length") ? Long.parseLong(String.valueOf(t.getLong("min-length"))) : 1;
        Set<String> homonyms = new TreeSet<>(ForbidEvaluator.strings(t, "homonyms"));
        Pattern shapePattern = shapePattern(shape);

        ClassFacts ownerFacts = ownerFacts(owner, ctx);
        if (ownerFacts == null)
            return Evaluation.ownerMissing(
                    "owner " + owner + " resolves on neither this module, its classpath nor the JDK");
        Map<String, String> vocabulary = new TreeMap<>();
        for (var e : constants(ownerFacts).entrySet()) {
            String value = e.getKey();
            if (value.length() < minLength || homonyms.contains(value)) continue;
            if (shapePattern != null && !shapePattern.matcher(value).matches()) continue;
            vocabulary.put(value, e.getValue());
        }
        if (vocabulary.isEmpty())
            return Evaluation.ownerMissing("owner " + owner + " yields no constant of shape `" + shape
                    + "`; the rule has lost the owner it reads");

        Path moduleDir = ctx.moduleDir();
        if (moduleDir == null) return Evaluation.notEvaluated("vocabulary runs per module");
        String ownerSource = ownerSourceRel(ownerFacts, ctx);
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        Set<Allow> applicable = new HashSet<>();

        boolean all = rule.sourceSet().equals("all");
        long files = 0;
        long literals = 0;
        List<Observation> sites = new ArrayList<>();
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        Map<String, Observation> unowned = new TreeMap<>();
        for (TextFiles.Entry f : TextFiles.corpus(moduleDir)) {
            if (!f.language().code || !f.language().lexable) continue;
            if (!inSourceSet(f.rel(), all)) continue;
            if (f.rel().equals(ownerSource)) continue;
            String text = TextFiles.read(f.file());
            if (text == null) continue;
            files++;
            Allow allow = allowing(rule.allow(), f.rel(), ctx.module());
            if (allow != null) applicable.add(allow);
            for (CodeText.Literal l : CodeText.literals(text)) {
                literals++;
                String value = l.body();
                String constant = vocabulary.get(value);
                if (constant != null) {
                    if (allow != null) {
                        allowUsed.put(allow, true);
                        continue;
                    }
                    String base = f.rel() + " | " + Hashing.sha256Hex(value).substring(0, 16);
                    int ordinal = ordinals.merge(base, 1, Integer::sum);
                    sites.add(Observation.site(
                            base + " | " + ordinal,
                            f.rel(),
                            CodeText.lineAt(text, l.start()),
                            "\"" + value + "\" typed as a literal; the owner spells it " + simpleName(owner) + "."
                                    + constant));
                } else if (inverse
                        && shapePattern != null
                        && shapePattern.matcher(value).matches()
                        && !homonyms.contains(value)
                        && value.length() >= minLength) {
                    if (allow != null) {
                        allowUsed.put(allow, true);
                        continue;
                    }
                    unowned.putIfAbsent(
                            value,
                            Observation.site(
                                    "unowned | " + value,
                                    f.rel(),
                                    CodeText.lineAt(text, l.start()),
                                    "\"" + value
                                            + "\" has the shape of the vocabulary but no owner declares it — declare it in "
                                            + simpleName(owner) + " or name it a homonym"));
                }
            }
        }
        sites.addAll(unowned.values());
        Map<String, Long> population =
                Map.of("files", files, "literals", literals, "constants", (long) vocabulary.size());
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet()) {
            // An allow naming a file of another module is judged there, not stale here.
            if (!e.getValue() && applicable.contains(e.getKey()))
                stale.add(e.getKey().in());
        }
        if (!stale.isEmpty() && files > 0) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    sites,
                    "allow entries matched nothing: " + String.join(", ", stale));
        }
        if (files == 0)
            return new Evaluation(Outcome.BLIND, population, List.of(), "no sources scanned in " + ctx.module());
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
            return WorkspaceFacts.lookup(WorkspaceModules.of(ctx.root()), internal)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static @Nullable String ownerSourceRel(ClassFacts owner, EvalContext ctx) {
        if (owner.sourceFile() == null || !ctx.facts().classes().containsKey(owner.name())) return null;
        String pkgPath = owner.packageName().replace('.', '/');
        String tail = (pkgPath.isEmpty() ? "" : pkgPath + "/") + owner.sourceFile();
        Path moduleDir = ctx.moduleDir();
        if (moduleDir == null) return null;
        for (String root : List.of("src/main/java/", "src/main/kotlin/", "src/")) {
            if (Files.isRegularFile(moduleDir.resolve(root + tail))) return root + tail;
        }
        return null;
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
