// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Listens where an engine would and answers from a script, one decision per request line: a reply,
 * or silence that holds the connection until the client's watchdog gives up. The fake speaks the
 * Unix transport, so it pins the client to that transport for its lifetime whatever the tier
 * forces for the engines it spawns.
 */
public final class ScriptedEngine implements AutoCloseable {

    /** The reply to a request of {@code type} on the {@code connection}th connection; null stays silent. */
    @FunctionalInterface
    public interface Script {
        @Nullable
        String reply(String type, int connection);
    }

    public final AtomicInteger connections = new AtomicInteger();

    /** Every request type received, in arrival order. */
    public final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    private final ServerSocketChannel server;
    private final Script script;
    private final @Nullable String forcedTransport;

    public ScriptedEngine(Path socket, Script script) throws IOException {
        this.script = script;
        forcedTransport = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "unix");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        Thread.ofVirtual().start(this::accept);
    }

    /** An engine that answers nothing on any connection. */
    public static ScriptedEngine silent(Path socket) throws IOException {
        return new ScriptedEngine(socket, (type, connection) -> null);
    }

    /** How many requests of {@code type} arrived. */
    public long received(String type) {
        synchronized (requests) {
            return requests.stream().filter(type::equals).count();
        }
    }

    public static String helloAck(String version, long pid) {
        return ProtoLifecycle.helloAck(version, pid, 1L, false, null);
    }

    public static String statusAck(String version, long pid, int jobs, boolean draining) {
        return ProtoLifecycle.statusAck(
                Map.of("version", version, "pid", pid, "startedAt", 1L, "activeBuildPlans", jobs, "engineEpoch", "e1"),
                draining,
                -1,
                null,
                null,
                false);
    }

    private void accept() {
        try {
            while (true) {
                SocketChannel c = server.accept();
                int n = connections.incrementAndGet();
                Thread.ofVirtual().start(() -> serve(c, n));
            }
        } catch (IOException closed) {
            // the test closed the server
        }
    }

    private void serve(SocketChannel c, int connection) {
        try (c) {
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(c), StandardCharsets.UTF_8));
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                String type = Objects.requireNonNullElse(EngineProtocol.typeOf(line), "");
                requests.add(type);
                String reply = script.reply(type, connection);
                if (reply == null) {
                    // Silent: hold the connection until the client's watchdog closes it.
                    while (in.readLine() != null) {}
                    return;
                }
                c.write(ByteBuffer.wrap((reply + "\n").getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // the client gave up — that is the point
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        if (forcedTransport == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
        else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, forcedTransport);
    }
}
