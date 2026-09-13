// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Every JVM the launcher forks is flagged from the request's session. The pull-mode shard workers
 * are driven from their own virtual threads, and a thread started bare sees the process default
 * session instead of the request's — so the worker thread is started under the request's binding,
 * and the flags the launcher computes on it carry the request's {@code --jvm-arg}s.
 */
class JUnitLauncherSessionBindingTest {

    private static final PluginTuning REQUEST =
            new PluginTuning(null, null, null, List.of("-Djk.probe=first", "-Djk.probe.out=/tmp/first.txt"));

    @Test
    void a_worker_thread_computes_its_jvm_flags_under_the_request_session() throws Exception {
        AtomicReference<List<String>> flags = new AtomicReference<>();
        AtomicReference<Session> seen = new AtomicReference<>();
        Session request = Session.defaults().withJvm(REQUEST);

        SessionContext.runWhere(request, () -> {
            Thread t = SessionContext.startVirtual("probe-worker", () -> {
                seen.set(SessionContext.current());
                flags.set(new JUnitLauncher().jvmFlags(JvmRole.PULL_WORKER, 2, null));
            });
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(seen.get()).isSameAs(request);
        assertThat(flags.get()).contains("-Djk.probe=first", "-Djk.probe.out=/tmp/first.txt");
    }
}
