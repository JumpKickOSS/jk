// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A hold ends when the job does: its helper must not hold the job's thread on the way out. */
class ChildHoldTest {

    @Test
    void closing_a_helper_that_ignores_its_stdin_ends_it_at_once() {
        FakeHelper helper = new FakeHelper();
        helper.ignoresStdin = true;
        ChildHold hold = requireNonNull(ChildHold.start(helper, List.of("caffeinate", "-i")));
        assertThat(hold.alive()).isTrue();

        long t0 = System.nanoTime();
        hold.close();

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(500));
        assertThat(hold.alive()).isFalse();
    }

    @Test
    void closing_a_helper_that_reads_its_stdin_ends_it() {
        FakeHelper helper = new FakeHelper();
        ChildHold hold = requireNonNull(ChildHold.start(helper, List.of("systemd-inhibit", "cat")));
        hold.close();
        assertThat(hold.alive()).isFalse();
    }
}
