// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.journal.BuildJournal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the idle boundary does with a cache root that {@code jk cache nuke} removed while this
 * engine kept running: nothing, quietly.
 *
 * <p>The two failure modes are opposite and both were live. Asserting the tree back — which is
 * what the shared lock helper did — undoes the command outright. Tripping over the missing lock
 * file instead, which is what this class did, logs a prune failure at <em>every</em> boundary from
 * then on, because the {@code .last-pruned} stamp went with the root so the schedule is always
 * due.
 */
class IdleHousekeepingNukedCacheTest {

    @Test
    void a_nuked_cache_root_is_neither_recreated_nor_logged_as_a_failed_prune(@TempDir Path home, @TempDir Path roots)
            throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        System.setProperty("jk.env.JK_HOME", home.toAbsolutePath().toString());
        System.setProperty("jk.env.JK_AUTO_PRUNE", "true");
        try {
            // Control. Without it an enqueue that silently never happened would leave the log
            // empty below and this test would pass having exercised nothing.
            Path live = roots.resolve("live");
            Files.createDirectories(live.resolve("actions/keys"));
            Files.writeString(live.resolve("actions/keys/task1"), "x", StandardCharsets.UTF_8);
            IdleHousekeeping control = idle(live, log);
            control.maybeEnqueuePrune(live);
            control.run();
            assertThat(log)
                    .as("the harness really does reach the prune from the boundary")
                    .anyMatch(line -> line.contains("idle-boundary cache prune removed"));
            log.clear();

            // Subject: the root `jk cache nuke` removed a moment ago.
            Path nuked = roots.resolve("nuked");
            IdleHousekeeping housekeeping = idle(nuked, log);
            housekeeping.maybeEnqueuePrune(nuked);
            housekeeping.run();

            assertThat(nuked)
                    .as("the boundary must not put back what the nuke removed")
                    .doesNotExist();
            assertThat(log)
                    .as("and must not report a failure for a cache that is simply not there")
                    .isEmpty();
        } finally {
            System.clearProperty("jk.env.JK_HOME");
            System.clearProperty("jk.env.JK_AUTO_PRUNE");
        }
    }

    /** Draining, so the warmup that would otherwise follow the prune stays out of the way. */
    private static IdleHousekeeping idle(Path cache, List<String> log) {
        return new IdleHousekeeping(
                new AtomicInteger(0),
                new ReentrantReadWriteLock(),
                new JkHistoryConfig(false, 30, 512),
                new BuildJournal(cache.resolveSibling("builds")),
                () -> cache.resolveSibling(cache.getFileName() + "-metrics.jsonl"),
                cache.resolveSibling("engine"),
                System::currentTimeMillis,
                log::add,
                () -> false,
                () -> true,
                () -> {});
    }
}
