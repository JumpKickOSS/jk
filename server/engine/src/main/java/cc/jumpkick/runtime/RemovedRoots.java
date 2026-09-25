// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/**
 * Direct dependencies a compile lost between two locks: coordinates that had one of {@code scopes}
 * and no longer do, and that nothing else in that set depends on. A starter removed from the
 * manifest is such a root; the jars it pulled in are not, because the starter still depends on them.
 */
@NullMarked
final class RemovedRoots {

    private RemovedRoots() {}

    /** {@code group:artifact} from a lock name or a {@code deps} entry. */
    static String coordinate(String lockName) {
        int first = lockName.indexOf(':');
        if (first < 0) return lockName;
        int second = lockName.indexOf(':', first + 1);
        return second < 0 ? lockName : lockName.substring(0, second);
    }

    /**
     * The removed roots, in {@code previous} order. Empty when {@code previous} is missing a row
     * the current lock still carries — an addition is not a root.
     */
    static List<String> of(List<Lockfile.Artifact> previous, List<Lockfile.Artifact> current, Set<Scope> scopes) {
        Map<String, Lockfile.Artifact> now = new LinkedHashMap<>();
        for (Lockfile.Artifact a : current) now.putIfAbsent(coordinate(a.name()), a);
        Map<String, List<String>> deps = new LinkedHashMap<>();
        List<String> lost = new ArrayList<>();
        for (Lockfile.Artifact a : previous) {
            String ga = coordinate(a.name());
            deps.putIfAbsent(ga, dependencies(a));
            if (!a.inAnyScope(scopes)) continue;
            Lockfile.Artifact kept = now.get(ga);
            if (kept != null && kept.inAnyScope(scopes)) continue;
            if (!lost.contains(ga)) lost.add(ga);
        }
        Set<String> lostSet = new LinkedHashSet<>(lost);
        Set<String> incoming = new LinkedHashSet<>();
        for (String ga : lost) {
            for (String dep : deps.getOrDefault(ga, List.of())) {
                if (lostSet.contains(dep)) incoming.add(dep);
            }
        }
        List<String> roots = new ArrayList<>();
        for (String ga : lost) if (!incoming.contains(ga)) roots.add(ga);
        return roots;
    }

    /** Whether {@code root} depends on {@code target}, directly or through {@code deps}. */
    static boolean reaches(String root, String target, Map<String, List<String>> deps) {
        if (root.equals(target)) return true;
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(root);
        seen.add(root);
        for (int i = 0; i < queue.size(); i++) {
            for (String dep : deps.getOrDefault(queue.get(i), List.of())) {
                if (!seen.add(dep)) continue;
                if (dep.equals(target)) return true;
                queue.add(dep);
            }
        }
        return false;
    }

    /** Dependency edges of {@code previous}, {@code group:artifact} to the coordinates it depends on. */
    static Map<String, List<String>> edges(List<Lockfile.Artifact> previous) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (Lockfile.Artifact a : previous) deps.putIfAbsent(coordinate(a.name()), dependencies(a));
        return deps;
    }

    private static List<String> dependencies(Lockfile.Artifact artifact) {
        List<String> out = new ArrayList<>();
        for (String dep : artifact.deps()) {
            String ga = coordinate(dep);
            if (!ga.isEmpty() && !out.contains(ga)) out.add(ga);
        }
        return out;
    }
}
