// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * De-facto default JDK when no explicit {@code jk jdk default} is set: sole install if one; else
 * current latest-LTS major if installed; else newest installed. Pure/offline — not persisted
 * ({@link GlobalDefaultJdk} wins when present).
 */
public final class DefaultJdkPolicy {

    private DefaultJdkPolicy() {}

    /**
     * Choose the de-facto default among {@code installed}, given the major of the newest LTS that
     * currently exists ({@code latestLtsMajor}; e.g. 25). Empty when nothing is installed (caller
     * should bootstrap-install).
     */
    public static Optional<JdkHit> choose(List<JdkHit> installed, int latestLtsMajor) {
        if (installed == null || installed.isEmpty()) return Optional.empty();

        // Prefer the current latest-LTS major when it's installed; else consider
        // every install and pick the newest.
        List<JdkHit> atLts = new ArrayList<>();
        for (JdkHit h : installed) {
            Integer m = major(h);
            if (m != null && m == latestLtsMajor) atLts.add(h);
        }
        List<JdkHit> pool = new ArrayList<>(atLts.isEmpty() ? installed : atLts);

        pool.sort(Comparator
                // Highest major first (no-op within the LTS pool, decisive otherwise).
                .comparingInt((JdkHit h) -> major(h) == null ? Integer.MIN_VALUE : major(h))
                .reversed()
                // Newest version next.
                .thenComparing(
                        h -> h.version() == null ? "" : JdkSelector.versionKey(h.version()), Comparator.reverseOrder())
                // Vendor preference breaks the remaining ties (lower rank wins).
                .thenComparingInt(DefaultJdkPolicy::vendorRank));
        return Optional.of(pool.getFirst());
    }

    private static Integer major(JdkHit h) {
        String v = h.version();
        if (v == null || v.isEmpty()) return null;
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
        if (end == 0) return null;
        try {
            return Integer.parseInt(v.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int vendorRank(JdkHit h) {
        return h.vendor() == null ? Integer.MAX_VALUE : h.vendor().preferenceRank();
    }
}
