// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A suspended session holds steps that have not started; resuming or cancelling lets them go. */
class SessionSuspendTest {

    @Test
    void a_suspended_session_holds_until_resumed_or_cancelled() throws Exception {
        Session.CancelToken token = Session.CancelToken.live();
        token.awaitResumed();

        token.suspend();
        assertThat(token.suspended()).isTrue();
        CountDownLatch passed = new CountDownLatch(1);
        Thread step = Thread.ofPlatform().start(() -> {
            try {
                token.awaitResumed();
                passed.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(passed.await(300, TimeUnit.MILLISECONDS)).isFalse();
        token.resume();
        assertThat(passed.await(5, TimeUnit.SECONDS)).isTrue();
        step.join();

        token.suspend();
        token.cancel();
        assertThat(token.suspended()).as("a cancel ends the suspension").isFalse();
        token.awaitResumed();
        token.suspend();
        assertThat(token.suspended())
                .as("a cancelled session cannot be suspended")
                .isFalse();
    }
}
