// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import cc.jumpkick.guard.validate.TierPartition;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The workspace's test-tag vocabulary: every tag the root manifest's {@code [test]} and {@code
 * [profiles.*]} include or exclude — the tier table's own words. A tier rule naming a tag outside
 * it is a load error: a tag no tier owns is a test no build runs. Members inherit the root's
 * vocabulary; a tag a member alone declares is not one the workspace's tiers know.
 */
public final class TestTags {

    private TestTags() {}

    public static Set<String> vocabulary(Path root) {
        return new TreeSet<>(TierPartition.table(root).vocabulary());
    }
}
