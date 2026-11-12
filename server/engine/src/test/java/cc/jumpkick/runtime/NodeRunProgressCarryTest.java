// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.run.JkThreads;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

/** The request's Node.js download bar follows a run's planning onto every pool. */
class NodeRunProgressCarryTest {

    @Test
    void a_pool_task_reports_to_the_planning_requests_bar() throws Exception {
        ToolProgress bar = ToolProgress.NONE;
        for (ExecutorService pool : List.of(JkThreads.cpu(), JkThreads.io(), JkThreads.platform())) {
            ToolProgress seen = ScopedValue.where(NodeRun.PROGRESS, bar)
                    .call(() -> pool.submit(() -> ContextPropagator.get(NodeRun.PROGRESS))
                            .get());
            assertThat(seen).isSameAs(bar);
        }
        assertThat(JkThreads.cpu()
                        .submit(() -> ContextPropagator.get(NodeRun.PROGRESS))
                        .get())
                .as("unbound off a run")
                .isNull();
    }
}
