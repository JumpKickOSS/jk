// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.api.ClientEnvForward;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.EngineHandshake;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
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
        // attempt, and a refusal is an answer, not a stale socket. Only the connect is — the
        // remembered endpoint may be dead because the engine died, was replaced, or the socket
        // moved; forgetting it and ensuring once more may spawn. So is a request the remembered
        // engine refused because it began draining after this process ensured it: the next ensure
        // finds it draining and starts its successor.
        try {
            return streamOnce(paths, requestLine, reply, ensure);
        } catch (DrainingRefusal refused) {
            ENSURED = null;
            try {
                return streamOnce(paths, requestLine, reply, ensure);
            } catch (DrainingRefusal again) {
                throw new IOException(again.getMessage(), again);
            }
        }
    }

    private static <T> T streamOnce(EnginePaths.Paths paths, String requestLine, Reply<T> reply, Ensure ensure)
            throws IOException {
        Path socket = ensuredSocket(paths, ensure);
        SocketChannel opened;
        try {
            opened = connect(socket);
        } catch (IOException stale) {
            ENSURED = null;
            Path again = ensuredSocket(paths, ensure);
            try {
                opened = connect(again);
            } catch (IOException e) {
                throw new IOException(
                        "cannot reach the engine at " + again + " (first tried " + socket + "): " + e.getMessage(), e);
            }
        }
        try (SocketChannel ch = opened) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = new DrainGate(protocolReader(ch));
            writer.write(withShell(requestLine));
            writer.write('\n');
            writer.flush();
            return reply.read(reader, ch);
        }
    }

    /**
     * The engine answered the request's first line with {@link EngineProtocol#ERR_SHUTTING_DOWN}:
     * it is draining and ran nothing. Unchecked so no reply decoder's {@code IOException} handling
     * can swallow it on its way back to {@link #stream}, which retries against the successor.
     */
    static final class DrainingRefusal extends RuntimeException {
        DrainingRefusal(String message) {
            super(message);
        }
    }

    /**
     * Reads the reply as the decoder would, except that a draining refusal before the job ran —
     * as the first line, or after nothing but {@code job-queued} lines — is thrown as {@link
     * DrainingRefusal}: no work happened, so the request can be sent again whole. Once any other
     * line has arrived, every line passes through untouched.
     */
    static final class DrainGate extends BufferedReader {
        private final BufferedReader in;
        private boolean beforeWork = true;

        DrainGate(BufferedReader in) {
            super(in, 1);
            this.in = in;
        }

        @Override
        public @Nullable String readLine() throws IOException {
            String line = in.readLine();
            if (!beforeWork || line == null) return line;
            String type = EngineProtocol.typeOf(line);
            if (EngineProtocol.ERROR.equals(type) && EngineProtocol.ERR_SHUTTING_DOWN.equals(Jsonl.str(line, "code"))) {
                String message = Jsonl.str(line, "message");
                throw new DrainingRefusal(message == null ? "the engine is shutting down" : message);
            }
            if (!EngineProtocol.JOB_QUEUED.equals(type)) beforeWork = false;
            return line;
        }

        @Override
        public void close() throws IOException {
            in.close();
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
        return ensureRemembering(paths, ensure);
    }

    /** Ensure for {@code paths}, unless this invocation already saw that fail; a success is remembered for {@link #stream}. */
    private static Path ensureRemembering(EnginePaths.Paths paths, Ensure ensure) throws IOException {
        AtomicReference<@Nullable IOException> failed = SessionContext.perInvocation(
                new FailedEnsure(paths), () -> new AtomicReference<@Nullable IOException>());
        @Nullable IOException known = failed.get();
        if (known != null) throw new IOException(known.getMessage(), known);
        try {
            ensure.run(paths, JkVersion.VERSION);
        } catch (IOException e) {
            failed.compareAndSet(null, e);
            throw e;
        }
        Path socket = EnginePaths.activeSocket(paths);
        ENSURED = new Ensured(paths, socket);
        return socket;
    }

    /**
     * The key of an ensure this invocation already saw fail, answered at once until the
     * invocation's next pass. One ensure against a silent engine can spend a quarter of a minute
     * probing and minutes waiting, and a command makes several requests; asking again each time
     * only multiplied the wait before the one error that reports it. Per invocation, not per
     * process: a long-lived client ({@code jk dev}, an IDE sync, the MCP server) asks again on its
     * next pass, and invocations sharing a JVM never answer for each other.
     */
    private record FailedEnsure(EnginePaths.Paths paths) {}

    /**
     * The socket of a live, version-matched engine for {@code paths}. A failure answers the rest of
     * the invocation at once; a success is probed afresh, since these callers connect on their own
     * and, unlike {@link #stream}, have no retry for an engine that went away since the last ask.
     */
    static Path ensured(EnginePaths.Paths paths) throws IOException {
        return ensureRemembering(paths, EngineSpawn::ensure);
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
     * How long a connect may wait for the engine to take it. An engine that stopped accepting
     * (stopped, swapping, in a GC death spiral) leaves each connect in its listen queue, and once
     * that is full a blocking connect waits forever, the one in {@code jk engine stop -n} included.
     */
    static final long CONNECT_TIMEOUT_MILLIS = 10_000;

    /**
     * Connect {@code ch} to {@code address} within {@code millis}, then hand it back blocking. A
     * full listen queue answers a non-blocking connect at once; it is asked again until the
     * deadline, and then the connect fails as nothing listening does.
     */
    static void connectWithin(SocketChannel ch, SocketAddress address, long millis) throws IOException {
        long deadline = Clock.SYSTEM.nanos() + TimeUnit.MILLISECONDS.toNanos(millis);
        ch.configureBlocking(false);
        try (Selector selector = Selector.open()) {
            boolean connected;
            while (true) {
                try {
                    connected = ch.connect(address);
                    break;
                } catch (IOException queueFull) {
                    if (!retryable(queueFull) || Clock.SYSTEM.nanos() >= deadline) throw queueFull;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                }
            }
            if (!connected) {
                ch.register(selector, SelectionKey.OP_CONNECT);
                while (!ch.finishConnect()) {
                    long left = TimeUnit.NANOSECONDS.toMillis(deadline - Clock.SYSTEM.nanos());
                    if (left <= 0)
                        throw new SocketTimeoutException(
                                "the engine did not take the connection within " + millis + " ms");
                    selector.select(left);
                }
            }
        }
        ch.configureBlocking(true);
    }

    /** A connect refused because the listener's queue is full, not because nothing listens. */
    private static boolean retryable(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("temporarily unavailable") || m.contains("EAGAIN"));
    }

    /**
     * A connected channel to the engine at {@code endpoint}; {@link #stream} opens one per request. On
     * the loopback-TCP transport (Windows — see {@link cc.jumpkick.wire.EngineTransport}), {@code
     * socket} holds the port number (not a real socket path) and this also runs the {@link
     * EngineHandshake} before returning: the engine proves it holds the token before any request
     * line, shell or secret is written, and every caller is covered without knowing the transport.
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
            try {
                EngineHandshake.client(ch, token, EngineHandshake.ACK_TIMEOUT_MILLIS);
                return ch;
            } catch (Throwable t) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                }
                throw t;
            }
        }
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            connectWithin(ch, UnixDomainSocketAddress.of(socket), CONNECT_TIMEOUT_MILLIS);
            return ch;
        } catch (Throwable t) {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
            throw t;
        }
    }

    /**
     * {@code requestLine} carrying what the engine is told of this shell ({@link #shellFor}), and
     * what this command read from the keychain ({@link ClientKeychain}).
     */
    static String withShell(String requestLine) {
        ClientKeychain.Carried keychain = ClientKeychain.carried();
        return ProtoSession.withKeychain(
                ProtoSession.withShell(requestLine, shellFor(requestLine)), keychain.secrets(), keychain.refused());
    }

    /** The request whose engine side runs a foreign build, which reads whatever it likes. */
    private static final String IMPORT = "import";

    /**
     * The part of this shell a request carries. The whole of it when something needs all of it: a
     * module of the project says {@code [env] inherit = true}, a {@code ${cmd:…}} credential helper
     * in {@code ~/.jk/config.toml} runs under it, or the request is an import, whose Gradle or
     * Maven build reads what it likes. Otherwise the variables the engine reads for itself and the
     * names the project and this user's configuration declare ({@link ClientEnvForward#forwarded}).
     */
    static Map<String, String> shellFor(String requestLine) {
        if (IMPORT.equals(EngineProtocol.typeOf(requestLine))
                || ProjectInfos.wantsWholeShell()
                || ClientEnvForward.userRefs().wholeShell()) {
            return ClientEnvForward.wholeShell();
        }
        Set<String> declared = new HashSet<>(ProjectInfos.declaredNames());
        Map<String, String> resolved = SessionContext.current().clientEnv();
        if (resolved != null) declared.addAll(resolved.keySet());
        return ClientEnvForward.forwarded(declared);
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
