// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.ManifestOverlay;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.FeatureSelection;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.LockPipeline;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.base.LockMode;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The packages a relock after {@code jk update}'s pin moves would add to the lock, a trust change a
 * person accepts, traced to the moves whose dependencies reach them.
 */
public final class AddedPackages {

    private AddedPackages() {}

    /**
     * The lock the scope at {@code lockDir} resolves to with each manifest in {@code manifests}
     * (path to text) read as that text and every other as it stands on disk, written nowhere: the
     * relock {@code mode} would run, seeded by the lock on disk like it. No file is written.
     */
    public static Lockfile probe(
            Path lockDir,
            Map<Path, String> manifests,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaults,
            LockMode mode)
            throws Exception {
        return ManifestOverlay.reading(manifests, () -> {
            LockPlans.LockScope scope = LockPlans.lockScope(lockDir, new FeatureSelection(features, withDefaults));
            return new LockPipeline(scope.lockDir(), scope.effective(), cache, repoUrl, features, withDefaults, mode)
                    .resolve(
                            LockPipeline.readIfPresent(scope.lockDir()),
                            ResolveObserver.NOOP,
                            LockPipeline.Progress.SILENT);
        });
    }

    /**
     * Each of {@code moves} whose coordinate's rows in {@code after} reach, through the rows'
     * dependency edges, a package {@code after} holds and {@code before} does not, mapped to those
     * packages as {@code coordinate version}. When some added package no move reaches, the new
     * packages cannot be told apart by pin: every move maps to every added package. Empty when
     * nothing is added, and when there is no {@code before}: a first lock adds every package.
     */
    public static Map<ManifestUpdates.Rewrite, List<String>> byMove(
            @Nullable Lockfile before, Lockfile after, List<ManifestUpdates.Rewrite> moves) {
        if (before == null || moves.isEmpty()) return Map.of();
        Set<String> old = new HashSet<>();
        for (Lockfile.Artifact a : before.artifacts()) old.add(row(a));
        List<Lockfile.Artifact> added =
                after.artifacts().stream().filter(a -> !old.contains(row(a))).toList();
        if (added.isEmpty()) return Map.of();

        Map<String, List<Lockfile.Artifact>> byGa = new HashMap<>();
        for (Lockfile.Artifact a : after.artifacts()) {
            byGa.computeIfAbsent(ga(a.name()), k -> new ArrayList<>()).add(a);
        }
        Map<ManifestUpdates.Rewrite, List<String>> out = new LinkedHashMap<>();
        Set<Lockfile.Artifact> reachedByAny = new HashSet<>();
        for (ManifestUpdates.Rewrite move : moves) {
            Set<String> reached = closure(move.module(), byGa);
            Set<String> adds = new LinkedHashSet<>();
            for (Lockfile.Artifact a : added) {
                if (!reached.contains(ga(a.name()))) continue;
                adds.add(label(a));
                reachedByAny.add(a);
            }
            if (!adds.isEmpty()) out.put(move, List.copyOf(adds));
        }
        if (reachedByAny.size() == added.size()) return out;
        Set<String> every = new LinkedHashSet<>();
        for (Lockfile.Artifact a : added) every.add(label(a));
        Map<ManifestUpdates.Rewrite, List<String>> together = new LinkedHashMap<>();
        for (ManifestUpdates.Rewrite move : moves) together.put(move, List.copyOf(every));
        return together;
    }

    /** Every {@code group:artifact} the rows of {@code module} reach in {@code byGa}, itself included. */
    private static Set<String> closure(String module, Map<String, List<Lockfile.Artifact>> byGa) {
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(module));
        while (!todo.isEmpty()) {
            String ga = todo.pop();
            if (!seen.add(ga)) continue;
            for (Lockfile.Artifact a : byGa.getOrDefault(ga, List.of())) {
                for (String dep : a.deps()) todo.push(depGa(dep));
            }
        }
        return seen;
    }

    /** A lock row's identity across two locks: its name and the members it serves. */
    private static String row(Lockfile.Artifact a) {
        return a.name() + " " + String.join(",", a.members());
    }

    private static String label(Lockfile.Artifact a) {
        return a.displayIdentity() + " " + a.version();
    }

    /** The {@code group:artifact} of a lock row's name. */
    private static String ga(String name) {
        if (!PackageId.isMavenPackageKey(name)) return name;
        try {
            return PackageId.parse(name).ga();
        } catch (RuntimeException e) {
            return name;
        }
    }

    /** The {@code group:artifact} of a dependency edge ({@code g:a:jar:@1.0}). */
    private static String depGa(String dep) {
        int first = dep.indexOf(':');
        int second = first < 0 ? -1 : dep.indexOf(':', first + 1);
        return second < 0 ? dep : dep.substring(0, second);
    }
}
