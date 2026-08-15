// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exclusive Maven {@code groupId} → repository bindings. When any repo claims a group
 * pattern, version discovery and fetch for matching coordinates use <em>only</em> the claiming
 * repos — other remotes are invisible (dependency-confusion defense).
 *
 * <p>Pattern syntax (data-only, no regex):
 *
 * <ul>
 * <li>{@code com.acme} — exact group match
 * <li>{@code com.acme.*} — {@code com.acme} and any subpackage ({@code com.acme.foo}, …)
 * <li>{@code *} — all groups (unusual; still exclusive to that repo for everything)
 * </ul>
 */
public final class ExclusiveGroups {

    private ExclusiveGroups() {}

    /**
     * Indices into {@code exclusivePatternsPerRepo} that claim {@code groupId}. Empty when no repo
     * claims the group (caller should use the full repo list).
     */
    public static List<Integer> claimantIndices(List<List<String>> exclusivePatternsPerRepo, String groupId) {
        Objects.requireNonNull(exclusivePatternsPerRepo, "exclusivePatternsPerRepo");
        Objects.requireNonNull(groupId, "groupId");
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < exclusivePatternsPerRepo.size(); i++) {
            List<String> patterns = exclusivePatternsPerRepo.get(i);
            if (patterns == null || patterns.isEmpty()) continue;
            for (String p : patterns) {
                if (matches(p, groupId)) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * {@code true} if {@code pattern} claims {@code groupId}. {@code x.*} claims subgroups ONLY —
     * never the bare group {@code x}; claim both with the pair {@code ["x", "x.*"]} (as the
     * built-in lists do). The old bare-inclusive wildcard made it impossible to bind subgroups
     * without also capturing the bare group, and Google's Android Maven exclusively captured
     * {@code com.google.android:annotations} (Central-only) that way, silently dropping
     * grpc-netty-shaded's closure from locks.
     */
    public static boolean matches(String pattern, String groupId) {
        if (pattern == null || pattern.isBlank() || groupId == null || groupId.isBlank()) return false;
        String p = pattern.trim();
        if (p.equals("*")) return true;
        if (p.endsWith(".*")) {
            String prefix = p.substring(0, p.length() - 2);
            if (prefix.isEmpty()) return true;
            return groupId.startsWith(prefix + ".");
        }
        return groupId.equals(p);
    }

    /** {@code true} when any repo declares at least one exclusive group pattern. */
    public static boolean anyBinding(List<List<String>> exclusivePatternsPerRepo) {
        if (exclusivePatternsPerRepo == null) return false;
        for (List<String> p : exclusivePatternsPerRepo) {
            if (p != null && !p.isEmpty()) return true;
        }
        return false;
    }
}
