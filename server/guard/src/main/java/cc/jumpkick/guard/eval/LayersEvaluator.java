// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * {@code layers}: named layers over packages or modules, and which may depend on which. Edges come
 * from the manifests ({@code edges = "manifest"}: sibling dependencies in the production scopes,
 * the model lane) or from the facts ({@code "classes"}: a type reference from a class in one layer
 * to a class in another, the workspace lane), or both. {@code exports} names the packages of a
 * module other modules may reference; {@code exact} makes a declared module dependency that no
 * class edge uses a violation. A layer with no member, or an edge set of none, is {@code blind}.
 */
final class LayersEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        TomlTable t = rule.table();
        TomlTable layersTable = t.getTable("layers");
        TomlTable accessTable = t.getTable("access");
        if (layersTable == null || accessTable == null) return Evaluation.failed("`layers` and `access` are required");
        Map<String, List<String>> layers = new LinkedHashMap<>();
        for (String name : layersTable.keySet()) layers.put(name, strings(layersTable.get(name)));
        boolean closed = Boolean.TRUE.equals(t.getBoolean("closed"));
        Map<String, Set<String>> access = new LinkedHashMap<>();
        for (String name : accessTable.keySet()) {
            if (!layers.containsKey(name)) return Evaluation.failed("access names an unknown layer `" + name + "`");
            Set<String> may = new LinkedHashSet<>(strings(accessTable.get(name)));
            for (String m : may)
                if (!layers.containsKey(m))
                    return Evaluation.failed("access." + name + " names an unknown layer `" + m + "`");
            access.put(name, may);
        }
        String edges = t.isString("edges") ? String.valueOf(t.getString("edges")) : "manifest";
        boolean exact = Boolean.TRUE.equals(t.getBoolean("exact"));
        TomlTable exportsTable = t.getTable("exports");
        if (exact && edges.equals("manifest"))
            return Evaluation.failed("exact needs class edges: edges = \"classes\" or \"both\"");

        Scan scan = new Scan(rule, ctx, layers, closed, access, edges, exact, exportsTable);
        scan.manifestEdges();
        if (!edges.equals("manifest")) scan.classEdges();
        return scan.finish();
    }

    /** One evaluation's walk: the rule's layers and access, and the members, edges and sites it finds. */
    private static final class Scan {
        private final Rule rule;
        private final EvalContext ctx;
        private final Map<String, List<String>> layers;
        private final boolean closed;
        private final Map<String, Set<String>> access;
        private final String edges;
        private final boolean exact;
        private final @Nullable TomlTable exportsTable;
        private final List<Path> moduleDirs;
        private final WorkspaceModel model;
        private final Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        private final Map<String, Long> members = new TreeMap<>();
        private final List<Observation> sites = new ArrayList<>();
        private long edgeCount = 0;
        private final Map<String, Set<String>> manifestEdges = new TreeMap<>();

        Scan(
                Rule rule,
                EvalContext ctx,
                Map<String, List<String>> layers,
                boolean closed,
                Map<String, Set<String>> access,
                String edges,
                boolean exact,
                @Nullable TomlTable exportsTable)
                throws IOException {
            this.rule = rule;
            this.ctx = ctx;
            this.layers = layers;
            this.closed = closed;
            this.access = access;
            this.edges = edges;
            this.exact = exact;
            this.exportsTable = exportsTable;
            this.moduleDirs = ctx.modules();
            this.model = WorkspaceModel.of(ctx.root(), moduleDirs);
            for (Allow a : rule.allow()) allowUsed.put(a, false);
            for (String l : layers.keySet()) members.put(l, 0L);
        }

        /** Manifest edges: module → sibling module. */
        void manifestEdges() {
            for (String module : model.modules()) {
                // Membership is the workspace's; `scope` only picks whose edges are judged.
                Set<String> from = moduleLayers(layers, module);
                for (String l : from) members.merge(l, 1L, Long::sum);
                if (!rule.applies(module)) continue;
                Set<String> to = model.edgesFrom(module, ModuleOrder.PRODUCTION_SCOPES);
                manifestEdges.put(module, to);
                if (edges.equals("classes")) continue;
                for (String target : to) {
                    edgeCount++;
                    Set<String> targetLayers = moduleLayers(layers, target);
                    for (String a : from) {
                        if (closed && targetLayers.isEmpty()) {
                            if (allowed(rule, allowUsed, module, target)) continue;
                            sites.add(Observation.site(
                                    "module:" + module + " -> " + target,
                                    module + "/" + ManifestPaths.MANIFEST,
                                    0,
                                    module + " (" + a + ") depends on " + target + ", which is in no layer; " + a
                                            + " may depend on " + describe(access.get(a)) + " (closed)"));
                            continue;
                        }
                        for (String b : targetLayers) {
                            if (a.equals(b) || access.getOrDefault(a, Set.of()).contains(b)) continue;
                            if (allowed(rule, allowUsed, module, target)) continue;
                            sites.add(Observation.site(
                                    "module:" + module + " -> " + target,
                                    module + "/" + ManifestPaths.MANIFEST,
                                    0,
                                    module + " (" + a + ") depends on " + target + " (" + b + "); " + a
                                            + " may depend on " + describe(access.get(a))));
                        }
                    }
                }
            }
        }

        /** Class edges: a type reference across layers, exports, exact. */
        void classEdges() throws IOException {
            FactsIndex facts = ctx.facts();
            Map<String, String> classModule = WorkspaceModel.classModules(ctx.root(), moduleDirs);
            Set<String> used = new TreeSet<>();
            for (ClassFacts c : facts.classList()) {
                if (c.isPackageInfo()) continue;
                String module = classModule.getOrDefault(c.name(), "");
                if (!rule.applies(module)) continue;
                Set<String> from = classLayers(layers, c, module);
                for (String l : from) members.merge(l, 1L, Long::sum);
                for (String ref : c.typeRefs()) {
                    ClassFacts target = facts.classes().get(ref);
                    if (target == null || Descriptors.outermost(ref).equals(Descriptors.outermost(c.name()))) continue;
                    String targetModule = classModule.getOrDefault(ref, "");
                    edgeCount++;
                    if (!module.equals(targetModule)) used.add(module + " -> " + targetModule);
                    classEdge(c, module, from, target, targetModule);
                }
            }
            if (exact) {
                for (var e : manifestEdges.entrySet()) {
                    for (String target : e.getValue()) {
                        if (used.contains(e.getKey() + " -> " + target) || allowed(rule, allowUsed, e.getKey()))
                            continue;
                        sites.add(Observation.site(
                                "module:" + e.getKey() + " -> " + target + " | unused",
                                e.getKey() + "/" + ManifestPaths.MANIFEST,
                                0,
                                e.getKey() + " declares a dependency on " + target + " that no class of it uses"));
                    }
                }
            }
        }

        private void classEdge(ClassFacts c, String module, Set<String> from, ClassFacts target, String targetModule) {
            Set<String> to = classLayers(layers, target, targetModule);
            for (String a : from) {
                for (String b : to) {
                    if (a.equals(b) || access.getOrDefault(a, Set.of()).contains(b)) continue;
                    if (allowedClass(rule, allowUsed, c, module)) continue;
                    sites.add(Observation.site(
                            c.binaryName() + " -> " + target.binaryName(),
                            ForbidEvaluator.source(ctx, c),
                            0,
                            c.binaryName() + " (" + a + ") references " + target.binaryName() + " (" + b + "); " + a
                                    + " may depend on " + describe(access.get(a))));
                }
            }
            if (exportsTable != null && !module.equals(targetModule) && exportsTable.contains(targetModule)) {
                List<String> visible = strings(exportsTable.get(targetModule));
                boolean exported = false;
                for (String pkg : visible)
                    if (ClassPredicates.packageMatches(pkg, target.packageName())) exported = true;
                if (!exported && !allowedClass(rule, allowUsed, c, module)) {
                    sites.add(Observation.site(
                            c.binaryName() + " -> " + target.binaryName() + " | export",
                            ForbidEvaluator.source(ctx, c),
                            0,
                            c.binaryName() + " references " + target.binaryName() + ", which " + targetModule
                                    + " does not export (exports " + visible + ")"));
                }
            }
        }

        Evaluation finish() {
            Map<String, Long> population = new TreeMap<>();
            population.put("edges", edgeCount);
            for (var e : members.entrySet()) population.put("layer:" + e.getKey(), e.getValue());
            List<String> empty = new ArrayList<>();
            for (var e : members.entrySet()) if (e.getValue() == 0) empty.add(e.getKey());
            if (!empty.isEmpty() || edgeCount == 0) {
                return new Evaluation(
                        Outcome.BLIND,
                        population,
                        List.of(),
                        empty.isEmpty()
                                ? "no edge between any two layers"
                                : "layer(s) with no member: " + String.join(", ", empty));
            }
            List<String> stale = new ArrayList<>();
            for (var e : allowUsed.entrySet())
                if (!e.getValue()) stale.add(e.getKey().in());
            if (!stale.isEmpty()) {
                return new Evaluation(
                        Outcome.STALE_ALLOW,
                        population,
                        sites,
                        "allow entries matched nothing: " + String.join(", ", stale));
            }
            return Evaluation.of(population, sites);
        }
    }

    /** A {@code ..pkg..} pattern, as against a module glob — {@code ../sib} names a member beside the root. */
    static boolean packagePattern(String value) {
        return value.contains("..") && !value.contains("/");
    }

    /** The layers a module belongs to: values that are module globs ({@code plugins/*}, {@code shared/host}). */
    static Set<String> moduleLayers(Map<String, List<String>> layers, String module) {
        Set<String> out = new LinkedHashSet<>();
        for (var e : layers.entrySet()) {
            for (String v : e.getValue()) {
                if (packagePattern(v)) continue;
                if (v.equals(module) || Rule.globMatches(v, module)) out.add(e.getKey());
            }
        }
        return out;
    }

    /** The layers a class belongs to: by package pattern, or by its module's glob. */
    static Set<String> classLayers(Map<String, List<String>> layers, ClassFacts c, String module) {
        Set<String> out = new LinkedHashSet<>();
        for (var e : layers.entrySet()) {
            for (String v : e.getValue()) {
                if (packagePattern(v)) {
                    if (ClassPredicates.packageMatches(v, c.packageName())) out.add(e.getKey());
                } else if (!module.isEmpty() && (v.equals(module) || Rule.globMatches(v, module))) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    /** An allow names a module (every edge from it) or an edge, {@code "a -> b"}, either side a glob. */
    private static boolean allowed(Rule rule, Map<Allow, Boolean> used, String module, String target) {
        String edge = module + " -> " + target;
        for (Allow a : rule.allow()) {
            String in = a.in().replaceAll("\\s*->\\s*", " -> ");
            boolean hit = in.contains(" -> ")
                    ? in.equals(edge) || Rule.globMatches(in, edge)
                    : in.equals(module) || Rule.globMatches(in, module);
            if (hit) {
                used.put(a, true);
                return true;
            }
        }
        return false;
    }

    private static boolean allowed(Rule rule, Map<Allow, Boolean> used, String module) {
        for (Allow a : rule.allow()) {
            if (!a.in().contains("->") && (a.in().equals(module) || Rule.globMatches(a.in(), module))) {
                used.put(a, true);
                return true;
            }
        }
        return false;
    }

    private static boolean allowedClass(Rule rule, Map<Allow, Boolean> used, ClassFacts c, String module) {
        Allow a = ForbidEvaluator.allowing(rule.allow(), c, module);
        if (a == null) return false;
        used.put(a, true);
        return true;
    }

    private static String describe(@Nullable Set<String> may) {
        return may == null || may.isEmpty() ? "nothing" : String.join(", ", may);
    }

    private static List<String> strings(@Nullable Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof TomlArray a) {
            for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        } else if (v != null) {
            out.add(String.valueOf(v));
        }
        return out;
    }
}
