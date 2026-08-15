// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Late open after retire must not resurrect a session. */
class JobSessionsTest {

    @Test
    void late_open_after_retire_is_noop() {
        AtomicLong newest = new AtomicLong(5);
        JobSessions sessions = new JobSessions(newest::get);
        JobSession live = sessions.open(5);
        assertThat(live).isNotNull();
        sessions.retire(5);
        assertThat(sessions.retired(5)).isTrue();
        assertThat(sessions.get(5)).isNull();
        assertThat(sessions.open(5)).isNull();
        assertThat(sessions.liveCount()).isZero();
    }

    @Test
    void tracker_after_retire_is_detached() {
        JobSessions sessions = new JobSessions(() -> 1L);
        JobSession s = sessions.open(1);
        assertThat(s).isNotNull();
        var first = s.tracker();
        sessions.retire(1);
        var late = s.tracker();
        assertThat(late).isNotSameAs(first);
        assertThat(sessions.open(1)).isNull();
    }
}
