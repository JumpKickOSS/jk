// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionContextTest {

    @Test
    void a_virtual_thread_started_under_a_session_reads_that_session() throws Exception {
        Session bound = Session.defaults().withCacheDir(Path.of("bound-cache"));
        AtomicReference<Session> seen = new AtomicReference<>();

        SessionContext.runWhere(bound, () -> {
            try {
                SessionContext.startVirtual("probe", () -> seen.set(SessionContext.current()))
                        .join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(seen.get()).isSameAs(bound);
    }

    @Test
    void a_bare_virtual_thread_reads_the_process_default_instead() throws Exception {
        Session bound = Session.defaults().withCacheDir(Path.of("bound-cache"));
        AtomicReference<Session> seen = new AtomicReference<>();

        SessionContext.runWhere(bound, () -> {
            try {
                Thread.ofVirtual()
                        .start(() -> seen.set(SessionContext.current()))
                        .join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(seen.get()).isNotSameAs(bound);
    }

    @Test
    void a_per_invocation_fact_is_read_once_per_invocation_and_afresh_by_the_next() throws Exception {
        Object key = new Object();
        AtomicInteger reads = new AtomicInteger();

        int first = SessionContext.invocation(() -> {
            int once = SessionContext.perInvocation(key, reads::incrementAndGet);
            assertThat(SessionContext.perInvocation(key, reads::incrementAndGet))
                    .isEqualTo(once);
            return once;
        });
        int second = SessionContext.invocation(() -> SessionContext.perInvocation(key, reads::incrementAndGet));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
    }

    @Test
    void a_per_invocation_fact_outside_an_invocation_is_read_on_every_ask() {
        Object key = new Object();
        AtomicInteger reads = new AtomicInteger();

        SessionContext.perInvocation(key, reads::incrementAndGet);
        SessionContext.perInvocation(key, reads::incrementAndGet);

        assertThat(reads).hasValue(2);
    }

    @Test
    void the_next_pass_of_a_long_lived_invocation_reads_a_per_invocation_fact_again() throws Exception {
        Object key = new Object();
        AtomicInteger reads = new AtomicInteger();
        Session installed = Session.defaults().withConfig(JkConfig.empty());

        SessionContext.invocation(() -> {
            SessionContext.install(installed);
            assertThat((int) SessionContext.perInvocation(key, reads::incrementAndGet))
                    .isEqualTo(1);
            assertThat((int) SessionContext.perInvocation(key, reads::incrementAndGet))
                    .isEqualTo(1);

            SessionContext.nextPass();

            assertThat((int) SessionContext.perInvocation(key, reads::incrementAndGet))
                    .isEqualTo(2);
            assertThat(SessionContext.current())
                    .as("a pass forgets read facts, not the session")
                    .isSameAs(installed);
            return null;
        });
    }
}
