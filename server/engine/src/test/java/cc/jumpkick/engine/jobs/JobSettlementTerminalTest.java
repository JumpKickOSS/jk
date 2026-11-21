// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.StalledClient;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.testing.Await;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cancel unpaces the job's stream, and an unpaced stream drops a paced line it has no room for.
 * The client blocks on {@code job-finish}, so the settlement's terminal lines must queue whatever
 * the stream holds.
 */
class JobSettlementTerminalTest {

    @Test
    @Timeout(120)
    void a_cancelled_job_s_terminal_lines_reach_a_client_behind_a_full_unpaced_stream(@TempDir Path spill)
            throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.accumulator = new BuildAccumulator("build", "/p", null, "cli");
        host.accumulator.markUserCancelled(true, "cancelled by the user");
        JobSettlement settlement = new JobSettlement(host, host, host, host);
        try (StalledClient client = StalledClient.open(spill)) {
            client.fillPastBound();
            WireWriter.unpace(client.writer());

            settlement.settle(7L, "build", "/p", true, false, client.writer(), 0L, true);
            client.startReading();

            String terminal = LiveJobRegistry.cancelledTerminalLine(false, "/p");
            Await.until(
                    Duration.ofSeconds(20),
                    () -> client.read().stream().anyMatch(l -> l.contains("\"job-finish\"")),
                    () -> "the client never read job-finish; it read "
                            + client.read().size() + " lines");
            List<String> tail = client.read().stream()
                    .filter(l -> l.equals(terminal) || l.contains("\"job-finish\""))
                    .toList();
            assertThat(tail).hasSize(2);
            assertThat(tail.get(0)).isEqualTo(terminal);
            assertThat(tail.get(1)).contains("\"job-finish\"");
        }
    }
}
