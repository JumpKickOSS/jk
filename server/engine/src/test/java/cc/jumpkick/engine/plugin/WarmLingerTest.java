// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The linger window: ten minutes, three windows on CI, a pin taken as given. */
class WarmLingerTest {

    @AfterEach
    void unpin() {
        WarmLinger.ciForTests(null);
        WarmLinger.forTests(-1);
    }

    @Test
    void a_developer_machine_lingers_ten_minutes_and_ci_three_windows() {
        WarmLinger.ciForTests(false);
        assertThat(WarmLinger.millis()).isEqualTo(Duration.ofMinutes(10).toMillis());
        WarmLinger.ciForTests(true);
        assertThat(WarmLinger.millis())
                .as("a JDK an agent builds once a month does not keep its worker for the agent's life")
                .isEqualTo(Duration.ofMinutes(30).toMillis());
    }

    @Test
    void a_pinned_linger_is_taken_as_given_on_ci_too() {
        WarmLinger.forTests(Duration.ofMinutes(4).toMillis());
        WarmLinger.ciForTests(true);
        assertThat(WarmLinger.millis()).isEqualTo(Duration.ofMinutes(4).toMillis());
    }
}
