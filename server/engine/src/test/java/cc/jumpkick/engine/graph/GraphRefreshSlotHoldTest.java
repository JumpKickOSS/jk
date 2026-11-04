// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.GraphRefreshSlot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build's graph refresh holds the workspace's refresh slot from before the build returns until
 * the last refresh in flight is done, so a {@code jk clean} that follows waits instead of racing
 * the refresh's writes under {@code target/}.
 */
class GraphRefreshSlotHoldTest {

    @Test
    void the_slot_is_held_until_the_last_refresh_in_flight_is_done(@TempDir Path root) throws Exception {
        Files.createDirectories(GraphRefreshSlot.lockFile(root).getParent());

        GraphRunner.holdSlot(root);
        GraphRunner.holdSlot(root); // a second build's refresh, while the first still runs
        assertThat(heldElsewhere(root)).as("held by the refreshes").isTrue();

        GraphRunner.releaseSlot(root);
        assertThat(heldElsewhere(root)).as("one refresh still runs").isTrue();

        GraphRunner.releaseSlot(root);
        assertThat(GraphRefreshSlot.awaitFree(root, Duration.ZERO))
                .as("free once both are done")
                .isTrue();
    }

    /** What a clean in another thread sees: the slot taken. */
    private static boolean heldElsewhere(Path root) throws Exception {
        boolean[] free = new boolean[1];
        Thread probe = Thread.ofPlatform().start(() -> free[0] = GraphRefreshSlot.awaitFree(root, Duration.ZERO));
        probe.join();
        return !free[0];
    }
}
