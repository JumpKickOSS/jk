// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one table of recognized build-logic script stems, shared by the engine's discovery
 * ({@code BuildLogicScripts}) and the CLI's offline scan ({@code BuildLogicTaskScan}). Two tables
 * meant {@code jk tasks} could advertise a set the engine disagreed with — the same reason
 * {@link BuildLogicToml} lives here. The engine-side anchor enum is not reachable from the
 * native-image CLI, so what is shared is the stem table, not the anchors.
 */
public final class BuildLogicStems {

    /** Module-scope stems: they cut against a module's compile and package steps. */
    public static final List<String> MODULE =
            List.of("before-compile", "after-compile", "after-resources", "before-package");

    /** Invocation-root stems (workspace root, or a standalone project for {@code gate}). */
    public static final List<String> ROOT = List.of("after-build", "guard");

    /** Every recognized base stem, module scope first. */
    public static final List<String> ALL = concat(MODULE, ROOT);

    private BuildLogicStems() {}

    /** Canonical spelling: trimmed, lower-case, underscores as hyphen aliases. */
    public static String normalize(String stem) {
        return stem.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * The base stem {@code stem} names — exactly, or as a suffixed variant
     * ({@code before-compile-collections} → {@code before-compile}). The longest base wins, so the
     * answer does not depend on table order even if one base ever becomes a prefix of another.
     */
    public static Optional<String> match(String stem) {
        if (stem == null || stem.isBlank()) return Optional.empty();
        String n = normalize(stem);
        String best = null;
        for (String base : ALL) {
            if (n.equals(base)) return Optional.of(base);
            if (n.startsWith(base + "-")
                    && n.length() > base.length() + 1
                    && (best == null || base.length() > best.length())) {
                best = base;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The valid stem closest to a typo, when it is close enough (edit distance ≤ 2) to suggest. */
    public static Optional<String> closest(String stem) {
        if (stem == null || stem.isBlank()) return Optional.empty();
        String n = normalize(stem);
        String best = null;
        int bestDistance = 3;
        for (String base : ALL) {
            int d = editDistance(n, base);
            if (d < bestDistance) {
                bestDistance = d;
                best = base;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
