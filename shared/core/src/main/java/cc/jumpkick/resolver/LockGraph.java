// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The one dependency-graph substrate every graph surface walks: {@code jk tree} (forward render),
 * {@code jk why} (reverse provenance), and the dashboard graph ({@code GET /api/project/graph}).
 * Built once per (project, lock) pair — index, canonicalization, declared roots, and both
 * adjacencies come from here, so the surfaces cannot disagree on what the graph <em>is</em> and a
 * request never rebuilds the same walk twice.
 *
 * <p>Lookups accept an artifact name, full package key ({@code g:a:type:classifier}), or bare GA —
 * the same aliasing the lockfile index always had. A coordinate the lock holds at one version for
 * main and another for test has two rows under one name; a graph built {@linkplain #forLock(Lockfile,
 * Collection) for a scope} answers with the row in that scope, and a graph built for none with the
 * lock's last row. The reverse adjacency is materialized lazily: tree/graph renders never pay for it.
 */
public final class LockGraph {

    private static final LockGraph EMPTY = new LockGraph(Map.of(), Map.of(), Map.of(), Set.of(), Map.of());

    private final Map<String, Lockfile.Artifact> byModule;
    /** Artifact name → version-stripped dep modules, lock order. */
    private final Map<String, List<String>> forward;
    /** Artifact name → same children, natural-sorted (tree render order). */
    private final Map<String, List<String>> forwardSorted;

    private final Set<String> declaredRoots;
    /** Declared root GA → the selector the manifest wrote for it. */
    private final Map<String, String> rootSelectors;
    /** Dep module (and its GA alias) → parent artifact names; built on first reverse walk. */
    private @Nullable Map<String, Set<String>> reverse;

    private LockGraph(
            Map<String, Lockfile.Artifact> byModule,
            Map<String, List<String>> forward,
            Map<String, List<String>> forwardSorted,
            Set<String> declaredRoots,
            Map<String, String> rootSelectors) {
        this.byModule = byModule;
        this.forward = forward;
        this.forwardSorted = forwardSorted;
        this.declaredRoots = declaredRoots;
        this.rootSelectors = rootSelectors;
    }

    /**
     * Full graph for a project: lock adjacency plus declared roots ({@code projectDir} lets a
     * workspace root union every member's declared deps, same as {@code jk why} always did).
     */
    public static LockGraph of(@Nullable JkBuild project, @Nullable Lockfile lock, @Nullable Path projectDir) {
        Set<String> roots =
                project == null ? Set.of() : new LinkedHashSet<>(DependencyTree.collectRoots(project, projectDir));
        return build(lock, roots, DependencyTree.collectRootSelectors(project, projectDir), Set.of());
    }

    /** Lock-only graph (no declared roots) — per-member locks in workspace renders. */
    public static LockGraph forLock(@Nullable Lockfile lock) {
        return build(lock, Set.of(), Map.of(), Set.of());
    }

    /**
     * Lock-only graph as one scope section reads it: where a name has several rows, the one in any
     * of {@code scopes} answers for it, so a test-only version never stands in for the main one.
     */
    public static LockGraph forLock(@Nullable Lockfile lock, Collection<Scope> scopes) {
        return build(lock, Set.of(), Map.of(), Set.copyOf(scopes));
    }

    private static LockGraph build(
            @Nullable Lockfile lock, Set<String> roots, Map<String, String> rootSelectors, Set<Scope> scopes) {
        if (lock == null && roots.isEmpty()) return EMPTY;
        Map<String, Lockfile.Artifact> byModule = lock == null ? Map.of() : indexByModule(lock, scopes);
        Map<String, List<String>> forward = new HashMap<>();
        Map<String, List<String>> forwardSorted = new HashMap<>();
        Set<Lockfile.Artifact> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
        chosen.addAll(byModule.values());
        for (Lockfile.Artifact pkg : chosen) {
            List<String> children = new ArrayList<>(pkg.deps().size());
            for (String depRef : pkg.deps()) children.add(stripVersion(depRef));
            forward.put(pkg.name(), List.copyOf(children));
            List<String> sorted = new ArrayList<>(children);
            sorted.sort(null);
            forwardSorted.put(pkg.name(), List.copyOf(sorted));
        }
        return new LockGraph(byModule, forward, forwardSorted, roots, rootSelectors);
    }

    /** The selector the manifest declares for root {@code module}, or null when it is not a declared root. */
    public @Nullable String rootSelector(String module) {
        return rootSelectors.get(ga(module));
    }

    /**
     * The selector {@code parent}'s lock row declared for its edge to {@code child} (name, package
     * key or GA), or null when the row has no such edge or the lock does not carry it.
     */
    public @Nullable String declaredSelector(String parent, String child) {
        Lockfile.Artifact pkg = artifact(parent);
        if (pkg == null) return null;
        String childGa = ga(child);
        for (String depRef : pkg.deps()) {
            String module = stripVersion(depRef);
            if (module.equals(child) || ga(module).equals(childGa)) return pkg.declaredFor(depRef);
        }
        return null;
    }

    /** The lock row for {@code moduleOrGa} (name / package key / GA alias), or null. */
    public Lockfile.@Nullable Artifact artifact(String moduleOrGa) {
        Lockfile.Artifact pkg = byModule.get(moduleOrGa);
        if (pkg == null) pkg = byModule.get(ga(moduleOrGa));
        return pkg;
    }

    /** Version-stripped children of {@code module} in lock order; empty when not in the lock. */
    public List<String> forward(String module) {
        Lockfile.Artifact pkg = artifact(module);
        if (pkg == null) return List.of();
        return forward.getOrDefault(pkg.name(), List.of());
    }

    /** As {@link #forward}, natural-sorted — the {@code jk tree} child order. */
    public List<String> forwardSorted(String module) {
        Lockfile.Artifact pkg = artifact(module);
        if (pkg == null) return List.of();
        return forwardSorted.getOrDefault(pkg.name(), List.of());
    }

    /** Parent artifact names of {@code module} (GA-alias aware); empty at a lock top. */
    public Set<String> parents(String module) {
        Map<String, Set<String>> rev = reverseAdjacency();
        Set<String> parents = rev.get(module);
        if (parents == null || parents.isEmpty()) parents = rev.get(ga(module));
        return parents == null ? Set.of() : parents;
    }

    /** Declared dependency modules ({@code jk.toml} roots across the workspace when built with one). */
    public Set<String> declaredRoots() {
        return declaredRoots;
    }

    /** True when {@code module} is a declared root, matching package key or GA form. */
    public boolean isDeclaredRoot(String module) {
        if (declaredRoots.contains(module)) return true;
        String moduleGa = ga(module);
        if (declaredRoots.contains(moduleGa)) return true;
        for (String root : declaredRoots) {
            if (ga(root).equals(moduleGa)) return true;
        }
        return false;
    }

    private Map<String, Set<String>> reverseAdjacency() {
        Map<String, Set<String>> rev = reverse;
        if (rev != null) return rev;
        rev = new HashMap<>();
        for (Map.Entry<String, List<String>> e : forward.entrySet()) {
            for (String depModule : e.getValue()) {
                rev.computeIfAbsent(depModule, k -> new TreeSet<>()).add(e.getKey());
                String depGa = ga(depModule);
                if (!depGa.equals(depModule)) {
                    rev.computeIfAbsent(depGa, k -> new TreeSet<>()).add(e.getKey());
                }
            }
        }
        reverse = rev;
        return rev;
    }

    // ---- the one home for the shared primitives --------------------------------------------

    /**
     * Lock index by artifact name, full package key, and bare GA. GA aliases resolve to the
     * default-jar row when one exists.
     */
    static Map<String, Lockfile.Artifact> indexByModule(Lockfile lock) {
        return indexByModule(lock, Set.of());
    }

    /**
     * As {@link #indexByModule(Lockfile)}; with {@code scopes} given, a row in one of them is never
     * displaced under its name by a row in none of them, so a scope-split coordinate indexes to the
     * row the section reads.
     */
    private static Map<String, Lockfile.Artifact> indexByModule(Lockfile lock, Set<Scope> scopes) {
        Map<String, Lockfile.Artifact> result = new HashMap<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            boolean inScope = pkg.inAnyScope(scopes);
            Lockfile.Artifact named = result.get(pkg.name());
            if (named != null && !inScope && named.inAnyScope(scopes)) continue;
            result.put(pkg.name(), pkg);
            result.put(pkg.packageKey(), pkg);
            if (PackageId.isMavenPackageKey(pkg.name())) {
                var id = PackageId.parse(pkg.name());
                if (id.isDefaultJar()) {
                    result.put(id.ga(), pkg);
                } else {
                    result.putIfAbsent(id.ga(), pkg);
                }
            }
        }
        return result;
    }

    /** Strip the {@code @version} suffix from a lockfile dep ref. */
    public static String stripVersion(String depRef) {
        int at = depRef.indexOf('@');
        return at > 0 ? depRef.substring(0, at) : depRef;
    }

    /** {@code group:artifact} for Maven package keys; identity otherwise. */
    public static String ga(@Nullable String nameOrKey) {
        if (nameOrKey == null || nameOrKey.isBlank()) return nameOrKey == null ? "" : nameOrKey;
        if (PackageId.isMavenPackageKey(nameOrKey)) {
            try {
                return PackageId.parse(nameOrKey).ga();
            } catch (RuntimeException ignored) {
                return nameOrKey;
            }
        }
        return nameOrKey;
    }
}
