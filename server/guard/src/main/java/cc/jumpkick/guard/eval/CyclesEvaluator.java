// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.Rule;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code cycles}: slices free of cycles. Slices are packages grouped by {@code matching} — ArchUnit's
 * notation, {@code com.acme.features.(*)..} one slice per captured segment, {@code (**)} one per
 * package — over the facts' package edges (type references, so static imports and qualified names
 * count, which the import regex it replaces dropped); or {@code over = "modules"}, the workspace's
 * sibling-dependency graph. A non-trivial strongly connected component is one metric observation per
 * component: unit {@code <module>:<smallest slice>}, value its size, so {@code baseline = true} is a
 * band that may only shrink. The report names the component's members, never its cycles: a 15-node
 * component has thousands of elementary cycles and none says which edge to cut.
 */
final class CyclesEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        TomlTable t = rule.table();
        if (t.isString("over")) return overModules(rule, ctx);
        String matching = t.isString("matching") ? String.valueOf(t.getString("matching")) : "(**)";
        Pattern slice = slicePattern(matching);
        boolean across = Boolean.TRUE.equals(t.getBoolean("across-modules"));
        FactsIndex facts = ctx.facts();
        // slice → outgoing slices
        Map<String, Set<String>> edges = new TreeMap<>();
        Map<String, String> sliceOf = new HashMap<>();
        for (ClassFacts c : facts.classList()) sliceOf.computeIfAbsent(c.packageName(), p -> sliceName(slice, p));
        long edgeCount = 0;
        for (ClassFacts c : facts.classList()) {
            String from = sliceOf.get(c.packageName());
            if (from == null) continue;
            Set<String> out = edges.computeIfAbsent(from, k -> new TreeSet<>());
            for (String ref : c.typeRefs()) {
                String pkg = Descriptors.packageOf(ref);
                if (!facts.classes().containsKey(ref) && !sliceOf.containsKey(pkg)) continue;
                String to = sliceOf.get(pkg);
                if (to == null || to.equals(from)) continue;
                if (out.add(to)) edgeCount++;
            }
        }
        for (String s : new TreeSet<>(sliceOf.values())) edges.putIfAbsent(s, new TreeSet<>());
        String where = across || ctx.module().isEmpty() ? "workspace" : ctx.module();
        return report(edges, edgeCount, where, "slices");
    }

    private static Evaluation overModules(Rule rule, EvalContext ctx) throws IOException {
        WorkspaceModel model = WorkspaceModel.of(ctx.root(), ctx.modules());
        Map<String, Set<String>> edges = new TreeMap<>();
        long edgeCount = 0;
        for (String m : model.modules()) {
            if (!rule.applies(m)) continue;
            Set<String> to = new TreeSet<>(model.edgesFrom(m, ModuleOrder.PRODUCTION_SCOPES));
            edges.put(m, to);
            edgeCount += to.size();
        }
        return report(edges, edgeCount, "workspace", "modules");
    }

    private static Evaluation report(Map<String, Set<String>> edges, long edgeCount, String where, String unit) {
        Map<String, Long> population = Map.of(unit, (long) edges.size(), "edges", edgeCount);
        // A module of one package, or of packages that never reference each other, has no cycle
        // to find: that is a fact about the module, not a broken scan. Blind is seeing no slices.
        if (edges.isEmpty()) return new Evaluation(Outcome.BLIND, population, List.of(), "no " + unit + " to examine");
        List<Observation> out = new ArrayList<>();
        for (List<String> component : Tarjan.components(edges)) {
            if (component.size() < 2) continue;
            List<String> members = new ArrayList<>(new TreeSet<>(component));
            out.add(Observation.metric(
                    where + ":" + members.get(0),
                    component.size(),
                    null,
                    component.size() + " " + unit + " in one cycle in " + where + ": " + String.join(", ", members)));
        }
        return Evaluation.of(population, out);
    }

    /** ArchUnit slice notation to a regex whose first group is the slice name. */
    static Pattern slicePattern(String matching) {
        StringBuilder re = new StringBuilder("^");
        String m = matching;
        if (m.startsWith("..")) {
            re.append("(?:.*?\\.)??"); // reluctant: `..(*)..` names the first segment, not the last
            m = m.substring(2);
        }
        boolean tail = m.endsWith("..");
        if (tail) m = m.substring(0, m.length() - 2);
        for (String part : m.split("\\.", -1)) {
            if (re.length() > 1
                    && re.charAt(re.length() - 1) != '?'
                    && !re.toString().endsWith("^")) re.append("\\.");
            if (part.equals("(*)")) re.append("([^.]+)");
            else if (part.equals("(**)")) re.append("(.+)");
            else if (part.equals("*")) re.append("[^.]+");
            else re.append(Pattern.quote(part));
        }
        re.append(tail ? "(?:\\..*)?$" : "$");
        return Pattern.compile(re.toString());
    }

    static @Nullable String sliceName(Pattern slice, String pkg) {
        Matcher m = slice.matcher(pkg);
        if (!m.matches()) return null;
        return m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : pkg;
    }

    /** Tarjan's strongly connected components over a directed graph of named nodes. */
    static final class Tarjan {
        private final Map<String, Set<String>> edges;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> low = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new TreeSet<>();
        private final List<List<String>> out = new ArrayList<>();
        private int next;

        private Tarjan(Map<String, Set<String>> edges) {
            this.edges = edges;
        }

        static List<List<String>> components(Map<String, Set<String>> edges) {
            Tarjan t = new Tarjan(edges);
            for (String v : edges.keySet()) if (!t.index.containsKey(v)) t.visit(v);
            return t.out;
        }

        private int lowOf(String v) {
            return low.getOrDefault(v, 0);
        }

        private void visit(String v) {
            index.put(v, next);
            low.put(v, next);
            next++;
            stack.push(v);
            onStack.add(v);
            for (String w : edges.getOrDefault(v, Set.of())) {
                if (!edges.containsKey(w)) continue;
                if (!index.containsKey(w)) {
                    visit(w);
                    low.put(v, Math.min(lowOf(v), lowOf(w)));
                } else if (onStack.contains(w)) {
                    low.put(v, Math.min(lowOf(v), index.getOrDefault(w, 0)));
                }
            }
            if (lowOf(v) == index.getOrDefault(v, -1)) {
                List<String> component = new ArrayList<>();
                String w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    component.add(w);
                } while (!w.equals(v));
                out.add(component);
            }
        }
    }
}
