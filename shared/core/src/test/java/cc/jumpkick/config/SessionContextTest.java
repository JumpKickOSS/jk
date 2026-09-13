// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
}
