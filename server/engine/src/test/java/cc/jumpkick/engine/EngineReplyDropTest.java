// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A request/reply connection whose client asks and never reads is released on the reply bound —
 * seconds, not the hour a job's stream is allowed — and the engine goes on answering everyone else.
 */
@Tag("integration")
class EngineReplyDropTest extends EngineServerHarness {

    @Test
    void a_client_that_asks_without_reading_is_dropped_on_the_reply_bound() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)));
        try (Client probe = new Client(EnginePaths.activeSocket(p))) {
            assertThat(EngineProtocol.typeOf(probe.send(ProtoLifecycle.statusRequest())))
                    .isEqualTo(EngineProtocol.STATUS_ACK);

            long opened = System.nanoTime();
            long deadline = opened
                    + Duration.ofMillis(EngineConnection.REPLY_IDLE_MS + 30_000).toNanos();
            boolean dropped = false;
            try (SocketChannel deaf = EngineSockets.connect(EnginePaths.activeSocket(p))) {
                ByteBuffer status =
                        ByteBuffer.wrap((ProtoLifecycle.statusRequest() + "\n").getBytes(StandardCharsets.UTF_8));
                // Ask and ask, read nothing: the replies pile up in the engine's queue for this
                // stream until the socket buffers fill and the writer thread blocks on us.
                while (System.nanoTime() < deadline) {
                    try {
                        status.rewind();
                        while (status.hasRemaining()) deaf.write(status);
                    } catch (IOException closedByEngine) {
                        dropped = true;
                        break;
                    }
                    Thread.sleep(20);
                }
            }
            long waitedMs = (System.nanoTime() - opened) / 1_000_000;
            assertThat(dropped)
                    .as("the engine closed the connection that stopped reading")
                    .isTrue();
            assertThat(waitedMs)
                    .as("released on the reply bound, not the stream-idle hour")
                    .isLessThan(EngineConnection.REPLY_IDLE_MS + 30_000);

            String after = probe.send(ProtoLifecycle.statusRequest());
            assertThat(EngineProtocol.typeOf(after))
                    .as("everyone else is still served")
                    .isEqualTo(EngineProtocol.STATUS_ACK);
            assertThat(Jsonl.intValue(after, "activeRequests", -1)).isPositive();
        } finally {
            server.close();
        }
    }
}
