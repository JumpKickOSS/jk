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
 * The exclusions each path registered for the packages it reaches, and the edges every expansion
 * kept or pruned.
 *
 * <p>Maven drops a dependency only when <em>every</em> path reaching it excludes that dependency,
 * so the set applied when a package expands is the <strong>intersection</strong> of the views the
 * paths to it registered, not their union. A registration is kept per origin — the {@code
 * parent@version} coordinate whose edge reached the package, or the manifest handle ({@code
 * jk.toml:<handle>}) that declares it — and only the live origins count: a manifest handle always,
 * a parent coordinate while the solve the ledger is read against decided that parent at that
 * version. Before a round has decided, every origin is live. Each pattern remembers the origins
 * that declared it, so a lock row can say who pruned an edge.
 *
 * <p>A pattern is {@code group:artifact}, {@code group:*}, {@code *:artifact} or {@code *:*}; the
 * type and classifier of a package never escape an exclusion.
 */
final class ExclusionLedger {

    /** Marks an exclusion a manifest declared: {@code jk.toml:<handle>}. */
    static final String MANIFEST_ORIGIN = "jk.toml:";

    /** What one expansion did with its edges: the children it kept and the ones it pruned. */
    private record Expansion(Set<String> kept, Set<String> filtered) {}

    /** Package → origin → the view (pattern → declaring origins) that path registered. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, Set<String>>>> registrations =
            new ConcurrentHashMap<>();

    /** {@code pkg@version} → what its last expansion kept and pruned. */
    private final ConcurrentHashMap<String, Expansion> expansions = new ConcurrentHashMap<>();

    /**
     * The decisions of the last solve round, which say whether a parent origin is live; {@code null}
     * before a round has decided, when every origin is.
     */
    private volatile @Nullable Map<String, String> decided;

    /** Forget every registration, expansion and decided round; the caches a solve does not own are elsewhere. */
    void reset() {
        registrations.clear();
        expansions.clear();
        decided = null;
    }

    /** Read every set against {@code decisions} from now on: a parent origin they decide elsewhere, or not at all, is dead. */
    void decided(Map<String, String> decisions) {
        decided = Map.copyOf(decisions);
    }

    /** The patterns applied when {@code pkg} expands. */
    Set<String> exclusionsFor(String pkg) {
        return viewFor(pkg).keySet();
    }

    /**
     * The converged view for {@code pkg}: the patterns every live origin registered, each with the
     * origins that declared it. Empty when no live origin reaches the package.
     */
    Map<String, Set<String>> viewFor(String pkg) {
        Map<String, Map<String, Set<String>>> byOrigin = registrations.get(pkg);
        if (byOrigin == null) return Map.of();
        Map<String, String> round = decided;
        Set<String> patterns = null;
        List<Map<String, Set<String>>> live = new ArrayList<>();
        for (Map.Entry<String, Map<String, Set<String>>> e : byOrigin.entrySet()) {
            if (!isLive(e.getKey(), round)) continue;
            live.add(e.getValue());
            if (patterns == null) {
                patterns = new LinkedHashSet<>(e.getValue().keySet());
            } else {
                patterns.retainAll(e.getValue().keySet());
            }
            if (patterns.isEmpty()) return Map.of();
        }
        if (patterns == null) return Map.of();
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String pattern : patterns) {
            Set<String> from = new LinkedHashSet<>();
            for (Map<String, Set<String>> view : live) from.addAll(view.getOrDefault(pattern, Set.of()));
            out.put(pattern, Set.copyOf(from));
        }
        return out;
    }

    /**
     * Whether {@code origin} still reaches its package: a manifest handle always does, and a {@code
     * parent@version} coordinate does while {@code round} decides the parent at that version.
     */
    private static boolean isLive(String origin, @Nullable Map<String, String> round) {
        if (round == null || origin.startsWith(MANIFEST_ORIGIN)) return true;
        int at = origin.lastIndexOf('@');
        return origin.substring(at + 1).equals(round.get(origin.substring(0, at)));
    }

    /**
     * Record the view one path applies to {@code pkg}, under the {@code parent@version} coordinate or
     * manifest handle the path comes from. A later registration from the same origin replaces the
     * earlier one. An empty view is a registration too: an unencumbered live path collapses the
     * converged set to nothing.
     */
    void register(String pkg, String origin, Map<String, Set<String>> view) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        view.forEach((pattern, from) -> copy.put(pattern, Set.copyOf(from)));
        registrations.computeIfAbsent(pkg, k -> new ConcurrentHashMap<>()).put(origin, Map.copyOf(copy));
    }

    /** One path's view of {@code patterns}, every one declared by {@code origin}; {@code null} records no origin. */
    static Map<String, Set<String>> view(Set<String> patterns, @Nullable String origin) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String pattern : patterns) out.put(pattern, origin == null ? Set.of() : Set.of(origin));
        return out;
    }

    /**
     * Remember what the expansion of {@code pkg@version} kept and dropped. Overwrite, not merge: a
     * re-expansion in a later solve round supersedes the earlier one.
     */
    void recordExpansion(String pkg, String version, Set<String> kept, Set<String> filtered) {
        expansions.put(pkg + "@" + version, new Expansion(Set.copyOf(kept), Set.copyOf(filtered)));
    }

    /**
     * Whether a decided package expanded under a set its converged set now disagrees with: an edge
     * it pruned that the converged set keeps, or one it kept that the converged set prunes. The
     * first happens when a clean path registers after the package was decided, the second when the
     * path that registered the clean view was backtracked away from; neither surfaces as a solver
     * conflict, so the solve must run again with the converged sets. False before a round has decided.
     */
    boolean anyExpansionStale() {
        Map<String, String> round = decided;
        if (round == null) return false;
        for (Map.Entry<String, String> e : round.entrySet()) {
            Expansion expansion = expansions.get(e.getKey() + "@" + e.getValue());
            if (expansion == null) continue;
            Set<String> converged = exclusionsFor(e.getKey());
            for (String dep : expansion.filtered()) {
                if (!isExcluded(dep, converged)) return true;
            }
            for (String dep : expansion.kept()) {
                if (isExcluded(dep, converged)) return true;
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
        Expansion expansion = expansions.get(pkg + "@" + version);
        if (expansion == null || expansion.filtered().isEmpty()) return List.of();
        Map<String, Set<String>> view = viewFor(pkg);
        List<String> out = new ArrayList<>(expansion.filtered().size());
        for (String child : new TreeSet<>(expansion.filtered())) {
            if (!isExcluded(child, view.keySet())) continue;
            String ga = gaOf(child);
            Set<String> from = new TreeSet<>();
            for (Map.Entry<String, Set<String>> e : view.entrySet()) {
                if (matches(ga, e.getKey())) from.addAll(e.getValue());
            }
            out.add(from.isEmpty() ? ga : ga + Lockfile.EXCLUSION_ORIGIN_SEPARATOR + String.join(", ", from));
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
