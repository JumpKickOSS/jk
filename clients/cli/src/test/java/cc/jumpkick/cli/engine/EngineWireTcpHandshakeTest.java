// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EngineHandshake;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.AuthAckFrame;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * On loopback TCP the client connects only to a listener that proves it holds the engine's token.
 * A process that took the port after the engine died hears a nonce and nothing else: not the token,
 * not a request, not the shell.
 */
class EngineWireTcpHandshakeTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkh-");

    private @Nullable String previousTransport;

    @BeforeEach
    void forceTcp() {
        previousTransport = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "tcp");
    }

    @AfterEach
    void restoreTransport() {
        if (previousTransport == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
        else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, previousTransport);
    }

    @Test
    void a_listener_without_the_token_is_refused_before_anything_is_sent() throws Exception {
        String token = EngineTransport.newToken();
        List<String> heard = new CopyOnWriteArrayList<>();
        try (Listener squatter = new Listener(heard, auth -> new AuthAckFrame("squatter", "forged").encode())) {
            Path socket = squatter.endpoint(tempDirs.create(), token);

            assertThatThrownBy(() -> EngineWire.connect(socket).close())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("did not prove it is the engine");
            squatter.awaitClosed();
        }
        assertThat(heard).hasSize(1);
        assertThat(EngineProtocol.typeOf(heard.getFirst())).isEqualTo(EngineProtocol.AUTH);
        assertThat(String.join("\n", heard)).doesNotContain(token);
    }

    @Test
    void the_engine_proves_itself_and_the_client_answers_with_its_own_proof() throws Exception {
        String token = EngineTransport.newToken();
        List<String> heard = new CopyOnWriteArrayList<>();
        EngineHandshake.Challenge[] challenge = new EngineHandshake.Challenge[1];
        try (Listener engine = new Listener(heard, auth -> {
            challenge[0] = Objects.requireNonNull(EngineHandshake.challenge(token, auth));
            return challenge[0].ack();
        })) {
            Path socket = engine.endpoint(tempDirs.create(), token);
            EngineWire.connect(socket).close();
            engine.awaitClosed();
        }
        assertThat(heard).hasSize(2);
        assertThat(challenge[0].accepts(heard.get(1))).isTrue();
        assertThat(String.join("\n", heard)).doesNotContain(token);
    }

    /** A loopback listener that answers the first line with {@code reply} and records every line it hears. */
    private static final class Listener implements AutoCloseable {
        private final ServerSocketChannel server;
        private final Thread thread;

        Listener(List<String> heard, Function<String, String> reply) throws IOException {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            thread = Thread.ofPlatform().daemon().start(() -> {
                try (SocketChannel ch = server.accept()) {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                    BufferedWriter w = new BufferedWriter(
                            new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                    String first = r.readLine();
                    if (first == null) return;
                    heard.add(first);
                    w.write(reply.apply(first));
                    w.write('\n');
                    w.flush();
                    String line;
                    while ((line = r.readLine()) != null) heard.add(line);
                } catch (IOException ignored) {
                    // the client hung up
                }
            });
        }

        /** The port file and token file a client reads, pointing at this listener. */
        Path endpoint(Path dir, String token) throws IOException {
            EnginePaths.Paths gen = EnginePaths.generation(EnginePaths.resolve(dir), 1);
            Files.createDirectories(gen.dir());
            Files.createDirectories(Objects.requireNonNull(gen.socket().getParent()));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            Files.writeString(gen.socket(), Integer.toString(port));
            OwnerOnlyFiles.write(Objects.requireNonNull(gen.token().getParent()), gen.token(), token);
            return gen.socket();
        }

        void awaitClosed() throws InterruptedException {
            thread.join(10_000);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
