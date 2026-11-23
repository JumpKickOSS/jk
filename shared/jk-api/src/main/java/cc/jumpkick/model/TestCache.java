// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code [test] cache}: which classes of a suite a run may leave out because an earlier run of them
 * still stands.
 */
public enum TestCache {
    /** The classes that passed under the same run-tests stamp: a red or cut-short suite runs the rest. */
    CLASS,
    /** None: the suite reruns whole or not at all, for suites whose classes share state. */
    MODULE,
    /**
     * As {@link #CLASS}, and across a changed stamp a class that passed and whose key (its cone of
     * workspace classes and what it read) has not moved since is replayed.
     */
    CONE;

    /** The spelling in {@code jk.toml}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code class}, {@code module} or {@code cone}; empty for anything else. */
    public static Optional<TestCache> parse(@Nullable Object raw) {
        for (TestCache c : values()) {
            if (c.wireName().equals(raw)) return Optional.of(c);
        }
        return Optional.empty();
    }
}
