// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The loopback-TCP transport (Windows' lane), forced via -Djk.engine.transport=tcp so the auth
 * handshake is exercised off-Windows. Regression: engine→engine signalling (helloProbe /
 * EngineElection.askPredecessorToYield) once sent a raw token line where the server requires the {@code auth} envelope,
 * so same-version election and takeover drain silently failed on TCP.
 */
@Tag("integration")
class EngineTcpTransportTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkt-");

    @BeforeAll
    static void forceTcpTransport() {
        System.setProperty("jk.engine.transport", "tcp");
    }

    @AfterAll
    static void restoreTransport() {
        System.clearProperty("jk.engine.transport");
    }

    @AfterEach
    void resetSharedWorkerHeapPlan() {
        JvmOptions.resetSharedPlanForTests();
    }

    /** Hello over TCP the way any client must: auth envelope first, then the hello. */
    private static @Nullable String tcpHelloVersion(Path socketFile) {
        try {
            int port = Integer.parseInt(Files.readString(socketFile).trim());
            String token = Files.readString(EnginePaths.tokenFor(socketFile)).trim();
            try (SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                BufferedReader r =
                        new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                w.write(ProtoLifecycle.auth(token));
                w.write('\n');
                w.write(ProtoLifecycle.hello("probe"));
                w.write('\n');
                w.flush();
                String ack = r.readLine();
                if (ack == null || !EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return null;
                return Jsonl.str(ack, "version");
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Test
    void same_version_election_and_hello_work_over_tcp() throws Exception {
        Path state = tempDirs.create();
        EnginePaths.Paths p = EnginePaths.resolve(state);

        EngineServer first = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
        Thread t = new Thread(() -> {
            try {
                first.run();
            } catch (IOException ignored) {
            }
        });
        t.start();
        try {
            Await.until(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
            assertThat(tcpHelloVersion(EnginePaths.activeSocket(p))).isEqualTo("1.0.0-test");

            // The second same-version instance must LOSE the election — which only happens when
            // its helloProbe authenticates correctly over TCP. Before the auth-envelope fix this
            // probe silently failed and the second instance won a fresh generation instead.
            EngineServer second = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0.0-test", null);
            assertThat(second.run())
                    .as("same-version spawn race loses the election over TCP")
                    .isFalse();
        } finally {
            first.close();
            t.join(10_000);
        }
    }
}
