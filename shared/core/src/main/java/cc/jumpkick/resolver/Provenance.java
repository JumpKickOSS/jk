// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Inverse dependency paths from declared roots to a target module ({@code jk why}). Each
 * {@link Path} starts at a root and ends at the target.
 */
public final class Provenance {

    private Provenance() {}

    /**
     * @return list of paths from declared roots to {@code targetModule}. Empty if the target isn't in
     *     the lockfile or is unreachable.
     */
    public static List<Path> pathsTo(JkBuild project, Lockfile lock, String targetModule) {
        Objects.requireNonNull(targetModule, "targetModule");

        Map<String, Lockfile.Artifact> byModule = DependencyTree.indexByModule(lock);
        String resolvedTarget = targetModule;
        if (!byModule.containsKey(resolvedTarget)) {
            // User / CLI often passes GA; lock rows are package keys (g:a:jar:).
            resolvedTarget = ga(targetModule);
            if (!byModule.containsKey(resolvedTarget)) {
                return List.of();
            }
        }

        // Reverse adjacency: dep → list of (parent, parent-version)
        Map<String, Set<String>> reverseDeps = new HashMap<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            for (String depRef : pkg.deps()) {
                String depModule = DependencyTree.stripVersion(depRef);
                reverseDeps.computeIfAbsent(depModule, k -> new TreeSet<>()).add(pkg.name());
                // also index by GA so walks that start from a GA query still find parents
                String depGa = ga(depModule);
                if (!depGa.equals(depModule)) {
                    reverseDeps.computeIfAbsent(depGa, k -> new TreeSet<>()).add(pkg.name());
                }
            }
        }

        Set<String> declaredRoots = new LinkedHashSet<>(DependencyTree.collectRoots(project));

        List<Path> paths = new ArrayList<>();
        walkUp(resolvedTarget, byModule, reverseDeps, declaredRoots, new ArrayList<>(), paths);
        return paths;
    }

    private static void walkUp(
            String current,
            Map<String, Lockfile.Artifact> byModule,
            Map<String, Set<String>> reverseDeps,
            Set<String> declaredRoots,
            List<Step> stack,
            List<Path> out) {

        Lockfile.Artifact pkg = byModule.get(current);
        String version = pkg != null ? pkg.version() : "?";
        stack.addLast(new Step(current, version));

        try {
            if (isDeclaredRoot(current, declaredRoots)) {
                // Reverse for display: declared root first, target last.
                List<Step> path = new ArrayList<>(stack);
                Collections.reverse(path);
                out.add(new Path(List.copyOf(path)));
                return;
            }
            Set<String> parents = reverseDeps.get(current);
            if (parents == null || parents.isEmpty()) {
                // also try GA form of package key
                parents = reverseDeps.get(ga(current));
            }
            if (parents == null || parents.isEmpty()) return;
            for (String parent : parents) {
                // Avoid cycles in the lockfile.
                boolean alreadyOnStack = stack.stream().anyMatch(s -> s.module().equals(parent));
                if (alreadyOnStack) continue;
                walkUp(parent, byModule, reverseDeps, declaredRoots, stack, out);
            }
        } finally {
            stack.removeLast();
        }
    }

    /** True when {@code module} is a declared root, matching either package key or GA form. */
    private static boolean isDeclaredRoot(String module, Set<String> declaredRoots) {
        if (declaredRoots.contains(module)) return true;
        String moduleGa = ga(module);
        if (declaredRoots.contains(moduleGa)) return true;
        for (String root : declaredRoots) {
            if (ga(root).equals(moduleGa)) return true;
        }
        return false;
    }

    /** {@code group:artifact} for Maven package keys; identity otherwise. */
    private static String ga(String nameOrKey) {
        if (nameOrKey == null || nameOrKey.isBlank()) return nameOrKey;
        if (cc.jumpkick.model.PackageId.isMavenPackageKey(nameOrKey)) {
            try {
                return cc.jumpkick.model.PackageId.parse(nameOrKey).ga();
            } catch (RuntimeException ignored) {
                return nameOrKey;
            }
        }
        return nameOrKey;
    }

    /** A path from a declared root (first) down to the target (last). */
    public record Path(List<Step> steps) {
        public Path {
            Objects.requireNonNull(steps, "steps");
            steps = List.copyOf(steps);
        }

        public String render() {
            return steps.stream().map(s -> s.module() + " v" + s.version()).collect(Collectors.joining(" -> "));
        }
    }

    public record Step(String module, String version) {}
}
