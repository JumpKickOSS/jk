// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.CacheTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Taking the maintenance lock must not be a write.
 *
 * <p>{@code jk cache nuke} removes the cache root and deliberately leaves the engine running —
 * built the local fallback so a cache purge would never boot or bounce one. The lock
 * helper then created the root back, unconditionally, because the cross-process {@code
 * .prune.lock} needs a directory to live in. Any later pass through here put the nuked directory
 * back, including passes that are not about the cache at all: {@code jk repo refresh} and {@code
 * jk storage nuke} borrow this lock while working on the artifact store.
 */
class CacheMaintenanceLocksTest {

    @Test
    void an_absent_cache_root_is_not_created_by_taking_the_lock(@TempDir Path tmp) throws Exception {
        Path nuked = tmp.resolve("cache"); // as `jk cache nuke` leaves it
        AtomicBoolean ran = new AtomicBoolean();

        CacheMaintenanceLocks.exclusively(new ReentrantReadWriteLock(), nuked, null, null, () -> ran.set(true));

        assertThat(ran)
                .as("the body still runs — a store-side pass only borrows this lock")
                .isTrue();
        assertThat(nuked).as("the root the user nuked is still gone").doesNotExist();
    }

    @Test
    void the_non_blocking_form_does_not_create_it_either(@TempDir Path tmp) throws Exception {
        Path nuked = tmp.resolve("cache");
        AtomicBoolean ran = new AtomicBoolean();

        boolean acquired =
                CacheMaintenanceLocks.tryExclusively(new ReentrantReadWriteLock(), nuked, () -> ran.set(true));

        assertThat(acquired).isTrue();
        assertThat(ran).isTrue();
        assertThat(nuked).doesNotExist();
    }

    /**
     * The other half of the contract: where there <em>is</em> a tree to protect, the cross-process
     * lock is still taken, and it is taken before the body runs.
     */
    @Test
    void an_existing_cache_root_still_gets_the_cross_process_lock(@TempDir Path cache) throws Exception {
        AtomicBoolean lockedDuringBody = new AtomicBoolean();

        CacheMaintenanceLocks.exclusively(
                new ReentrantReadWriteLock(),
                cache,
                null,
                null,
                () -> lockedDuringBody.set(Files.isRegularFile(CacheTree.PRUNE_LOCK.under(cache))));

        assertThat(lockedDuringBody).isTrue();
    }
}
