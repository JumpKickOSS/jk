// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.Pom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The edges between members of an imported reactor: a dependency on a sibling becomes a
 * {@code workspace = true} edge, a reactor BOM leaves {@code [platform]}, a reactor POM the
 * workspace does not build is dropped or, for a {@code <type>pom</type>} edge to an aggregator,
 * rewritten onto the dependent as the aggregator's own classpath dependencies.
 */
final class SiblingEdges {

    private SiblingEdges() {}

    /**
     * Map {@code group:artifact} → sibling {@link Project#name()} for every unit in the
     * workspace (root + members) so inter-module deps become {@code workspace = true}.
     */
    static Map<String, String> siblingGaIndex(JkBuild root, Collection<JkBuild> modules) {
        Map<String, String> ga = new LinkedHashMap<>();
        ga.put(
                root.project().group() + ":" + root.project().name(),
                root.project().name());
        for (JkBuild m : modules) {
            ga.put(m.project().group() + ":" + m.project().name(), m.project().name());
        }
        return ga;
    }

    /**
     * Convert deps whose GA matches a workspace sibling into workspace edges, each under the handle
     * the Maven dependency was written under; an edge to a name in {@code sharedNames} carries the
     * dependency's group so it picks one member. Maven {@code <type>test-jar</type>} becomes
     * {@code kind = "tests"} (Mill testModuleDeps) under its {@code -tests} handle. A BOM of
     * the reactor leaves {@code [platform]}: its managed versions are already on the declared
     * dependencies, and the lock fetches a BOM from a repository, which a reactor BOM is not in. A
     * dependency on a reactor POM the workspace does not build ({@code unbuilt}: an aggregator, a
     * module of an inactive profile) is dropped with a row, since no repository has it either; a
     * {@code <type>pom</type>} edge to an aggregator is rewritten in place: the aggregator's own
     * compile and runtime dependencies, which Maven put on the dependent's classpath through the
     * pom, are written on the dependent, a sibling among them as a workspace edge.
     */
    static JkBuild rewrite(
            JkBuild module,
            Map<String, String> siblingByGa,
            Set<String> sharedNames,
            Map<String, String> bomByGa,
            Map<String, ReactorModules.Unbuilt> unbuilt,
            Set<String> importedBoms,
            String moduleKey,
            ModuleRows rows) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        Map<Scope, List<Dependency>> carried = new EnumMap<>(Scope.class);
        Set<String> declared = declaredModules(module);
        boolean changed = false;
        for (Scope scope : Scope.values()) {
            List<Dependency> in = module.dependencies().of(scope);
            if (in.isEmpty()) continue;
            List<Dependency> out = new ArrayList<>(in.size());
            for (Dependency d : in) {
                // A managed version for a reactor module is the workspace's to supply; the row has
                // nothing to pin, whether the module is a member, an aggregator or a reactor BOM.
                if (scope == Scope.MANAGED && namesReactorPom(d.module(), siblingByGa, unbuilt, bomByGa)) {
                    changed = true;
                    continue;
                }
                String bomPath = scope == Scope.PLATFORM ? bomByGa.get(d.module()) : null;
                if (bomPath != null) {
                    changed = true;
                    importedBoms.add(bomPath);
                    rows.add(
                            moduleKey,
                            ImportReport.Severity.WARNING,
                            "`<dependencyManagement>` imports the reactor BOM `" + bomPath + "` (" + d.module()
                                    + "); its managed versions are applied to the declared dependencies and no"
                                    + " `[platform]` row is written, because the lock fetches a BOM from a repository"
                                    + " and a reactor BOM is not published, so transitive versions follow the"
                                    + " resolver.");
                    continue;
                }
                String siblingName = siblingByGa.get(d.module());
                if (siblingName == null) {
                    ReactorModules.Unbuilt reactorPom = unbuilt.get(d.module());
                    if (reactorPom != null) {
                        changed = true;
                        boolean platform = scope == Scope.PLATFORM;
                        List<String> written = reactorPom.carriesOnto(platform)
                                ? carry(reactorPom.classpath(), siblingByGa, sharedNames, declared, carried)
                                : List.of();
                        rows.add(
                                moduleKey,
                                reactorPom.carriesOnto(platform)
                                        ? ImportReport.Severity.WARNING
                                        : ImportReport.Severity.ERROR,
                                reactorPom.row(d.module(), platform, written));
                        continue;
                    }
                    // External test-jar keeps kind=tests (lock/resolve map to g:a:test-jar:tests).
                    out.add(d);
                    if (d.isTestsKind()) changed = true;
                    continue;
                }
                changed = true;
                if (scope == Scope.PLATFORM) {
                    rows.add(
                            moduleKey,
                            ImportReport.Severity.WARNING,
                            "`<dependencyManagement>` imports the sibling BOM " + d.module()
                                    + "; its managed versions are applied to the declared dependencies and no"
                                    + " `[platform]` entry is written, because a workspace module is not a published"
                                    + " BOM, so transitive versions follow the resolver.");
                    continue;
                }
                // The edge keeps the handle the Maven dependency was written under — the
                // artifactId, `-tests` for a test-jar, a collision rename — so a feature list
                // naming it still does, and the jar and the test-jar of one sibling stay two rows.
                // mapDependencies already forced tests-kind deps into a test scope, so kind is
                // carried as-is — never emitted where the parser would reject it.
                Dependency ws = sharedNames.contains(siblingName)
                        ? Dependency.workspace(siblingName, d.group())
                        : Dependency.workspace(siblingName);
                ws = ws.withLibrary(d.library()).withOptional(d.optional());
                if (d.isTestsKind()) {
                    ws = ws.withKind(DependencyKind.TESTS);
                }
                out.add(ws);
            }
            byScope.put(scope, out);
        }
        if (!changed) return module;
        for (var e : carried.entrySet()) {
            byScope.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
        if (!carried.isEmpty()) {
            ImportReport.Builder handles = ImportReport.builder();
            PomImporter.uniquifyHandles(byScope, handles);
            rows.addAll(moduleKey, handles.build());
        }
        return module.withDependencies(new JkBuild.Dependencies(byScope));
    }

    /** True when {@code module} is a POM of the reactor: a member, an aggregator or a reactor BOM. */
    static boolean namesReactorPom(
            String module,
            Map<String, String> siblingByGa,
            Map<String, ReactorModules.Unbuilt> unbuilt,
            Map<String, String> bomByGa) {
        return siblingByGa.containsKey(module) || unbuilt.containsKey(module) || bomByGa.containsKey(module);
    }

    /** Every {@code group:artifact} the module declares in any scope, so a carried dependency is not written twice. */
    private static Set<String> declaredModules(JkBuild module) {
        Set<String> modules = new HashSet<>();
        for (Scope scope : Scope.values()) {
            for (Dependency d : module.dependencies().of(scope)) {
                if (d.module() != null) modules.add(d.module());
            }
        }
        return modules;
    }

    /**
     * Write an aggregator's classpath dependencies onto the dependent, in the scope each holds
     * under Maven (compile or runtime), a sibling as a workspace edge. Returns what was written, as
     * {@code group:artifact} with {@code (workspace)} on a sibling; a coordinate the dependent
     * declares itself is left to that declaration.
     */
    private static List<String> carry(
            List<Pom.Dep> classpath,
            Map<String, String> siblingByGa,
            Set<String> sharedNames,
            Set<String> declared,
            Map<Scope, List<Dependency>> carried) {
        List<String> written = new ArrayList<>();
        for (Pom.Dep dep : classpath) {
            if (!declared.add(dep.module())) continue;
            Dependency d = DependencyMapping.toDependency(dep);
            Scope scope = DependencyMapping.scope(dep.scope());
            if (d.isTestsKind()) scope = Scope.TEST;
            String siblingName = siblingByGa.get(dep.module());
            if (siblingName != null) {
                Dependency ws = sharedNames.contains(siblingName)
                        ? Dependency.workspace(siblingName, dep.groupId())
                        : Dependency.workspace(siblingName);
                ws = ws.withLibrary(d.library());
                d = d.isTestsKind() ? ws.withKind(DependencyKind.TESTS) : ws;
                written.add(dep.module() + " (workspace)");
            } else {
                written.add(dep.module());
            }
            carried.computeIfAbsent(scope, k -> new ArrayList<>()).add(d);
        }
        return written;
    }
}
