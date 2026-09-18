// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.HostLoad;
import org.junit.jupiter.api.Test;

/** The default stall window grows with the host's load; a window set by hand is taken as written. */
class StallWatchWindowTest {

    @Test
    void the_default_window_stretches_with_the_load_per_processor() {
        assertThat(StallWatch.windowMs(null, 0.5, 8)).isEqualTo(StallWatch.DEFAULT_WINDOW_MS);
        assertThat(StallWatch.windowMs("", -1, 8)).isEqualTo(StallWatch.DEFAULT_WINDOW_MS);
        assertThat(StallWatch.windowMs(null, 146, 24))
                .as("load 146 on 24 processors: seven times the quiet window")
                .isEqualTo(StallWatch.DEFAULT_WINDOW_MS * 7);
        assertThat(StallWatch.windowMs(null, 100_000, 24))
                .isEqualTo(StallWatch.DEFAULT_WINDOW_MS * HostLoad.MAX_FACTOR);
    }

    @Test
    void a_window_set_by_hand_is_taken_as_written_whatever_the_load() {
        assertThat(StallWatch.windowMs("5000", 146, 24)).isEqualTo(5_000L);
        assertThat(StallWatch.windowMs("0", 146, 24))
                .as("0 never stops the work")
                .isZero();
        assertThat(StallWatch.windowMs("not-a-number", 0.5, 8)).isEqualTo(StallWatch.DEFAULT_WINDOW_MS);
    }
}
