// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.JkThreads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A worker forked from a pool task or a started thread files its heap under the key of the work that started it. */
class HeapScopeTest {

    private static final HeapScope.Key KEY = new HeapScope.Key(Path.of("/ws/app"), "g:app", HeapScope.TEST, 25);

    @Test
    void every_pool_and_started_thread_sees_the_key_bound_where_the_work_began() throws Exception {
        List<HeapScope.@Nullable Key> seen = HeapScope.call(KEY, HeapScopeTest::sampleEverywhere);

        assertThat(seen).hasSize(5).containsOnly(KEY);
    }

    @Test
    void work_started_outside_a_key_sees_none() throws Exception {
        assertThat(sampleEverywhere()).hasSize(5).containsOnlyNulls();
    }

    private static List<HeapScope.@Nullable Key> sampleEverywhere() throws Exception {
        List<HeapScope.@Nullable Key> seen = new ArrayList<>();
        for (ExecutorService pool : List.of(JkThreads.cpu(), JkThreads.io(), JkThreads.platform())) {
            seen.add(pool.submit(HeapScope::get).get());
        }
        AtomicReference<HeapScope.@Nullable Key> onVirtual = new AtomicReference<>();
        SessionContext.startVirtual("heap-scope-probe", () -> onVirtual.set(HeapScope.get()))
                .join();
        seen.add(onVirtual.get());
        AtomicReference<HeapScope.@Nullable Key> onPlatform = new AtomicReference<>();
        SessionContext.startPlatform("heap-scope-probe", () -> onPlatform.set(HeapScope.get()))
                .join();
        seen.add(onPlatform.get());
        return seen;
    }
}
