// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One-connection questions about the engine behind a socket: is anything listening, does it speak
 * this protocol, what does it say about itself. Every probe opens its own connection and never
 * starts an engine; an engine advertising a newer protocol reads as unreachable, which is what
 * makes the ensure path replace it.
 */
public final class EngineProbe {

    private EngineProbe() {}

    /** What a connection's {@code hello}/{@code hello-ack} handshake reveals about the engine. */
    public record Handshake(String version, long pid, long startedAtMillis, boolean draining, String buildId) {}

    /**
     * {@code jk engine status} snapshot. Memory fields use {@code -1} when unknown; http fields
     * report embedded server URL/error; {@code aotTrainingPid} is visibility-only.
     */
    public record Status(
            String version,
            long pid,
            long startedAtMillis,
            int activeRequests,
            int activeBuildPlans,
            boolean draining,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes,
            long aotTrainingPid,
            String httpUrl,
            String httpError,
            /** MCP JSON-RPC endpoint when HTTP is up ({@code httpUrl + "/mcp"}), else null. */
            String mcpUrl,
            /** Last-job VFS object from {@code status-ack}, or {@code null} when none yet. */
            String vfsJson,
            int cores,
            long totalMemoryBytes,
            long availableMemoryBytes,
            double systemCpuLoad,
            double systemLoadAverage,
            String engineEpoch) {}

    /**
     * Connect, ping, and get {@code pong} back — the engine-existence check per {@code docs/architecture.md}
     * (never trust a pidfile alone). {@code false} for anything from "nothing is listening" to "it
     * answered something unexpected."
     */
    public static boolean ping(Path socket) {
        try (SocketChannel ch = EngineWire.connect(socket)) {
            String reply = EngineWire.exchange(ch, ProtoLifecycle.ping());
            return EngineProtocol.PONG.equals(EngineProtocol.typeOf(reply));
        } catch (IOException e) {
            return false;
        }
    }

    /** Connect and perform the {@code hello}/{@code hello-ack} handshake; empty if unreachable. */
    public static Optional<Handshake> handshake(Path socket, String clientVersion) {
        try (SocketChannel ch = EngineWire.connect(socket)) {
            String ack = EngineWire.exchange(ch, ProtoLifecycle.hello(clientVersion));
            if (!EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            // Protocol-zero's teeth: an engine speaking a NEWER protocol than this client is not
            // usable — treat it as unreachable so the ensure path elects/starts a matching one
            // (which the newer engine's takeover logic then arbitrates).
            if (Jsonl.intValue(ack, "proto", EngineProtocol.PROTOCOL) > EngineProtocol.PROTOCOL) {
                return Optional.empty();
            }
            String ackBuildId = Jsonl.str(ack, "buildId");
            return Optional.of(new Handshake(
                    Jsonl.str(ack, "version"),
                    Jsonl.longValue(ack, "pid", -1),
                    Jsonl.longValue(ack, "startedAt", -1),
                    Jsonl.bool(ack, "draining", false),
                    ackBuildId == null ? "" : ackBuildId));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a socket connection can be opened at all, regardless of protocol behavior — for host
     * checks (e.g. {@code jk doctor}) that must tell "nothing is listening" (a WARN — lazy-start
     * handles it) apart from "something is listening but not answering" (a wedged engine, a FAIL).
     * {@link #status} alone cannot make that distinction: it returns empty for both.
     */
    public static boolean reachable(Path socket) {
        try (SocketChannel ch = EngineWire.connect(socket)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Connect and request a status snapshot; empty if no engine is reachable. */
    public static Optional<Status> status(Path socket) {
        try (SocketChannel ch = EngineWire.connect(socket)) {
            EngineWire.exchange(ch, ProtoLifecycle.hello(Jk.VERSION, "probe")); // handshake first, response discarded
            String ack = EngineWire.exchange(ch, ProtoLifecycle.statusRequest());
            if (!EngineProtocol.STATUS_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            String httpUrl = Jsonl.str(ack, "httpUrl");
            String mcpUrl = Jsonl.str(ack, "mcpUrl"); // null = MCP disabled
            return Optional.of(new Status(
                    Jsonl.str(ack, "version"),
                    Jsonl.longValue(ack, "pid", -1),
                    Jsonl.longValue(ack, "startedAt", -1),
                    Jsonl.intValue(ack, "activeRequests", -1),
                    Jsonl.intValue(ack, "activeBuildPlans", 0),
                    Jsonl.bool(ack, "draining", false),
                    Jsonl.longValue(ack, "heapUsedBytes", -1),
                    Jsonl.longValue(ack, "heapCommittedBytes", -1),
                    Jsonl.longValue(ack, "heapMaxBytes", -1),
                    Jsonl.longValue(ack, "rssBytes", -1),
                    Jsonl.longValue(ack, "aotTrainingPid", -1),
                    httpUrl,
                    Jsonl.str(ack, "httpError"),
                    mcpUrl,
                    Jsonl.nested(ack, "vfs"),
                    Jsonl.intValue(ack, "cores", -1),
                    Jsonl.longValue(ack, "totalMemoryBytes", -1),
                    Jsonl.longValue(ack, "availableMemoryBytes", -1),
                    Jsonl.doubleValue(ack, "systemCpuLoad", -1),
                    Jsonl.doubleValue(ack, "systemLoadAverage", -1),
                    Jsonl.str(ack, "engineEpoch")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
