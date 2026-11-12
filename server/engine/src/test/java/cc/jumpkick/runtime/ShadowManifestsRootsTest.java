// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A restructured reactor reports each leaf under the root that renders it today. */
class ShadowManifestsRootsTest {

    @Test
    void a_leaf_dropped_or_promoted_to_a_root_is_no_longer_the_old_roots(@TempDir Path dir) {
        Path root = dir.resolve("r");
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        ShadowManifests.recordLeaves(root, List.of(a, b));
        assertThat(ShadowManifests.rootOf(a)).isEqualTo(root);

        ShadowManifests.recordLeaves(root, List.of(b));
        assertThat(ShadowManifests.rootOf(a)).as("dropped from the reactor").isNull();

        ShadowManifests.recordLeaves(b, List.of());
        assertThat(ShadowManifests.rootOf(b)).as("a reactor root of its own").isNull();
    }
}
