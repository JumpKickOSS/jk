// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Ctrl-C hand-off: every job this process owns is cancelled whether its jid arrived before or after the signal. */
class ActiveJobsTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ActiveJobs.reset();
    }

    @Test
    void a_jid_noted_before_the_interrupt_is_in_its_snapshot() {
        ActiveJobs.note(7);
        List<Long> late = new CopyOnWriteArrayList<>();

        assertThat(ActiveJobs.interrupt(late::add)).containsExactly(7L);
        assertThat(late).isEmpty();
    }

    @Test
    void a_jid_whose_job_start_arrives_after_the_interrupt_is_handed_to_the_canceller() {
        List<Long> late = new CopyOnWriteArrayList<>();

        assertThat(ActiveJobs.interrupt(late::add)).isEmpty();
        ActiveJobs.note(42);

        assertThat(late).containsExactly(42L);
    }
}
