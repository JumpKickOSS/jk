// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.Pom;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The dependency tables of one imported POM, by scope: its declared dependencies, the processor
 * paths of its compiler plugin, and the BOM imports and inline pins of its effective {@code
 * dependencyManagement}, each spelled as {@link DependencyMapping} spells a coordinate.
 */
public final class PomDependencies {

    private PomDependencies() {}

    /**
     * {@code processorPaths} count as users of a managed version too, so their pins are not reported
     * unused; {@code hoisted} collects the reactor parent's inline pins for the workspace root, and is
     * {@code null} for a POM imported on its own; {@code pluginPlatform} names the BOMs and parents a
     * plugin table contributes, which get no {@code [platform-dependencies]} row. {@code
     * platformSupplied} collects the modules declared without a version that a platform entry (one
     * written, or the plugin table's) supplies, and {@code bomSupplied} those of them a BOM import
     * supplied rather than an ancestor's own entry; {@code reactorSupplied} those a reactor parent's
     * own entry supplied.
     */
    static Map<Scope, List<Dependency>> map(
            EffectiveModel em,
            ImportReport.Builder report,
            List<Pom.Dep> processorPaths,
            @Nullable List<Dependency> hoisted,
            Predicate<String> pluginPlatform,
            Set<String> platformSupplied,
            Set<String> bomSupplied,
            Set<String> reactorSupplied) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        Set<String> used = new HashSet<>();
        for (Pom.Dep path : processorPaths) used.add(path.module() + ":jar");
        List<EffectiveModel.Declared> declarations = em.dependencies(report);
        for (EffectiveModel.Declared declared : declarations) used.add(declared.key());
        EffectiveModel.Management mgmt = em.management(used);
        Map<String, List<String>> managedBy = new LinkedHashMap<>();
        Map<String, List<String>> inheritedFrom = new LinkedHashMap<>();
        List<Pom.Dep> plainProcessors = new ArrayList<>();
        for (EffectiveModel.Declared declared : declarations) {
            if (processorPaths.isEmpty() && KnownProcessors.recognizes(declared.dep())) {
                plainProcessors.add(declared.dep());
            }
            if (!declared.own()) {
                inheritedFrom
                        .computeIfAbsent(declared.source(), k -> new ArrayList<>())
                        .add(declared.dep().module());
            } else if (declared.versionManaged() && !declared.source().startsWith("this POM")) {
                managedBy
                        .computeIfAbsent(declared.source(), k -> new ArrayList<>())
                        .add(declared.dep().module());
            }
            if (declared.versionManaged() && em.platformSupplies(declared.key(), mgmt)) {
                platformSupplied.add(declared.dep().module());
                if (!em.chainManages(declared.key()))
                    bomSupplied.add(declared.dep().module());
            } else if (declared.own()
                    && declared.versionManaged()
                    && em.managedBy(declared.key())
                            .map(EffectiveModel.Ancestor::inReactor)
                            .orElse(false)) {
                reactorSupplied.add(declared.dep().module());
            }
            mapDependency(declared.dep(), byScope, report);
        }
        mapManagement(mgmt, byScope, report, hoisted, pluginPlatform);
        managedBy.forEach((source, modules) ->
                report.warning("versions for " + String.join(", ", modules) + " managed by " + source));
        inheritedFrom.forEach((source, modules) ->
                report.warning("dependencies " + String.join(", ", modules) + " inherited from " + source));
        KnownProcessors.write(plainProcessors, byScope, report);
        uniquifyHandles(byScope, report);
        return byScope;
    }

    private static void mapDependency(Pom.Dep dep, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        DependencyMapping.map(dep, report)
                .ifPresent(mapped -> byScope.computeIfAbsent(mapped.scope(), s -> new ArrayList<>())
                        .add(mapped.dependency()));
    }

    /**
     * {@code <annotationProcessorPaths>} → {@code [processor-dependencies]} for compile-main's path,
     * and {@code [test-processor-dependencies]} for what compile-test's differs by: the extra paths
     * when it is compile-main's plus more, else its whole path under {@code inherit = false}, so the
     * tests run exactly the processors Maven's {@code testCompile} runs. With the element present
     * Maven hands javac a processor path and stops discovering processors on the classpath, which is
     * exactly what the jk tables do, so only the listed paths are written. Without it, the
     * processors {@link KnownProcessors} recognizes among the plain dependencies are written.
     */
    static void processorPaths(
            PluginFacts.ProcessorPaths paths, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        mapProcessorPaths(paths.shared(), Scope.PROCESSOR, byScope, report);
        mapProcessorPaths(paths.test(), Scope.TEST_PROCESSOR, byScope, report);
        String named = paths.test().stream().map(Pom.Dep::module).collect(Collectors.joining(", "));
        if (paths.testOwn()) {
            report.warning("<annotationProcessorPaths>: testCompile runs "
                    + (named.isEmpty() ? "no declared processor" : named)
                    + ", not compile's path; written to [test-processor-dependencies] with inherit = false");
        } else if (!named.isEmpty()) {
            report.warning("<annotationProcessorPaths> on testCompile: " + named
                    + " written to [test-processor-dependencies]");
        }
    }

    private static void mapProcessorPaths(
            List<Pom.Dep> paths, Scope scope, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        for (Pom.Dep path : paths) {
            if (PluginFacts.usable(path.version()) == null) {
                report.warning("<annotationProcessorPaths> " + path.module() + ": no version; written =unresolved");
            }
            byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(DependencyMapping.toDependency(path));
        }
    }

    /**
     * A BOM import whose version is still a property is a Tier-3 row naming that property and no
     * {@code [platform-dependencies]} row: a lock cannot ask a repository for a BOM at the version
     * {@code unresolved}, and the effective model left the property that way because the chain
     * that defines it could not be built.
     */
    private static void reportUnresolvedBom(Pom.Dep bom, ImportReport.Builder report) {
        String version = bom.version() == null ? "" : bom.version();
        List<String> properties = CiFriendlyVersions.unresolved(version);
        String named = properties.isEmpty()
                ? "no version"
                : "version " + version + " (" + String.join(", ", properties) + " undefined)";
        report.error("BOM import " + bom.module() + " has " + named
                + "; no [platform-dependencies] row. Pin the version in the POM and re-import");
    }

    /**
     * BOM imports become {@code [platform]} entries with their versions resolved; a published parent
     * whose chain manages versions is carried as one {@code [platform]} entry of its own, so the
     * inherited table governs transitive versions too. A BOM or parent a plugin table contributes
     * ({@code pluginPlatform}: the {@code [helidon]} table's {@code helidon-dependencies}) is left out. Bare pins nothing declared uses, and every
     * entry with {@code <exclusions>}, become {@code [managed-dependencies]} rows, which govern
     * transitive versions and prune every edge onto the module the way Maven's inline {@code
     * dependencyManagement} does; the ones a reactor parent owns are gathered into {@code hoisted}
     * for the workspace root when a workspace import passes one.
     */
    private static void mapManagement(
            EffectiveModel.Management mgmt,
            Map<Scope, List<Dependency>> byScope,
            ImportReport.Builder report,
            @Nullable List<Dependency> hoisted,
            Predicate<String> pluginPlatform) {
        for (Pom.Dep bom : mgmt.platform()) {
            if (pluginPlatform.test(bom.module())) continue;
            if (PluginFacts.usable(bom.version()) == null) {
                reportUnresolvedBom(bom, report);
                continue;
            }
            byScope.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>()).add(DependencyMapping.toDependency(bom));
        }
        EffectiveModel.Ancestor parent = mgmt.parentPlatform();
        if (parent != null && !pluginPlatform.test(parent.groupId() + ":" + parent.artifactId())) {
            String module = parent.groupId() + ":" + parent.artifactId();
            byScope.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>())
                    .add(Dependency.of(parent.artifactId(), module, VersionSelector.parse(parent.version())));
            report.warning("<dependencyManagement> from " + parent.label() + " written as [platform] " + parent.gav());
        }
        ManagedRows rows = new ManagedRows();
        Set<String> seen = new HashSet<>();
        for (EffectiveModel.InlinePin pin : mgmt.inline()) {
            Pom.Dep dep = pin.dep();
            if (dep.version() == null || dep.version().isBlank()) {
                rows.versionless(pin.owner(), dep.module());
                continue;
            }
            if (PluginFacts.usable(dep.version()) == null) {
                rows.unresolved(pin.owner(), dep.module());
                continue;
            }
            if (DependencyMapping.unmappedType(dep) != null || !seen.add(dep.module())) continue;
            Dependency row = ExclusionMapping.apply(
                    Dependency.of(
                            dep.artifactId(),
                            dep.module(),
                            VersionSelector.parse(dep.version().trim())),
                    dep,
                    report);
            if (pin.reactorParent() && hoisted != null) {
                // Reported once, by the workspace import, when the root's table is written.
                if (hoisted.stream().noneMatch(h -> h.module().equals(row.module()))) hoisted.add(row);
                continue;
            }
            byScope.computeIfAbsent(Scope.MANAGED, s -> new ArrayList<>()).add(row);
            rows.written(pin.owner(), row);
        }
        rows.flush(report);
    }

    /**
     * The manifest key (dep {@code library} handle) must be unique per scope section — the renderer
     * keys each section on it, so a collision silently drops an edge. Maven and Gradle allow
     * same-artifactId deps in one scope (different groups); disambiguate deterministically in
     * declaration order.
     * The renamed edge keeps every other field — {@code optional}, kind, classifier, exclusions.
     */
    public static void uniquifyHandles(Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        for (Map.Entry<Scope, List<Dependency>> e : byScope.entrySet()) {
            List<Dependency> deps = e.getValue();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < deps.size(); i++) {
                Dependency d = deps.get(i);
                if (seen.add(d.library())) continue;
                int n = 2;
                String candidate;
                do {
                    candidate = d.library() + "-" + n++;
                } while (!seen.add(candidate));
                report.warning("handle " + d.library() + " collides in ["
                        + e.getKey().tomlSection() + "]; " + d.module() + " written as " + candidate);
                deps.set(i, d.withLibrary(candidate));
            }
        }
    }

    /**
     * The managed tables of the BOMs inactive profiles import, read through the import's
     * repositories once each; a BOM no repository serves is a row, and the profile's dependencies
     * it would have versioned stay {@code unresolved}.
     */
    static ProfileMapping.BomTables profileBoms(RepoModelResolver resolver, ImportReport.Builder report) {
        Map<String, Optional<List<Pom.Dep>>> read = new HashMap<>();
        EffectivePomBuilder builder = new EffectivePomBuilder(resolver.repos());
        return (groupId, artifactId, version) -> read.computeIfAbsent(
                        groupId + ":" + artifactId + ":" + version, gav -> {
                            try {
                                return Optional.of(builder.build(Coordinate.of(groupId, artifactId, version))
                                        .managedDependencies());
                            } catch (IOException | RuntimeException e) {
                                report.warning("profile BOM " + gav + " unreadable (" + e.getMessage()
                                        + "); its versions written =unresolved");
                                return Optional.empty();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return Optional.empty();
                            }
                        })
                .orElse(null);
    }
}
