// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Issue {@code cancel-request} and reconcile {@link ActiveJobs}. Two entry points ensure an engine
 * first ({@code jk cancel}); the SIGINT path never spawns or replaces one, never throws into the
 * signal handler, and spends at most {@code 1.5 x SOCKET_TIMEOUT_MILLIS} across every tracked jid plus
 * one dir-scoped cancel, so a wedged engine cannot hold the terminal.
 */
public final class EngineCancel {

    /** Per-read/connect socket timeout — a live engine replies in well under this. */
    private static final int SOCKET_TIMEOUT_MILLIS = EngineWire.SOCKET_TIMEOUT_MILLIS;

    private EngineCancel() {}

    /**
     * Cancel a live engine job by jid. Returns the {@code cancel-ack} line, or empty if the
     * engine is unreachable. Idempotent: already-finished jids yield {@code cancelled=false}.
     */
    public static Optional<String> cancel(EnginePaths.Paths paths, long jid) throws IOException {
        ensureUnlessAnswering(paths);
        return cancelOnce(EnginePaths.activeSocket(paths), ProtoLifecycle.cancelRequest(jid), jid);
    }

    /**
     * Cancel every live job under {@code dir}. Used by bare {@code jk cancel} and Ctrl-C.
     */
    public static Optional<String> cancelForDir(EnginePaths.Paths paths, String dir) throws IOException {
        ensureUnlessAnswering(paths);
        Optional<String> ack = cancelOnce(EnginePaths.activeSocket(paths), ProtoLifecycle.cancelRequestForDir(dir), -1);
        if (ack.isPresent()) ActiveJobs.forgetAll();
        return ack;
    }

    /**
     * The jobs to cancel live on whichever engine the endpoint names, draining or of another
     * version; only when none answers is an engine ensured, so a cancel never takes over the
     * engine running the job it is meant to stop.
     */
    private static void ensureUnlessAnswering(EnginePaths.Paths paths) throws IOException {
        if (EngineProbe.handshake(EnginePaths.activeSocket(paths), JkVersion.VERSION)
                .isEmpty()) {
            EngineSpawn.ensure(paths, JkVersion.VERSION);
        }
    }

    /**
     * The SIGINT path: the same {@code cancel-request} wire as {@link #cancel}/{@link #cancelForDir},
     * but <em>never</em> spawns or replaces an engine and never blocks long. Call this from the
     * Ctrl-C handler before {@code halt}; the hard exit is the backup if this is too late.
     *
     * <p>Cancels every tracked jid from this CLI process, and only those: another client's jobs in
     * the same workspace are not this signal's to stop. A jid whose {@code job-start} arrives after
     * the signal is cancelled by the thread that reads it (see {@link ActiveJobs#interrupt}), and
     * the engine cancels a job whose connection closes. All failures are swallowed.
     */
    public static void cancelBestEffortForInterrupt() {
        try {
            cancelTracked(EnginePaths.activeSocket(EnginePaths.current()));
        } catch (Throwable ignored) {
            // never throw into the SIGINT handler
        }
    }

    /** {@link #cancelBestEffortForInterrupt} against the engine at {@code socket}. */
    static void cancelTracked(Path socket) {
        cancelBestEffort(socket, ActiveJobs.interrupt(late -> cancelQuietly(socket, late)));
    }

    /**
     * Cancel each of {@code jids} at the engine on {@code socket}, never spawning or replacing an
     * engine and giving up after a few seconds in all. Failures are swallowed.
     */
    public static void cancelBestEffort(Path socket, Collection<Long> jids) {
        try {
            // Overall deadline: each RPC self-limits at SOCKET_TIMEOUT_MILLIS, but N stale jids
            // against a wedged engine would still serialize to N×2s of dead air.
            long deadline = Clock.SYSTEM.nanos() + 3 * SOCKET_TIMEOUT_MILLIS * 1_000_000L / 2;
            for (long jid : jids) {
                if (Clock.SYSTEM.nanos() >= deadline) break;
                cancelQuietly(socket, jid);
            }
        } catch (Throwable ignored) {
            // never throw into the SIGINT handler
        }
    }

    private static void cancelQuietly(Path socket, long jid) {
        try {
            cancelOnce(socket, ProtoLifecycle.cancelRequest(jid), jid);
        } catch (Exception ignored) {
            // best-effort — halt follows
        }
    }

    /**
     * One cancel-request RPC on an already-running engine. Does not {@link #ensureRunning}. Used by
     * the public cancel APIs and the interrupt best-effort path.
     *
     * @param forgetJid when ≥ 0, removed from {@link ActiveJobs} on a positive ack
     */
    private static Optional<String> cancelOnce(Path socket, String requestLine, long forgetJid) throws IOException {
        Optional<String> ack = requestOnce(socket, requestLine, EngineProtocol.CANCEL_ACK);
        if (ack.isPresent() && forgetJid >= 0) ActiveJobs.forget(forgetJid);
        return ack;
    }

    /**
     * Send {@code requestLine} on a fresh connection and return the first line of type {@code
     * ackType}, or empty when the engine closed without one. Bounded by a socket watchdog, so a
     * signal handler never hangs on a wedged engine.
     */
    static Optional<String> requestOnce(Path socket, String requestLine, String ackType) throws IOException {
        try (SocketChannel ch = EngineWire.connect(socket)) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            writer.write(requestLine);
            writer.write('\n');
            writer.flush();
            Thread watchdog = new Thread(
                    () -> {
                        try {
                            Thread.sleep(SOCKET_TIMEOUT_MILLIS);
                            ch.close();
                        } catch (InterruptedException | IOException ignored) {
                            // done
                        }
                    },
                    "jk-request-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (ackType.equals(EngineProtocol.typeOf(line))) return Optional.of(line);
                }
            } finally {
                watchdog.interrupt();
            }
        }
        return Optional.empty();
    }
}
