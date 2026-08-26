// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An offline publish does not upload.
 *
 * <p>This is the one worker in the tree whose unguarded network call has a side effect on someone
 * else's server, and its own comment used to claim the transport carried the guard. It did not:
 * {@code Http.checkOffline} reads the ambient session, and a forked worker JVM starts on
 * {@code Session.defaults()}, where offline is false. So the flag reached the engine, the engine
 * forked the publisher, and the publisher PUT.
 *
 * <p>The assertion is on <strong>connections the destination accepted</strong>, not on the exit
 * code or the error text alone. A publish that fails for any reason exits non-zero, and a socket
 * that is never reachable accepts nothing whatever the guard does — so the online case runs the
 * same spec against the same socket and requires that it <em>is</em> reached. Zero versus non-zero
 * against a live listener is what distinguishes "the guard fired" from "the network was down",
 * which an exit-code assertion cannot.
 */
class PublisherOfflineTest {

    @Test
    void an_offline_publish_never_reaches_the_repository(@TempDir Path dir) throws Exception {
        try (Listener repo = new Listener()) {
            Result offline = publish(dir, repo.url(), true);

            assertThat(repo.accepted())
                    .describedAs("connections the repository accepted under --offline")
                    .isZero();
            assertThat(offline.exit()).isNotZero();
            assertThat(offline.output())
                    .contains("offline: refusing outbound request to")
                    .contains(repo.url());
        }
    }

    /**
     * The control. Same spec, same socket, offline off — the publisher reaches the destination, so
     * the zero above is the guard's doing and not an unreachable address. The upload still fails
     * (this listener speaks no HTTP), which is fine: the claim under test is that a connection
     * happened at all.
     */
    @Test
    void the_same_publish_online_does_reach_it(@TempDir Path dir) throws Exception {
        try (Listener repo = new Listener()) {
            Result online = publish(dir, repo.url(), false);

            assertThat(repo.accepted())
                    .describedAs("connections the repository accepted without --offline")
                    .isPositive();
            assertThat(online.output()).doesNotContain("offline: refusing outbound request to");
        }
    }

    private record Result(int exit, String output) {}

    private static Result publish(Path dir, String repoUrl, boolean offline) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "widget"
                version = "1.2.3"
                """);
        Path jar = dir.resolve("widget-1.2.3.jar");
        Files.write(jar, new byte[] {0x50, 0x4b, 0x05, 0x06}); // empty-zip signature

        Path spec = dir.resolve("publish-" + offline + ".spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                        .configString("repoUrl", repoUrl)
                        .configString("repoAuthType", "anonymous")
                        .configBool("dryRun", false)
                        .offline(offline)
                        .artifact(jar)
                        .layout(Map.of("moduleDir", dir))
                        .lines());

        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        int exit = new Publisher().run(List.of(spec.toString()), out);
        return new Result(exit, buffer.toString(StandardCharsets.UTF_8));
    }

    /** A loopback address that counts what reaches it and speaks nothing back. */
    private static final class Listener implements AutoCloseable {

        private final ServerSocket socket;
        private final AtomicInteger accepted = new AtomicInteger();
        private final Thread acceptor;

        Listener() throws IOException {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            acceptor = new Thread(this::acceptLoop, "publisher-offline-test-listener");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void acceptLoop() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    accepted.incrementAndGet();
                    // Say nothing: the upload fails on EOF instead of hanging on a read timeout.
                } catch (IOException stop) {
                    return;
                }
            }
        }

        String url() {
            return "http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort() + "/repo/";
        }

        int accepted() {
            return accepted.get();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
