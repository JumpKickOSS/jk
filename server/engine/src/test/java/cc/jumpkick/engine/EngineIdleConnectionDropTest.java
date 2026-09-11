// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A connection that never speaks is closed inside the hello window, the slot it held comes back,
 * and the drop is counted in status; a connection that has spoken is not held to that window.
 */
@Tag("integration")
class EngineIdleConnectionDropTest extends EngineServerHarness {

    @Test
    void a_silent_connection_is_closed_within_the_hello_window_and_counted() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)));

        try (Client probe = new Client(EnginePaths.activeSocket(p))) {
            String before = probe.send(ProtoLifecycle.statusRequest());
            assertThat(EngineProtocol.typeOf(before)).isEqualTo(EngineProtocol.STATUS_ACK);
            int baseline = Jsonl.intValue(before, "activeRequests", -1);
            assertThat(baseline).as("the probe itself").isEqualTo(1);
            assertThat(Jsonl.longValue(before, "idleDropped", -1)).isZero();

            long opened = System.nanoTime();
            try (SocketChannel silent = EngineSockets.connect(EnginePaths.activeSocket(p))) {
                waitUntil(
                        Duration.ofSeconds(5),
                        () -> Jsonl.intValue(quietStatus(probe), "activeRequests", -1) == baseline + 1);
                InputStream in = Channels.newInputStream(silent);
                int eof = CompletableFuture.supplyAsync(() -> {
                            try {
                                return in.read();
                            } catch (IOException closed) {
                                return -1;
                            }
                        })
                        .get(EngineConnection.HELLO_IDLE_MS + 5_000, TimeUnit.MILLISECONDS);
                long waitedMs = (System.nanoTime() - opened) / 1_000_000;
                assertThat(eof).as("the engine closed the silent connection").isEqualTo(-1);
                assertThat(waitedMs)
                        .as("closed by the hello window, not merely at some point")
                        .isLessThan(EngineConnection.HELLO_IDLE_MS + 5_000);
            }

            waitUntil(
                    Duration.ofSeconds(5), () -> Jsonl.intValue(quietStatus(probe), "activeRequests", -1) == baseline);
            String after = probe.send(ProtoLifecycle.statusRequest());
            assertThat(Jsonl.longValue(after, "idleDropped", -1)).isEqualTo(1);
            assertThat(Jsonl.intValue(after, "activeRequests", -1)).isEqualTo(baseline);
        } finally {
            server.close();
        }
    }

    @Test
    void a_connection_that_has_spoken_outlives_the_hello_window() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)));
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            assertThat(EngineProtocol.typeOf(c.send(ProtoLifecycle.hello("1.0"))))
                    .isEqualTo(EngineProtocol.HELLO_ACK);
            Thread.sleep(EngineConnection.HELLO_IDLE_MS + 1_000);
            String pong = c.send(ProtoLifecycle.ping());
            assertThat(EngineProtocol.typeOf(pong))
                    .as("still served past the hello window")
                    .isEqualTo(EngineProtocol.PONG);
            assertThat(Jsonl.longValue(c.send(ProtoLifecycle.statusRequest()), "idleDropped", -1))
                    .isZero();
        } finally {
            server.close();
        }
    }

    /** One status round-trip on an already-open connection; {@code ""} when it fails. */
    private static String quietStatus(Client probe) {
        try {
            String reply = probe.send(ProtoLifecycle.statusRequest());
            return reply == null ? "" : reply;
        } catch (IOException e) {
            return "";
        }
    }
}
