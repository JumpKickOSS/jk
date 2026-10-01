// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code stream} retries a stale connection, never the ensure: an engine that cannot start already
 * spends two spawn attempts behind a 30 s ceiling each before ensure gives up, and a refusal is an
 * answer. Doubling either only doubles how long the user waits to be told.
 */
class EngineWireEnsureOnceTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkw-");

    private @Nullable String forcedTransport;

    @BeforeEach
    void isolate() {
        EngineWire.forgetEnsured();
        forcedTransport = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "unix");
    }

    @AfterEach
    void restore() {
        EngineWire.forgetEnsured();
        if (forcedTransport == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
        else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, forcedTransport);
    }

    @Test
    void an_ensure_that_fails_is_attempted_once_and_its_reason_is_the_error() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        AtomicInteger ensures = new AtomicInteger();

        assertThatThrownBy(() -> EngineWire.stream(paths, "{}", (reader, ch) -> "unreached", (p, v) -> {
                    ensures.incrementAndGet();
                    throw new IOException("could not start the build engine");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("could not start the build engine");

        assertThat(ensures).hasValue(1);
    }

    @Test
    void a_refusal_from_ensure_is_not_retried_either() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        AtomicInteger ensures = new AtomicInteger();

        assertThatThrownBy(() -> EngineWire.stream(paths, "{}", (reader, ch) -> "unreached", (p, v) -> {
                    ensures.incrementAndGet();
                    throw new IOException("the build engine is alive and busy");
                }))
                .hasMessage("the build engine is alive and busy");

        assertThat(ensures).hasValue(1);
    }

    @Test
    void a_dead_remembered_endpoint_is_forgotten_and_ensured_once_more() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        EngineWire.rememberEnsured(paths, EnginePaths.activeSocket(paths)); // nothing listens there
        AtomicInteger ensures = new AtomicInteger();

        assertThatThrownBy(() -> EngineWire.stream(paths, "{}", (reader, ch) -> "unreached", (p, v) -> {
                    ensures.incrementAndGet();
                    throw new IOException("could not start the build engine");
                }))
                .hasMessage("could not start the build engine");

        assertThat(ensures).as("the stale memo costs exactly one re-ensure").hasValue(1);
    }

    /**
     * The remembered engine began draining after this process ensured it: it refuses the request
     * before running anything. The request is sent again after a fresh ensure, which is where a
     * draining engine's successor is started.
     */
    @Test
    void a_request_a_draining_engine_refuses_is_sent_again_after_a_fresh_ensure() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        Files.createDirectories(paths.dir());
        AtomicInteger ensures = new AtomicInteger();
        try (ScriptedEngine engine = new ScriptedEngine(
                EnginePaths.activeSocket(paths),
                List.of(ProtoLifecycle.error(EngineProtocol.ERR_SHUTTING_DOWN, "draining")),
                List.of("{\"type\":\"done\"}"))) {
            String reply = EngineWire.stream(
                    paths, "{}", (reader, ch) -> reader.readLine(), (p, v) -> ensures.incrementAndGet());

            assertThat(reply).isEqualTo("{\"type\":\"done\"}");
            assertThat(ensures)
                    .as("the first ensure, then one after the refusal")
                    .hasValue(2);
            assertThat(engine.connections).hasValue(2);
        }
    }

    /** Once the job has started, a later line is the decoder's to read, whatever it says. */
    @Test
    void a_refusal_after_the_job_started_is_not_resent() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        Files.createDirectories(paths.dir());
        AtomicInteger ensures = new AtomicInteger();
        String refusal = ProtoLifecycle.error(EngineProtocol.ERR_SHUTTING_DOWN, "draining");
        try (ScriptedEngine engine =
                new ScriptedEngine(EnginePaths.activeSocket(paths), List.of("{\"type\":\"job-start\"}", refusal))) {
            String second = EngineWire.stream(
                    paths,
                    "{}",
                    (reader, ch) -> {
                        reader.readLine();
                        return reader.readLine();
                    },
                    (p, v) -> ensures.incrementAndGet());

            assertThat(second).isEqualTo(refusal);
            assertThat(ensures).hasValue(1);
            assertThat(engine.connections).hasValue(1);
        }
    }

    /** A Unix-socket stand-in that answers its n-th connection's request with the n-th script. */
    private static final class ScriptedEngine implements AutoCloseable {
        private final ServerSocketChannel listener;
        final AtomicInteger connections = new AtomicInteger();

        @SafeVarargs
        ScriptedEngine(Path socket, List<String>... scripts) throws IOException {
            listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(socket));
            Thread t = new Thread(
                    () -> {
                        for (List<String> script : scripts) {
                            try (SocketChannel ch = listener.accept()) {
                                connections.incrementAndGet();
                                BufferedReader r = new BufferedReader(
                                        new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                                r.readLine();
                                BufferedWriter w = new BufferedWriter(
                                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                                for (String line : script) {
                                    w.write(line);
                                    w.write('\n');
                                }
                                w.flush();
                            } catch (IOException e) {
                                return;
                            }
                        }
                    },
                    "scripted-engine");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }

    @Test
    void an_engine_that_ensures_but_never_listens_is_ensured_at_most_twice() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        AtomicInteger ensures = new AtomicInteger();

        assertThatThrownBy(() -> EngineWire.stream(
                        paths, "{}", (reader, ch) -> "unreached", (p, v) -> ensures.incrementAndGet()))
                .isInstanceOf(IOException.class);

        assertThat(ensures)
                .as("the first ensure, then one more after the connect failed")
                .hasValue(2);
    }
}
