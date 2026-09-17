// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The queued line carries the position, the wait so far and the live jobs, and reads back whole. */
class JobQueuedFrameTest {

    @Test
    void round_trips_position_wait_and_live_jobs() {
        JobQueuedFrame frame = new JobQueuedFrame(
                741,
                2,
                JobQueuedFrame.MEMORY,
                300_000L,
                List.of(
                        new JobQueuedFrame.Live(739, "test", "/home/me/app", 1_700_000_000_000L),
                        new JobQueuedFrame.Live(740, "build", "/home/me/lib", 1_700_000_060_000L)));
        String line = frame.encode();
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.JOB_QUEUED);
        assertThat(Jsonl.longValue(line, "jid", -1)).isEqualTo(741);
        assertThat(Jsonl.intValue(line, "ahead", -1)).isEqualTo(2);
        assertThat(Jsonl.longValue(line, "waitedMs", -1)).isEqualTo(300_000L);
        assertThat(Jsonl.objectArray(line, "live")).hasSize(2);
        assertThat(JobQueuedFrame.decode(line)).isEqualTo(frame);
    }

    @Test
    void the_first_line_has_no_wait_and_may_name_no_live_job() {
        String line = ProtoLifecycle.jobQueued(5, 0, 0L, List.of());
        JobQueuedFrame frame = JobQueuedFrame.decode(line);
        assertThat(frame.waitedMs()).isZero();
        assertThat(frame.live()).isEmpty();
        assertThat(frame.reason()).isEqualTo(JobQueuedFrame.MEMORY);
        assertThat(line).contains("\"live\":[]");
    }
}
