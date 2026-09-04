// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
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
        EngineSpawn.ensure(paths, Jk.VERSION);
        return cancelOnce(EnginePaths.activeSocket(paths), ProtoLifecycle.cancelRequest(jid), jid);
    }

    /**
     * Cancel every live job under {@code dir}. Used by bare {@code jk cancel} and Ctrl-C.
     */
    public static Optional<String> cancelForDir(EnginePaths.Paths paths, String dir) throws IOException {
        EngineSpawn.ensure(paths, Jk.VERSION);
        Optional<String> ack = cancelOnce(EnginePaths.activeSocket(paths), ProtoLifecycle.cancelRequestForDir(dir), -1);
        if (ack.isPresent()) ActiveJobs.forgetAll();
        return ack;
    }

    /**
     * The SIGINT path: the same {@code cancel-request} wire as {@link #cancel}/{@link #cancelForDir},
     * but <em>never</em> spawns or replaces an engine and never blocks long. Call this from the
     * Ctrl-C handler before {@code halt}; the hard exit is the backup if this is too late.
     *
     * <p>Cancels every tracked jid from this CLI process, then a dir-scoped cancel for {@code cwd}.
     * All failures are swallowed.
     */
    public static void cancelBestEffortForInterrupt(Path cwd) {
        try {
            // Overall deadline: each RPC self-limits at SOCKET_TIMEOUT_MILLIS, but N stale jids
            // against a wedged engine would still serialize to N×2s of dead air.
            long deadline = System.nanoTime() + 3 * SOCKET_TIMEOUT_MILLIS * 1_000_000L / 2;
            Path socket = EnginePaths.activeSocket(EnginePaths.current());
            for (long jid : ActiveJobs.snapshot()) {
                if (System.nanoTime() >= deadline) break;
                try {
                    cancelOnce(socket, ProtoLifecycle.cancelRequest(jid), jid);
                } catch (Exception ignored) {
                    // best-effort — halt follows
                }
            }
            if (cwd != null && System.nanoTime() < deadline) {
                try {
                    Optional<String> ack = cancelOnce(socket, ProtoLifecycle.cancelRequestForDir(cwd.toString()), -1);
                    if (ack.isPresent()) ActiveJobs.forgetAll();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        } catch (Throwable ignored) {
            // never throw into the SIGINT handler
        }
    }

    /**
     * One cancel-request RPC on an already-running engine. Does not {@link #ensureRunning}. Used by
     * the public cancel APIs and the interrupt best-effort path.
     *
     * @param forgetJid when ≥ 0, removed from {@link ActiveJobs} on a positive ack
     */
    private static Optional<String> cancelOnce(Path socket, String requestLine, long forgetJid) throws IOException {
        try (SocketChannel ch = EngineWire.connect(socket)) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            writer.write(requestLine);
            writer.write('\n');
            writer.flush();
            // Watchdog: SIGINT must not hang waiting for a wedged engine.
            Thread watchdog = new Thread(
                    () -> {
                        try {
                            Thread.sleep(SOCKET_TIMEOUT_MILLIS);
                            ch.close();
                        } catch (InterruptedException | IOException ignored) {
                            // done
                        }
                    },
                    "jk-cancel-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (EngineProtocol.CANCEL_ACK.equals(EngineProtocol.typeOf(line))) {
                        if (forgetJid >= 0) ActiveJobs.forget(forgetJid);
                        return Optional.of(line);
                    }
                }
            } finally {
                watchdog.interrupt();
            }
        }
        return Optional.empty();
    }
}
