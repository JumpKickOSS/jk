// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.LockFloor;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobTransport;
import cc.jumpkick.engine.verbs.HostedVerb;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.engine.verbs.VerbShape;
import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Serve one accepted socket: the loopback-TCP auth line, framing, and one wire request at a time to
 * one reply — or to a job that then owns the rest of the connection. Always typed, never silence:
 * a silently dropped request wedges a streaming client waiting for a terminal event. A client
 * speaking a newer protocol is refused and closed; an unparseable line or an unknown type is refused
 * and the loop continues; a guarded verb is checked against the lock's floor before it is looked up.
 *
 * <p>A connection that never speaks is closed after {@link #HELLO_IDLE_MS}; one that has sent a
 * request is held to the same stream-idle bound the client applies to the engine. While a job owns
 * the connection the timer is off — a quiet client mid-build is the normal case, and the job's own
 * watchdogs bound its life. Every such close is counted for {@code /api/status}.
 *
 * <p>The connection is served on a platform thread of its own and its replies go out through
 * {@link WireWriter}'s writer thread for this stream, so hello, ping and status are answered
 * whatever the CPU pool and the virtual-thread scheduler are busy with. A client that stops
 * reading is dropped: a request/reply connection after {@link #REPLY_IDLE_MS}, since a reply is
 * one line its client is waiting for, and a connection a job owns after the stream-idle bound the
 * reader applies, since a job's client may be quiet for as long as the job is.
 */
final class EngineConnection {

    /** Grace for a fresh connection to send its first line before it is closed as idle. */
    static final long HELLO_IDLE_MS = 10_000;

    /**
     * How long a request/reply connection's client may leave a reply unread before it is dropped.
     * A probe's reply is one line the client is waiting for; one that has not read it in this long
     * is gone, and its connection thread is released now rather than at the stream-idle bound a
     * job's quiet client is allowed.
     */
    static final long REPLY_IDLE_MS = 10_000;

    /** The {@code shutdown} arm belongs to the lifecycle owner; the connection hands it the line and the writer. */
    interface ShutdownHandler {
        void handle(String line, BufferedWriter writer) throws IOException;
    }

    /**
     * What serving a connection needs from the engine: read-only views of its identity and state,
     * the verb registry, the job envelope, and the two lifecycle callbacks — never the lifecycle lock.
     */
    record Context(
            VerbRegistry verbs,
            JobEnvelope jobs,
            String version,
            long pid,
            long startedAtMillis,
            String buildId,
            @Nullable String expectedToken,
            Supplier<StatusSnapshot> status,
            EngineHttpFront http,
            BooleanSupplier draining,
            DrainReporter drain,
            ShutdownHandler shutdown,
            Runnable idleDropped) {}

    private final Context ctx;

    /** Idle bound once the peer has sent a request; {@code 0} = unbounded. */
    private final long streamIdleMillis;

    EngineConnection(Context ctx) {
        this(ctx, BoundedLineReader.streamIdleMillis(JkDirs::env));
    }

    EngineConnection(Context ctx, long streamIdleMillis) {
        this.ctx = ctx;
        this.streamIdleMillis = streamIdleMillis;
    }

    void serve(SocketChannel ch) {
        BoundedLineReader reader = new BoundedLineReader(
                new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8), ch, HELLO_IDLE_MS);
        try (ch;
                reader;
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))) {
            WireWriter.bind(writer, REPLY_IDLE_MS);
            try {
                String expected = ctx.expectedToken();
                if (expected != null && !authenticate(reader, expected)) {
                    // Typed refusal (then close): a silent close is indistinguishable from a crash.
                    WireWriter.sendQuiet(
                            writer, ProtoLifecycle.error(EngineProtocol.ERR_AUTH, "engine token rejected"));
                    return;
                }
                serveConnection(reader, writer, ch);
            } finally {
                // Every queued line lands before the writer closes under it.
                WireWriter.release(writer);
            }
        } catch (IOException ignored) {
            // client disconnected / socket error mid-exchange — nothing to do
        }
        if (reader.timedOut()) ctx.idleDropped().run();
    }

    /** Loopback-TCP transport only: the connection's first line must be a matching {@link EngineProtocol#AUTH}. */
    private boolean authenticate(BoundedLineReader reader, String expected) throws IOException {
        String line = reader.readLine();
        if (line == null || !EngineProtocol.AUTH.equals(EngineProtocol.typeOf(line))) return false;
        String presented = Jsonl.str(line, "token");
        if (presented == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private void serveConnection(BoundedLineReader reader, BufferedWriter writer, SocketChannel ch) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            // The peer has spoken: from here the gap between requests is the stream-idle bound.
            reader.idleTimeout(streamIdleMillis);
            try {
                if (serveRequest(line, reader, writer, ch)) return;
            } catch (RuntimeException e) {
                // A request the decoders cannot make sense of — a dir that is not a path, a field
                // of the wrong shape — is malformed, and malformed gets the same typed refusal
                // garbage does. Left to escape, the exception closes the socket and the client
                // reads a bare EOF it can only report as an engine crash.
                WireWriter.sendQuiet(
                        writer,
                        ProtoLifecycle.error(
                                EngineProtocol.ERR_PROTOCOL,
                                "malformed request: " + e.getClass().getSimpleName()
                                        + (e.getMessage() == null ? "" : ": " + e.getMessage())));
            }
        }
    }

    /** One request line to its reply or its job. {@code true} when the connection is finished with. */
    private boolean serveRequest(String line, BoundedLineReader reader, BufferedWriter writer, SocketChannel ch)
            throws IOException {
        String type = EngineProtocol.typeOf(line);
        if (type == null) {
            // A garbled REQUEST gets a typed refusal, never silence — a silently-dropped
            // request wedges a streaming client that is waiting for a terminal event.
            WireWriter.sendQuiet(
                    writer,
                    ProtoLifecycle.error(
                            EngineProtocol.ERR_PROTOCOL, "unparseable request line (no \"type\" discriminator)"));
            return false;
        }
        // Lock floor: a jk older than the lock's jk-min refuses with the upgrade error —
        // newer always wins, and nothing runs an older engine to satisfy a lock.
        if (LockFloor.GUARDED.contains(type)) {
            String dir = Jsonl.str(line, "dir");
            String floor = dir == null ? null : LockFloor.requiredNewer(Path.of(dir), ctx.version());
            if (floor != null) {
                WireWriter.send(
                        writer,
                        ProtoLifecycle.error(EngineProtocol.ERR_VERSION_SKEW, LockFloor.message(floor, ctx.version())));
                return true;
            }
        }
        HostedVerb verb = ctx.verbs().find(type);
        if (verb != null) return dispatchVerb(verb, line, reader, writer, ch);
        switch (type) {
            case EngineProtocol.HELLO -> {
                int clientProto = Jsonl.intValue(line, "proto", EngineProtocol.PROTOCOL);
                if (clientProto > EngineProtocol.PROTOCOL) {
                    // A newer-protocol client: this engine must not serve wire semantics
                    // it postdates — the client reacts by taking over (spawn + drain).
                    WireWriter.send(
                            writer,
                            ProtoLifecycle.error(
                                    EngineProtocol.ERR_VERSION_SKEW,
                                    "client speaks protocol " + clientProto + " but this engine speaks "
                                            + EngineProtocol.PROTOCOL + " — start a matching engine"));
                    return true;
                }
                WireWriter.send(
                        writer,
                        ProtoLifecycle.helloAck(
                                ctx.version(),
                                ctx.pid(),
                                ctx.startedAtMillis(),
                                ctx.draining().getAsBoolean(),
                                ctx.buildId()));
            }
            case EngineProtocol.PING -> WireWriter.send(writer, ProtoLifecycle.pong());
            case EngineProtocol.STATUS -> {
                StatusSnapshot s = ctx.status().get();
                HttpEngineServer hs = ctx.http().server();
                String ack = ProtoLifecycle.statusAck(
                        s.vitals(),
                        ctx.draining().getAsBoolean(),
                        hs != null ? hs.url() : null,
                        ctx.http().error(),
                        hs != null && hs.mcpEnabled());
                WireWriter.send(writer, InputTrees.appendToStatusAck(ack));
            }
            case EngineProtocol.SHUTDOWN -> {
                ctx.shutdown().handle(line, writer);
                return true;
            }
            case EngineProtocol.DRAIN_STATUS ->
                ctx.drain().predecessorDraining(Jsonl.longValue(line, "pid", -1), Jsonl.intValue(line, "plans", 0));
            case EngineProtocol.DRAIN_DONE -> ctx.drain().predecessorFinished(Jsonl.longValue(line, "pid", -1));
            case EngineProtocol.CANCEL_REQUEST -> handleCancelRequest(line, writer);
            default ->
                WireWriter.sendQuiet(
                        writer, ProtoLifecycle.error(EngineProtocol.ERR_PROTOCOL, "unknown request type: " + type));
        }
        return false;
    }

    /**
     * Registry dispatch. {@code true} means the verb owns the rest of this connection
     * (async plan / cache maint).
     */
    private boolean dispatchVerb(
            HostedVerb verb, String line, BoundedLineReader reader, BufferedWriter writer, SocketChannel ch)
            throws IOException {
        return switch (verb.shape()) {
            case VerbShape.AsyncPlan() -> {
                // The job owns the connection now and watches it for EOF; a silent client is
                // the normal case for the whole build, so the idle timer must not read it as dead,
                // and its unread lines are held to the stream-idle bound rather than a reply's.
                reader.idleTimeout(0);
                WireWriter.idleBound(writer, streamIdleMillis);
                ctx.jobs().submit(line, verb.toJobRequest(line), new JobTransport.SocketWatch(reader, writer));
                yield true;
            }
            case VerbShape.CacheMaint() -> {
                reader.idleTimeout(0);
                WireWriter.idleBound(writer, streamIdleMillis);
                ctx.jobs().submit(line, verb.toJobRequest(line), new JobTransport.SocketWatch(reader, writer));
                yield true;
            }
            case VerbShape.SyncRead() -> {
                verb.run(line, Session.defaults().cancel(), writer);
                yield false;
            }
        };
    }

    private void handleCancelRequest(String requestLine, BufferedWriter writer) throws IOException {
        long jid = Jsonl.longValue(requestLine, "jid", -1);
        String dir = Jsonl.str(requestLine, "dir");
        if (jid >= 0) {
            boolean ok = ctx.jobs().cancelJob(jid);
            WireWriter.send(writer, ProtoLifecycle.cancelAck(jid, ok, ok ? null : "unknown or already finished jid"));
            return;
        }
        if (dir != null && !dir.isBlank()) {
            int n = ctx.jobs().cancelJobsForDir(dir);
            WireWriter.send(
                    writer,
                    ProtoLifecycle.cancelAck(
                            0, n > 0, n > 0 ? ("cancelled " + n + " job(s)") : "no running jobs for dir"));
            return;
        }
        WireWriter.send(writer, ProtoLifecycle.cancelAck(-1, false, "cancel-request requires jid or dir"));
    }
}
