// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.util.Optional;

/** "Did you mean" suggestions: the known name closest to a typo by edit distance. */
public final class Suggest {

    /** The largest edit distance still worth suggesting. */
    public static final int MAX_DISTANCE = 2;

    private Suggest() {}

    /**
     * The name in {@code names} closest to {@code typo}, when it is within {@link #MAX_DISTANCE}
     * edits. On a tie the first in iteration order wins, so the answer is stable.
     */
    public static Optional<String> closest(String typo, Iterable<String> names) {
        String best = null;
        int bestDistance = MAX_DISTANCE + 1;
        for (String name : names) {
            int d = editDistance(typo, name);
            if (d < bestDistance) {
                bestDistance = d;
                best = name;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Edit distance counting single-character insertions, deletions, substitutions and swaps of two
     * adjacent characters (the optimal-string-alignment distance), so {@code stauts} is one edit
     * from {@code status}.
     */
    public static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int best = Math.min(d[i - 1][j - 1] + cost, Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    best = Math.min(best, d[i - 2][j - 2] + 1);
                }
                d[i][j] = best;
            }
        }
        return d[a.length()][b.length()];
    }
}
