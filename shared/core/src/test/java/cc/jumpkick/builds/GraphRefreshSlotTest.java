// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.FileLocks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A clean waits for a held refresh slot, gives up after its bound, and never waits on a free one. */
class GraphRefreshSlotTest {

    @Test
    void a_free_slot_or_no_target_does_not_wait(@TempDir Path root) throws Exception {
        assertThat(GraphRefreshSlot.awaitFree(root, Duration.ZERO))
                .as("no target/")
                .isTrue();
        Files.createDirectories(GraphRefreshSlot.lockFile(root).getParent());
        assertThat(GraphRefreshSlot.awaitFree(root, Duration.ZERO))
                .as("nobody holds it")
                .isTrue();
    }

    @Test
    void a_held_slot_is_waited_for_until_it_comes_free(@TempDir Path root) throws Exception {
        Files.createDirectories(GraphRefreshSlot.lockFile(root).getParent());
        FileLocks.Hold hold = (FileLocks.Hold) FileLocks.tryHold(GraphRefreshSlot.lockFile(root));

        assertThat(GraphRefreshSlot.awaitFree(root, Duration.ofMillis(120)))
                .as("held past the bound")
                .isFalse();

        CompletableFuture<Boolean> waiter =
                CompletableFuture.supplyAsync(() -> GraphRefreshSlot.awaitFree(root, Duration.ofSeconds(30)));
        Thread.sleep(150);
        assertThat(waiter).isNotDone();
        hold.close();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
    }
}
