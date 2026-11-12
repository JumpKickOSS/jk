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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.Opcodes;
import org.tomlj.TomlTable;

/**
 * {@code coupling}: how far the top-level classes of one module's packages reach into each other,
 * over the facts' calls and field accesses. A nested, inner, anonymous or lambda class is folded into
 * its outermost class, so peeling a helper into a nested class changes nothing and peeling it into a
 * sibling top-level class shows up as the edge it is.
 *
 * <p>The weight of {@code A → B} is the number of distinct members of {@code B} (or of a class nested
 * in it) that code of {@code A} names: a method or constructor by name and descriptor, a field by
 * name, read or written. Only classes in scope count on either end. A {@code static final} constant
 * javac inlines leaves no field access behind and so no edge.
 *
 * <ul>
 *   <li>{@code mutual}: one metric observation per pair that reaches both ways, unit {@code
 *       <module>:<A>~<B>} (the names in order), value the two weights summed. With {@code baseline =
 *       true} the pairs of today are a band that may only shrink, and a new pair is red.
 *   <li>{@code efferent}: per class, the number of classes it reaches; past {@code cap} it is an
 *       observation, unit {@code <module>:<A>}.
 *   <li>{@code static-fan-in}: per static-only holder (no instance field, every method static, every
 *       constructor private), the number of classes reaching it; past {@code cap} it is an
 *       observation. A holder many classes lean on is a namespace for functions that belong with
 *       their data.
 * </ul>
 *
 * An {@code allow} entry naming a class (or a package, {@code pkg.*} / {@code pkg.**}) takes it out of
 * the graph.
 */
final class CouplingEvaluator implements Evaluator {

    static final int DEFAULT_CAP = 15;

    private static final Set<String> MEASURES = Set.of("mutual", "efferent", "static-fan-in");

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        TomlTable t = rule.table();
        String measure = String.valueOf(t.getString("measure"));
        if (!MEASURES.contains(measure)) return Evaluation.failed("unknown coupling measure " + measure);
        if (measure.equals("mutual") && t.contains("cap")) {
            return Evaluation.failed("measure mutual takes no cap: every pair that reaches both ways is reported");
        }
        Long capKey = t.getLong("cap");
        int cap = capKey == null ? DEFAULT_CAP : capKey.intValue();
        List<String> packages = ForbidEvaluator.strings(t, "packages");
        FactsIndex facts = ctx.facts();
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);

        // The nodes: top-level classes in scope, by internal name.
        Map<String, ClassFacts> nodes = new TreeMap<>();
        for (ClassFacts c : facts.classList()) {
            if (c.isNested() || c.isPackageInfo() || c.name().endsWith("module-info")) continue;
            if (!packages.isEmpty() && !inScope(packages, c.packageName())) continue;
            Allow allow = ForbidEvaluator.allowing(rule.allow(), c, ctx.module());
            if (allow != null) {
                allowUsed.put(allow, true);
                continue;
            }
            nodes.put(c.name(), c);
        }
        Graph g = graph(facts, nodes.keySet());
        String where = ctx.module().isEmpty() ? "workspace" : ctx.module();
        List<Observation> out =
                switch (measure) {
                    case "mutual" -> mutual(g, nodes, ctx, where);
                    case "efferent" -> efferent(g, nodes, ctx, where, cap);
                    case "static-fan-in" -> staticFanIn(g, nodes, ctx, where, cap);
                    default -> List.of();
                };
        Map<String, Long> population = Map.of("classes", (long) nodes.size(), "edges", g.edgeCount());
        if (nodes.isEmpty()) {
            return new Evaluation(Outcome.BLIND, population, List.of(), "no classes in scope to measure");
        }
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet()) {
            if (!e.getValue() && ForbidEvaluator.appliesHere(e.getKey(), facts, ctx)) {
                stale.add(e.getKey().in());
            }
        }
        if (!stale.isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW, population, out, "allow entries matched nothing: " + String.join(", ", stale));
        }
        return Evaluation.of(population, out);
    }

    private static boolean inScope(List<String> packages, String pkg) {
        for (String p : packages) if (ClassPredicates.packageMatches(p, pkg)) return true;
        return false;
    }

    /** {@code A → B → the distinct members of B that A names}, both ends top-level classes in scope. */
    record Graph(Map<String, Map<String, Set<String>>> out) {
        int weight(String from, String to) {
            Set<String> members = out.getOrDefault(from, Map.of()).get(to);
            return members == null ? 0 : members.size();
        }

        Set<String> targets(String from) {
            return out.getOrDefault(from, Map.of()).keySet();
        }

        long edgeCount() {
            long n = 0;
            for (Map<String, Set<String>> m : out.values()) n += m.size();
            return n;
        }
    }

    static Graph graph(FactsIndex facts, Set<String> nodes) {
        Map<String, Map<String, Set<String>>> out = new TreeMap<>();
        for (ClassFacts c : facts.classList()) {
            String from = Descriptors.outermost(c.name());
            if (!nodes.contains(from)) continue;
            for (MethodFacts m : c.methods()) {
                for (CallSite s : m.calls()) add(out, nodes, from, s.owner(), s.target());
                for (FieldRef f : m.fieldRefs()) add(out, nodes, from, f.owner(), f.target());
            }
        }
        return new Graph(out);
    }

    private static void add(
            Map<String, Map<String, Set<String>>> out, Set<String> nodes, String from, String owner, String member) {
        String to = Descriptors.outermost(owner);
        if (to.equals(from) || !nodes.contains(to)) return;
        out.computeIfAbsent(from, k -> new TreeMap<>())
                .computeIfAbsent(to, k -> new TreeSet<>())
                .add(member);
    }

    private static List<Observation> mutual(Graph g, Map<String, ClassFacts> nodes, EvalContext ctx, String where) {
        List<Observation> obs = new ArrayList<>();
        for (String a : nodes.keySet()) {
            for (String b : g.targets(a)) {
                if (a.compareTo(b) >= 0) continue;
                int ab = g.weight(a, b);
                int ba = g.weight(b, a);
                if (Math.min(ab, ba) < 1) continue;
                String an = Descriptors.binaryName(a);
                String bn = Descriptors.binaryName(b);
                obs.add(Observation.metric(
                        where + ":" + an + "~" + bn,
                        ab + ba,
                        ForbidEvaluator.source(ctx, nodes.get(a)),
                        an + " and " + bn + " reach into each other: " + ab + " members one way, " + ba
                                + " the other"));
            }
        }
        return obs;
    }

    private static List<Observation> efferent(
            Graph g, Map<String, ClassFacts> nodes, EvalContext ctx, String where, int cap) {
        List<Observation> obs = new ArrayList<>();
        for (var e : nodes.entrySet()) {
            int n = g.targets(e.getKey()).size();
            if (n <= cap) continue;
            String name = e.getValue().binaryName();
            obs.add(Observation.metric(
                    where + ":" + name,
                    n,
                    ForbidEvaluator.source(ctx, e.getValue()),
                    name + " reaches " + n + " classes (cap " + cap + ")"));
        }
        return obs;
    }

    private static List<Observation> staticFanIn(
            Graph g, Map<String, ClassFacts> nodes, EvalContext ctx, String where, int cap) {
        Map<String, Integer> fanIn = new TreeMap<>();
        for (String a : nodes.keySet()) for (String b : g.targets(a)) fanIn.merge(b, 1, Integer::sum);
        List<Observation> obs = new ArrayList<>();
        for (var e : nodes.entrySet()) {
            if (!staticHolder(e.getValue())) continue;
            int n = fanIn.getOrDefault(e.getKey(), 0);
            if (n <= cap) continue;
            String name = e.getValue().binaryName();
            obs.add(Observation.metric(
                    where + ":" + name,
                    n,
                    ForbidEvaluator.source(ctx, e.getValue()),
                    n + " classes reach the static-only holder " + name + " (cap " + cap + ")"));
        }
        return obs;
    }

    /** No instance field, every method static, at least one constructor and every one private. */
    static boolean staticHolder(ClassFacts c) {
        int kinds = Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION | Opcodes.ACC_RECORD;
        if ((c.access() & kinds) != 0) return false;
        for (FieldFacts f : c.fields()) if ((f.access() & Opcodes.ACC_STATIC) == 0) return false;
        boolean constructor = false;
        boolean member = false;
        for (MethodFacts m : c.methods()) {
            if (m.name().equals("<clinit>")) continue;
            if (m.name().equals("<init>")) {
                if ((m.access() & Opcodes.ACC_PRIVATE) == 0) return false;
                constructor = true;
                continue;
            }
            if ((m.access() & Opcodes.ACC_STATIC) == 0) return false;
            member = true;
        }
        return constructor && (member || !c.fields().isEmpty());
    }
}
