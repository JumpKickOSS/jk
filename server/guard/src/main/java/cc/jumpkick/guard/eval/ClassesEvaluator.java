// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * {@code classes}: the classes {@code that} match must {@code should}. ArchUnit's core, as closed
 * predicate sets over the facts index — no DSL, no eager class-graph import. Both sides are ANDed;
 * any value is negatable with a leading {@code !}. An unknown predicate, or a shape no predicate
 * understands, is {@code scanner-failed} naming the closed set — every {@code should} value is
 * compiled once before the first class is looked at, so a typo is the rule's error and never a
 * violation per class (nor a pass when negated). The population is the classes {@code that}
 * selected: none is {@code blind}, the {@code allowEmptyShould(false)} lesson.
 *
 * <p>{@code should} predicates: {@code reside-in}, {@code be}, {@code be-annotated-with},
 * {@code implement}, {@code extend}, {@code have-only-private-constructors},
 * {@code have-only-final-fields}, {@code have-simple-name-ending-with}, {@code not-depend-on},
 * {@code only-be-accessed-by}, {@code have-modifier}. The last two read the index's other classes;
 * {@code only-be-accessed-by} needs every module's, so its rule routes to the workspace lane.
 */
final class ClassesEvaluator implements Evaluator {

    static final List<String> SHOULD = List.of(
            "reside-in",
            "be",
            "be-annotated-with",
            "implement",
            "extend",
            "have-only-private-constructors",
            "have-only-final-fields",
            "have-simple-name-ending-with",
            "not-depend-on",
            "only-be-accessed-by",
            "have-modifier");

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        TomlTable t = rule.table();
        TypeHierarchy types = ctx.hierarchy();
        ClassPredicates.Compiled that = ClassPredicates.compile(t.getTable("that"), types);
        if (that.error() != null) return Evaluation.failed("that: " + that.error());
        TomlTable should = t.getTable("should");
        if (should == null) return Evaluation.failed("`should` is required");
        List<Should> shoulds = new ArrayList<>();
        String error = compileShould(should, shoulds);
        if (error != null) return Evaluation.failed("should: " + error);
        FactsIndex facts = ctx.facts();
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        List<Observation> sites = new ArrayList<>();
        long selected = 0;
        for (ClassFacts c : facts.classList()) {
            if (c.isPackageInfo() || c.hasFlag(Opcodes.ACC_SYNTHETIC) || !that.test(c)) continue;
            selected++;
            List<String[]> failed = new ArrayList<>();
            for (Should s : shoulds) {
                String detail = holds(s, c, facts, types);
                boolean ok = detail == null;
                if (ok == s.negate()) {
                    String what = s.key() + " = \"" + s.raw() + "\"";
                    failed.add(new String[] {what, detail != null && !s.negate() ? what + " (" + detail + ")" : what});
                }
            }
            if (failed.isEmpty()) continue;
            Allow a = ForbidEvaluator.allowing(rule.allow(), c, ctx.module());
            if (a != null) {
                allowUsed.put(a, true);
                continue;
            }
            for (String[] f : failed) {
                sites.add(Observation.site(
                        c.binaryName() + " | " + f[0],
                        ForbidEvaluator.source(ctx, c),
                        0,
                        c.binaryName() + " should " + f[1]));
            }
        }
        Map<String, Long> population = Map.of("classes", selected);
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet()) {
            if (!e.getValue() && ForbidEvaluator.appliesHere(e.getKey(), facts, ctx)) {
                stale.add(e.getKey().in());
            }
        }
        if (!stale.isEmpty() && selected > 0) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    sites,
                    "allow entries matched nothing: " + String.join(", ", stale));
        }
        return Evaluation.of(population, sites);
    }

    /**
     * One {@code should} value as written: the predicate key, the raw value with its {@code !}, and
     * for the shape predicates ({@code be}, {@code have-modifier}) the shape resolved once.
     */
    private record Should(
            String key,
            String raw,
            boolean negate,
            String value,
            @Nullable Predicate<ClassFacts> shape) {}

    /** Every {@code should} value compiled, or the text of the first problem. */
    private static @Nullable String compileShould(TomlTable should, List<Should> into) {
        for (Map.Entry<String, Object> e : should.toMap().entrySet()) {
            String key = e.getKey();
            if (!SHOULD.contains(key)) {
                return "unknown predicate `" + key + "`; predicates are " + String.join(", ", SHOULD);
            }
            for (String raw : values(e.getValue())) {
                boolean negate = raw.startsWith("!");
                String v = negate ? raw.substring(1) : raw;
                Predicate<ClassFacts> shape = null;
                if (key.equals("be") || key.equals("have-modifier")) {
                    shape = ClassPredicates.shape(v);
                    if (shape == null) {
                        return key + " = \"" + raw + "\" is not understood; shapes are "
                                + String.join(", ", ClassPredicates.SHAPES);
                    }
                }
                into.add(new Should(key, raw, negate, v, shape));
            }
        }
        return null;
    }

    /** {@code null} when the predicate holds for {@code c}; otherwise what was found instead. */
    private static @Nullable String holds(Should should, ClassFacts c, FactsIndex facts, TypeHierarchy types) {
        String v = should.value();
        switch (should.key()) {
            case "reside-in" -> {
                return ClassPredicates.packageMatches(v, c.packageName()) ? null : "in " + c.packageName();
            }
            case "be", "have-modifier" -> {
                Predicate<ClassFacts> shape = should.shape();
                return shape == null || shape.test(c) ? null : "is not " + v;
            }
            case "be-annotated-with" -> {
                return c.hasAnnotation(v) ? null : "carries no @" + v.substring(v.lastIndexOf('.') + 1);
            }
            case "implement", "extend" -> {
                return types.isAssignableTo(c.name(), Descriptors.internalName(v)) ? null : "does not";
            }
            case "have-only-private-constructors" -> {
                for (MethodFacts m : c.methods()) {
                    if (m.name().equals("<init>") && (m.access() & Opcodes.ACC_PRIVATE) == 0) {
                        return "constructor " + m.desc() + " is not private";
                    }
                }
                return null;
            }
            case "have-only-final-fields" -> {
                for (FieldFacts f : c.fields()) {
                    if ((f.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) != 0) continue;
                    if ((f.access() & Opcodes.ACC_FINAL) == 0) return "field " + f.name() + " is not final";
                }
                return null;
            }
            case "have-simple-name-ending-with" -> {
                return ClassPredicates.simpleName(c).endsWith(v) ? null : "is named " + ClassPredicates.simpleName(c);
            }
            case "not-depend-on" -> {
                TreeSet<String> hits = new TreeSet<>();
                for (String ref : c.typeRefs()) if (targets(v, ref)) hits.add(Descriptors.binaryName(ref));
                for (MethodFacts m : c.methods()) {
                    for (CallSite s : m.calls()) if (targets(v, s.owner())) hits.add(Descriptors.binaryName(s.owner()));
                    for (FieldRef r : m.fieldRefs())
                        if (targets(v, r.owner())) hits.add(Descriptors.binaryName(r.owner()));
                }
                return hits.isEmpty() ? null : "depends on " + String.join(", ", hits);
            }
            case "only-be-accessed-by" -> {
                TreeSet<String> outsiders = new TreeSet<>();
                for (ClassFacts other : facts.classList()) {
                    if (Descriptors.outermost(other.name()).equals(Descriptors.outermost(c.name()))) continue;
                    if (!other.typeRefs().contains(c.name())) continue;
                    if (targets(v, other.name())) continue;
                    outsiders.add(other.binaryName());
                }
                return outsiders.isEmpty() ? null : "accessed by " + String.join(", ", outsiders);
            }
            default -> {
                return "unknown predicate " + should.key();
            }
        }
    }

    /** {@code v} names a package ({@code ..x..}, {@code a.b..}, {@code a.b.*}, {@code a.b.**}), a class glob, or a class. */
    private static boolean targets(String v, String internalName) {
        String binary = Descriptors.binaryName(internalName);
        String pkg = Descriptors.packageOf(internalName);
        if (v.endsWith(".**")) {
            String p = v.substring(0, v.length() - 3);
            return pkg.equals(p) || pkg.startsWith(p + ".");
        }
        if (v.contains("..") || v.endsWith(".*")) {
            String pattern = v.endsWith(".*") ? v.substring(0, v.length() - 2) : v;
            return ClassPredicates.packageMatches(pattern, pkg);
        }
        return binary.equals(v)
                || Rule.globMatches(v, binary)
                || Descriptors.binaryName(Descriptors.outermost(internalName)).equals(v);
    }

    /** Values as negatable names; a TOML boolean is the predicate itself ({@code true}) or its negation. */
    private static List<String> values(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof TomlArray a) {
            for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        } else if (v instanceof Boolean b) {
            out.add(b ? "true" : "!true");
        } else {
            out.add(String.valueOf(v));
        }
        return out;
    }
}
