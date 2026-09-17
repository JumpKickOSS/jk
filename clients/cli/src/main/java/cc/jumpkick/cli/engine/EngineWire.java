// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * JSONL I/O against a live engine socket. {@link #exchange} is the one-shot form (write a line,
 * read the single reply); {@link #stream} is the framing every request-then-read-a-stream verb
 * shares — ensure an engine, connect, write exactly one request line, hand the reader to the
 * caller's decoder, close.
 *
 * <p>That framing was written out nine times inside the old {@code EngineBuildListenerAdapter}
 * alone, and twice more in each of {@link EnginePluginAdapter} and {@link EngineResolveAdapter}
 *. Thirteen copies of "ensure, connect, write, read" is thirteen chances for one of them
 * to skip the ensure or leak the channel; it exists once now.
 */
public final class EngineWire {

    /** Per-read/connect socket timeout — a live engine replies in well under this. */
    static final int SOCKET_TIMEOUT_MILLIS = 2_000;

    private EngineWire() {}

    /**
     * Reads one request's reply off {@code reader}. {@code ch} is the same connection, for the
     * job pumps that half-close their write side while waiting for {@code job-finish}.
     */
    @FunctionalInterface
    interface Reply<T> {
        T read(BufferedReader reader, SocketChannel ch) throws IOException;
    }

    /**
     * Ensure a live version-matched engine, open a fresh connection, write {@code requestLine} as
     * one JSONL line, and let {@code reply} read the answer to its terminal. The connection closes
     * when this returns, however it returns.
     */
    static <T> T stream(EnginePaths.Paths paths, String requestLine, Reply<T> reply) throws IOException {
        return stream(paths, requestLine, reply, EngineSpawn::ensure);
    }

    /** How {@link #stream} brings up an engine; the seam a test replaces to count attempts. */
    @FunctionalInterface
    interface Ensure {
        void run(EnginePaths.Paths paths, String clientVersion) throws IOException;
    }

    /**
     * An {@link Ensure} that never spawns: the request is served by an engine that is already
     * running and serves this client, or it fails with {@link EngineClient.EngineNotRunningException}.
     * For a diagnostic ({@code jk doctor}) that must not change the state it inspects.
     */
    static final Ensure RUNNING_ONLY = (paths, clientVersion) -> {
        Optional<EngineProbe.Handshake> live = EngineProbe.handshake(EnginePaths.activeSocket(paths), clientVersion);
        if (live.isEmpty() || live.get().draining()) throw new EngineClient.EngineNotRunningException();
    };

    static <T> T stream(EnginePaths.Paths paths, String requestLine, Reply<T> reply, Ensure ensure) throws IOException {
        // Ensuring is not retried: it already spawns twice with backoff behind a 30 s ceiling per
        // attempt, and a refusal ("engine is shutting down") is an answer, not a stale socket. Only
        // the connect is — the remembered endpoint may be dead because the engine died, was
        // replaced, or the socket moved; forgetting it and ensuring once more may spawn.
        Path socket = ensuredSocket(paths, ensure);
        SocketChannel opened;
        try {
            opened = connect(socket);
        } catch (IOException stale) {
            ENSURED = null;
            opened = connect(ensuredSocket(paths, ensure));
        }
        try (SocketChannel ch = opened) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = protocolReader(ch);
            writer.write(requestLine);
            writer.write('\n');
            writer.flush();
            return reply.read(reader, ch);
        }
    }

    /**
     * The engine endpoint this process has already ensured, if any.
     *
     * <p>An {@code ensure} opens a connection, completes a {@code hello}/{@code hello-ack} exchange
     * with its own watchdog thread and closes it. Done per RPC, {@code jk build}'s three or four
     * RPCs would cost six to eight connects and endpoint-file reads on a native binary with no JIT
     * to amortise any of it — and on Windows each connect also reads the port file and the token
     * file.
     *
     * <p>Ensuring once per process is safe because the thing it establishes — that a live,
     * version-matched engine is listening here — is exactly what a failed connect disproves. So the
     * memo is only ever wrong in the direction the next connect catches, and {@code stream} then
     * forgets it and ensures once more.
     */
    private static volatile @Nullable Ensured ENSURED;

    private record Ensured(EnginePaths.Paths paths, Path socket) {}

    /** The socket for {@code paths}, ensuring a live engine the first time this process asks. */
    private static Path ensuredSocket(EnginePaths.Paths paths, Ensure ensure) throws IOException {
        Ensured hit = ENSURED;
        if (hit != null && hit.paths().equals(paths)) return hit.socket();
        ensure.run(paths, JkVersion.VERSION);
        Path socket = EnginePaths.activeSocket(paths);
        ENSURED = new Ensured(paths, socket);
        return socket;
    }

    /** Test seam: forget the ensured endpoint, so the next RPC re-probes. */
    static void forgetEnsured() {
        ENSURED = null;
    }

    /** Test seam: remember {@code socket} as {@code paths}' ensured endpoint without probing it. */
    static void rememberEnsured(EnginePaths.Paths paths, Path socket) {
        ENSURED = new Ensured(paths, socket);
    }

    /**
     * The client-side protocol reader: line-capped to what this process's heap can hold ({@link
     * BoundedLineReader#maxLineForHeap}), and idle-timed so a dead engine surfaces as an error
     * instead of a forever-blocked {@code readLine}. The idle bound is {@link
     * BoundedLineReader#streamIdleMillis} — the same one the engine holds its clients to.
     */
    static BoundedLineReader protocolReader(SocketChannel ch) {
        return new BoundedLineReader(
                new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8),
                ch,
                BoundedLineReader.streamIdleMillis(System::getenv),
                BoundedLineReader.maxLineForHeap(Runtime.getRuntime().maxMemory()));
    }

    /**
     * Package-visible: {@link #stream} opens a fresh long-lived connection per request. On
     * the loopback-TCP transport (Windows — see {@link cc.jumpkick.wire.EngineTransport}), {@code
     * socket} holds the port number (not a real socket path) and this also sends the required
     * {@link EngineProtocol#AUTH} line before returning, so every caller authenticates transparently
     * without needing its own knowledge of the transport.
     */
    static SocketChannel connect(@Nullable Path endpoint) throws IOException {
        if (endpoint == null) throw new IOException("no engine endpoint to connect to");
        Path socket = endpoint;
        if (EngineTransport.useLoopbackTcp()) {
            // A killed engine can leave this file empty or half-written. "No port here" means the
            // same thing to every caller as nothing listening, and they all handle IOException —
            // an escaping NumberFormatException would instead take the probe down with it.
            int port;
            String raw = Files.readString(socket).trim();
            try {
                port = Integer.parseInt(raw);
            } catch (NumberFormatException notAPort) {
                throw new IOException("no engine port in " + socket + " (stale or half-written)", notAPort);
            }
            String token = Files.readString(EnginePaths.tokenFor(socket)).trim();
            SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            BufferedWriter authWriter =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            authWriter.write(ProtoLifecycle.auth(token));
            authWriter.write('\n');
            authWriter.flush();
            return ch;
        }
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(socket));
        return ch;
    }

    /**
     * Send one line, read one reply line, over an already-connected channel. {@link SocketChannel}
     * has no built-in read timeout, so a watchdog thread closes the channel if the engine doesn't
     * reply in time.
     */
    static String exchange(SocketChannel ch, String line) throws IOException {
        return exchange(ch, line, SOCKET_TIMEOUT_MILLIS);
    }

    /** {@link #exchange(SocketChannel, String)} with the reply wait chosen by the caller. */
    static String exchange(SocketChannel ch, String line, int replyTimeoutMillis) throws IOException {
        BufferedWriter writer =
                new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
        writer.write(line);
        writer.write('\n');
        writer.flush();
        BufferedReader reader = protocolReader(ch);
        Thread watchdog = new Thread(
                () -> {
                    try {
                        Thread.sleep(replyTimeoutMillis);
                        ch.close();
                    } catch (InterruptedException ignored) {
                        // exchange finished in time — nothing to do
                    } catch (IOException ignored) {
                        // already closing
                    }
                },
                "jk-engine-client-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            String reply = reader.readLine();
            if (reply == null) throw new IOException("engine closed the connection without replying");
            return reply;
        } catch (AsynchronousCloseException e) {
            throw new IOException("engine did not reply within " + replyTimeoutMillis + "ms", e);
        } finally {
            watchdog.interrupt();
        }
    }
}
