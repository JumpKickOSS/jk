// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AttemptWindowTest {

    private static final long MINUTE = Duration.ofMinutes(1).toNanos();

    @Test
    void one_caller_wins_the_slot_per_key_per_window() {
        AttemptWindow window = new AttemptWindow(Duration.ofMinutes(10));

        assertThat(window.tryAcquire("a", 0)).isTrue();
        assertThat(window.tryAcquire("a", 10 * MINUTE - 1)).isFalse();
        assertThat(window.tryAcquire("b", 0)).as("keys are independent").isTrue();
        assertThat(window.tryAcquire("a", 10 * MINUTE))
                .as("the window has passed")
                .isTrue();
    }

    @Test
    void a_recorded_attempt_closes_the_window_and_only_time_opens_it() {
        AttemptWindow window = new AttemptWindow(Duration.ofMinutes(10));

        assertThat(window.open("a", 0)).isTrue();
        assertThat(window.open("a", 0)).as("asking does not record").isTrue();
        window.record("a", 5 * MINUTE);
        assertThat(window.open("a", 14 * MINUTE)).isFalse();
        assertThat(window.open("a", 15 * MINUTE)).isTrue();
        assertThat(window.open("b", 6 * MINUTE)).isTrue();

        window.clear();
        assertThat(window.open("a", 6 * MINUTE)).isTrue();
    }
}
