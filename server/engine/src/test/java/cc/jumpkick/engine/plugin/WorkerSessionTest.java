// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The detach is a prefix when the tool exists, the command itself when it does not, and never doubled. */
class WorkerSessionTest {

    private static final List<String> WORKER = List.of("/jdk/bin/java", "-cp", "worker.jar", "cc.jumpkick.Worker");

    @Test
    void a_worker_is_forked_under_setsid_when_the_tool_exists() {
        assertThat(WorkerSession.detached(WORKER, true))
                .containsExactly("/usr/bin/setsid", "/jdk/bin/java", "-cp", "worker.jar", "cc.jumpkick.Worker");
    }

    @Test
    void the_command_is_untouched_where_no_tool_exists() {
        assertThat(WorkerSession.detached(WORKER, false)).isSameAs(WORKER);
    }

    @Test
    void an_already_detached_command_is_not_wrapped_twice() {
        List<String> once = WorkerSession.detached(WORKER, true);
        assertThat(WorkerSession.detached(once, true)).isEqualTo(once);
    }

    @Test
    void availability_is_a_posix_fact_about_the_tool_not_a_guess() {
        assertThat(WorkerSession.available(false, p -> true)).isTrue();
        assertThat(WorkerSession.available(false, p -> false)).isFalse();
        assertThat(WorkerSession.available(true, p -> true))
                .as("Windows has no sessions to detach from")
                .isFalse();
    }
}
