// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A module a failure elsewhere stopped is told apart from one that failed. */
class ModuleOutcomeStoppedTest {

    @Test
    void cancelled_steps_in_a_run_that_failed_elsewhere_are_a_stop() {
        assertThat(ModuleOutcome.stoppedByFailure(false, false, List.of("SUCCESS", "CANCELLED"), true))
                .isTrue();
        assertThat(ModuleOutcome.stoppedByFailure(false, false, List.of("SUCCESS", "SKIPPED"), true))
                .as("every step green, ended before it was judged")
                .isTrue();
    }

    @Test
    void a_failed_step_of_its_own_is_a_failure() {
        assertThat(ModuleOutcome.stoppedByFailure(false, false, List.of("FAIL", "CANCELLED"), true))
                .isFalse();
        assertThat(ModuleOutcome.stoppedByFailure(false, false, List.of("failed"), true))
                .isFalse();
    }

    @Test
    void success_a_user_cancel_no_steps_or_nothing_failed_elsewhere_is_not_a_stop() {
        List<String> cancelled = List.of("CANCELLED");
        assertThat(ModuleOutcome.stoppedByFailure(true, false, List.of("SUCCESS"), true))
                .isFalse();
        assertThat(ModuleOutcome.stoppedByFailure(false, true, cancelled, true)).isFalse();
        assertThat(ModuleOutcome.stoppedByFailure(false, false, List.of(), true))
                .isFalse();
        assertThat(ModuleOutcome.stoppedByFailure(false, false, cancelled, false))
                .isFalse();
    }

    @Test
    void with_stopped_keeps_every_other_field() {
        ModuleOutcome failed = new ModuleOutcome("g:a", Path.of("/w/a"), false, 1, 7, true, false);
        ModuleOutcome stopped = failed.withStopped(true);
        assertThat(stopped.stopped()).isTrue();
        assertThat(stopped.withStopped(false)).isEqualTo(failed);
    }
}
