// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The exclusions in force while packages expand, who declared them, and the edges they pruned.
 *
 * <p>Maven drops a dependency only when <em>every</em> path reaching it excludes that dependency,
 * so the set applied when a package expands is the <strong>intersection</strong> of the sets the
 * paths to it registered, not their union: two parents, one excluding and one not, leave the
 * child expanding with nothing stripped. Intersection only shrinks, so registrations may arrive in
 * any order and still converge on the same set. Each pattern remembers the origins that registered
 * it — a manifest handle ({@code jk.toml:<handle>}) or a POM ({@code g:a@version}) — so a lock row
 * can say who pruned an edge.
 *
 * <p>A pattern is {@code group:artifact}, {@code group:*}, {@code *:artifact} or {@code *:*}; the
 * type and classifier of a package never escape an exclusion.
 */
final class ExclusionLedger {

    /** Marks an exclusion a manifest declared: {@code jk.toml:<handle>}. */
    static final String MANIFEST_ORIGIN = "jk.toml:";

    /** Package → the patterns in force when it expands. */
    private final ConcurrentHashMap<String, Set<String>> whenExpanding = new ConcurrentHashMap<>();

    /** Package → pattern → every origin that registered the pattern for it. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> origins = new ConcurrentHashMap<>();

    /** {@code pkg@version} → the children its last expansion filtered. */
    private final ConcurrentHashMap<String, Set<String>> filteredAtExpansion = new ConcurrentHashMap<>();

    /** Forget every registration and pruned edge; the caches a solve does not own are elsewhere. */
    void reset() {
        whenExpanding.clear();
        origins.clear();
        filteredAtExpansion.clear();
    }

    /** The patterns applied when {@code pkg} expands. */
    Set<String> exclusionsFor(String pkg) {
        return whenExpanding.getOrDefault(pkg, Set.of());
    }

    /** The converged view for {@code pkg}: each pattern in force with the origins that registered it. */
    Map<String, Set<String>> viewFor(String pkg) {
        Set<String> patterns = exclusionsFor(pkg);
        if (patterns.isEmpty()) return Map.of();
        Map<String, Set<String>> byPattern = origins.getOrDefault(pkg, new ConcurrentHashMap<>());
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String pattern : patterns) out.put(pattern, Set.copyOf(byPattern.getOrDefault(pattern, Set.of())));
        return out;
    }

    /**
     * Intersect one path's view into the set applied when {@code pkg} expands, and remember the
     * origins. The first registration establishes the set; later ones can only narrow it, and an
     * empty view — an unencumbered path — collapses it to nothing.
     */
    void register(String pkg, Map<String, Set<String>> view) {
        Set<String> incoming = Set.copyOf(view.keySet());
        whenExpanding.merge(pkg, incoming, (existing, fresh) -> {
            if (existing.isEmpty() || fresh.isEmpty()) return Set.of();
            Set<String> both = new LinkedHashSet<>(existing);
            both.retainAll(fresh);
            return Set.copyOf(both);
        });
        if (view.isEmpty()) return;
        ConcurrentHashMap<String, Set<String>> byPattern = origins.computeIfAbsent(pkg, k -> new ConcurrentHashMap<>());
        view.forEach((pattern, from) -> byPattern
                .computeIfAbsent(pattern, k -> ConcurrentHashMap.newKeySet())
                .addAll(from));
    }

    /** One path's view of {@code patterns}, every one registered by {@code origin}; {@code null} records no origin. */
    static Map<String, Set<String>> view(Set<String> patterns, @Nullable String origin) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String pattern : patterns) out.put(pattern, origin == null ? Set.of() : Set.of(origin));
        return out;
    }

    /**
     * Remember what the expansion of {@code pkg@version} dropped. Overwrite, not merge: a
     * re-expansion in a later solve round supersedes the earlier one.
     */
    void recordFiltered(String pkg, String version, @Nullable Set<String> filtered) {
        String key = pkg + "@" + version;
        if (filtered == null || filtered.isEmpty()) {
            filteredAtExpansion.remove(key);
        } else {
            filteredAtExpansion.put(key, Set.copyOf(filtered));
        }
    }

    /**
     * Whether any decided package's expansion filtered an edge the converged set would keep. A
     * package decided before a clean path registered can bake a too-aggressive filter into the
     * solve — the dropped child never enters the resolution and no conflict surfaces it — so the
     * solve must run again with the converged sets.
     */
    boolean anyExpansionStale(Map<String, String> decisions) {
        for (Map.Entry<String, String> e : decisions.entrySet()) {
            Set<String> filtered = filteredAtExpansion.get(e.getKey() + "@" + e.getValue());
            if (filtered == null) continue;
            Set<String> converged = exclusionsFor(e.getKey());
            for (String dep : filtered) {
                if (!isExcluded(dep, converged)) return true;
            }
        }
        return false;
    }

    /**
     * The edges the expansion of {@code pkg@version} pruned and the converged set still prunes,
     * one {@code group:artifact <- origin[, origin]} line each — what a lock row's
     * {@code excluded-by} carries. A pruned child whose origin no registration recorded is the
     * coordinate alone.
     */
    List<String> prunedEdges(String pkg, String version) {
        Set<String> filtered = filteredAtExpansion.get(pkg + "@" + version);
        if (filtered == null || filtered.isEmpty()) return List.of();
        Map<String, Set<String>> view = viewFor(pkg);
        List<String> out = new ArrayList<>(filtered.size());
        for (String child : new TreeSet<>(filtered)) {
            if (!isExcluded(child, view.keySet())) continue;
            String ga = gaOf(child);
            Set<String> from = new TreeSet<>();
            for (Map.Entry<String, Set<String>> e : view.entrySet()) {
                if (matches(ga, e.getKey())) from.addAll(e.getValue());
            }
            out.add(from.isEmpty() ? ga : ga + Lockfile.DECLARED_SEPARATOR + String.join(", ", from));
        }
        return out;
    }

    /** Whether {@code packageKey} is covered by any pattern in {@code exclusions}. */
    static boolean isExcluded(String packageKey, @Nullable Set<String> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return false;
        String ga = gaOf(packageKey);
        if (exclusions.contains(ga) || exclusions.contains(packageKey)) return true;
        int colon = ga.indexOf(':');
        if (colon < 0) return exclusions.contains("*:*");
        String g = ga.substring(0, colon);
        String a = ga.substring(colon + 1);
        return exclusions.contains(g + ":*") || exclusions.contains("*:" + a) || exclusions.contains("*:*");
    }

    private static boolean matches(String ga, String pattern) {
        if (pattern.equals(ga) || pattern.equals("*:*")) return true;
        int colon = ga.indexOf(':');
        if (colon < 0) return false;
        return pattern.equals(ga.substring(0, colon) + ":*") || pattern.equals("*:" + ga.substring(colon + 1));
    }

    /** {@code group:artifact} of a package key; a non-Maven key is its own coordinate. */
    static String gaOf(String packageKey) {
        return PackageId.isMavenPackageKey(packageKey)
                ? PackageId.parse(packageKey).ga()
                : packageKey;
    }
}
