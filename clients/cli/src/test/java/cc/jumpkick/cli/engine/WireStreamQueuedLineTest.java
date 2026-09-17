// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.JobQueuedFrame;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a queued client prints: its position, how long it has waited, and who holds the engine. */
class WireStreamQueuedLineTest {

    private static final long SINCE = 1_700_000_000_000L;
    private static final String SINCE_CLOCK = DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(SINCE).atZone(ZoneId.systemDefault()));
    private static final List<JobQueuedFrame.Live> LIVE =
            List.of(new JobQueuedFrame.Live(739, "test", "/home/me/app", SINCE));

    @Test
    void joining_the_queue_names_the_position_and_the_live_job() {
        assertThat(WireStream.waitingLine(new JobQueuedFrame(741, 2, JobQueuedFrame.MEMORY, 0L, LIVE)))
                .isEqualTo("waiting for engine memory (2 jobs ahead); live: test /home/me/app since " + SINCE_CLOCK);
        assertThat(WireStream.waitingLine(new JobQueuedFrame(741, 0, JobQueuedFrame.MEMORY, 0L, List.of())))
                .isEqualTo("waiting for engine memory (next in line)");
        assertThat(WireStream.waitingLine(new JobQueuedFrame(741, 1, JobQueuedFrame.MEMORY, 0L, List.of())))
                .isEqualTo("waiting for engine memory (1 job ahead)");
    }

    @Test
    void a_later_report_says_how_long_it_has_waited() {
        assertThat(WireStream.waitingLine(new JobQueuedFrame(741, 2, JobQueuedFrame.MEMORY, 5 * 60_000L, LIVE)))
                .isEqualTo("queued behind 2 jobs for 5m, live: test /home/me/app since " + SINCE_CLOCK);
        assertThat(WireStream.waitingLine(new JobQueuedFrame(741, 1, JobQueuedFrame.MEMORY, 62 * 60_000L, List.of())))
                .isEqualTo("queued behind 1 job for 1h 02m");
        assertThat(WireStream.formatWait(40_000L)).isEqualTo("40s");
    }
}
