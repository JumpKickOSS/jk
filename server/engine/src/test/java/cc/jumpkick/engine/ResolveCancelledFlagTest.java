// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.BuildAccumulator;
import org.junit.jupiter.api.Test;

/**
 * Journal/SSE cancel bit: a finished failure must not be re-labelled cancelled when the client
 * closes the socket after the terminal (EOF race).
 */
class ResolveCancelledFlagTest {

    @Test
    void stamped_success_is_never_cancelled() {
        assertThat(BuildAccumulator.resolveCancelledFlag(true, false, true)).isFalse();
        assertThat(BuildAccumulator.resolveCancelledFlag(true, true, true)).isFalse();
    }

    @Test
    void stamped_failure_is_cancelled_only_with_user_stamp() {
        assertThat(BuildAccumulator.resolveCancelledFlag(false, false, true))
                .as("EOF / cooperative cancel hint after a real failure")
                .isFalse();
        assertThat(BuildAccumulator.resolveCancelledFlag(false, true, false))
                .as("user/deadline cancel that ended as failure")
                .isTrue();
        assertThat(BuildAccumulator.resolveCancelledFlag(false, true, true)).isTrue();
    }

    @Test
    void no_outcome_honours_cancel_hint() {
        assertThat(BuildAccumulator.resolveCancelledFlag(null, false, true)).isTrue();
        assertThat(BuildAccumulator.resolveCancelledFlag(null, false, false)).isFalse();
        assertThat(BuildAccumulator.resolveCancelledFlag(null, true, false)).isTrue();
    }
}
