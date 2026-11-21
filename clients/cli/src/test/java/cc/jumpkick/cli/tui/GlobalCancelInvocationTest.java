// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import org.junit.jupiter.api.Test;

/**
 * A signal's handler runs on a thread of its own, where no invocation is bound: the cancel it sends
 * must still reach the session of the invocation that is running, not the process fallback.
 */
class GlobalCancelInvocationTest {

    @Test
    void a_cancel_from_a_bare_thread_reaches_the_running_invocation() throws Exception {
        Session fallback = SessionContext.installed();
        boolean cancelled = GlobalCancel.invocation(() -> {
            // Config loading installs a fresh session, with a token of its own, after the invocation opens.
            SessionContext.install(Session.defaults());
            Thread signal = Thread.ofPlatform().name("SIGINT handler").start(GlobalCancel::cancelInvocations);
            signal.join();
            return SessionContext.current().cancelled();
        });
        assertThat(cancelled).isTrue();
        assertThat(fallback.cancelled())
                .as("the process fallback is nobody's session")
                .isFalse();
    }

    @Test
    void a_finished_invocation_is_not_cancelled_by_a_later_signal() throws Exception {
        Session[] seen = new Session[1];
        GlobalCancel.invocation(() -> seen[0] = SessionContext.current());
        Thread signal = Thread.ofPlatform().start(GlobalCancel::cancelInvocations);
        signal.join();
        assertThat(seen[0].cancelled()).isFalse();
    }
}
