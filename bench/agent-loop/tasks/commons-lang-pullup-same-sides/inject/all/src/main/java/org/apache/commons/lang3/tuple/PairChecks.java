package org.apache.commons.lang3.tuple;

import java.util.List;

/** Checks over several pairs at once. */
final class PairChecks {

    private PairChecks() {
    }

    static boolean allSame(final List<ImmutablePair<String, String>> pairs) {
        return pairs.stream().allMatch(ImmutablePair::sameSides);
    }

    static long countSame(final List<ImmutablePair<String, String>> pairs) {
        long n = 0;
        for (final ImmutablePair<String, String> pair : pairs) {
            if (pair.sameSides()) {
                n++;
            }
        }
        return n;
    }

}
