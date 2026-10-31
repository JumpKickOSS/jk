// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.jobs.JobSessions;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

class LiveRunsTest {

    @Test
    void a_job_without_its_hold_is_live_until_its_session_retires() {
        JobSessions sessions = new JobSessions(() -> 7L);
        LiveRuns runs = new LiveRuns(
                new InFlightBuilds(), sessions, null, new ReentrantReadWriteLock(), System::currentTimeMillis);
        assertThat(runs.isLive(7L)).isFalse();

        // Finished and released its hold, record not written yet.
        sessions.open(7L);
        assertThat(runs.isLive(7L)).isTrue();

        sessions.retire(7L);
        assertThat(runs.isLive(7L)).isFalse();
    }
}
