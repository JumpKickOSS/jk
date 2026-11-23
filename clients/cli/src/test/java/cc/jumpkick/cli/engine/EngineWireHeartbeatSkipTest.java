// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.protocol.ProtoReads;
import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

/** The client's protocol reader reads past heartbeats, so no decoder ever takes one for a reply. */
class EngineWireHeartbeatSkipTest {

    @Test
    void heartbeats_around_and_between_reply_lines_never_reach_the_caller() throws Exception {
        String edge = ProtoReads.explainEdge("/ws/app", "/ws/lib");
        String done = ProtoReads.explainDone(1, 2);
        String wire = String.join(
                "\n",
                ProtoLifecycle.heartbeat(30_000),
                edge,
                ProtoLifecycle.heartbeat(60_000),
                ProtoLifecycle.heartbeat(90_000),
                done,
                ProtoLifecycle.heartbeat(120_000));

        try (BufferedReader reader = new EngineWire.HeartbeatSkip(new BufferedReader(new StringReader(wire)))) {
            assertThat(reader.readLine()).isEqualTo(edge);
            assertThat(reader.readLine()).isEqualTo(done);
            assertThat(reader.readLine())
                    .as("a heartbeat before the end of input is no line")
                    .isNull();
        }
    }
}
