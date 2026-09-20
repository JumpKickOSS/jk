// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ByeFrame;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Stop, drain or kill an engine process, and reason about the pid file beside its socket. Every
 * kill path refuses the pid of this JVM — in-process engine tests share it — and a live pid whose
 * command is visibly not a JVM is never a kill target either, because a recycled pid must not get an
 * engine's hard kill.
 */
public final class EngineProcessControl {

    /**
     * After force-stop / hard-kill, wait this long for the OS process to exit before escalating.
     * Keeps the next client from racing a half-dead generation.
     */
    private static final Duration STOP_DEATH_WAIT = Duration.ofMillis(1_500);

    private EngineProcessControl() {}

    /**
     * Ask a reachable engine to shut down gracefully; {@code true} if one was reached and acknowledged
     * (or was already not running — stopping a non-running engine is not an error), {@code false} if
     * one was reachable but didn't acknowledge cleanly.
     */
    public static boolean stop(Path socket) {
        SocketChannel ch;
        try {
            ch = EngineWire.connect(socket);
        } catch (IOException e) {
            return true; // nothing reachable — a no-op "stop" is success
        }
        try (ch) {
            String bye = EngineWire.exchange(ch, ProtoLifecycle.shutdown());
            return EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye));
        } catch (IOException e) {
            return false; // reachable but didn't behave — a real problem, not "already stopped"
        }
    }

    /**
     * Schedule a graceful drain: the engine refuses new jobs and exits cleanly once in-flight jobs
     * finish. Returns the in-flight job count at the moment of the request (0 → the engine is exiting
     * now), or {@code -1} when nothing was reachable (a no-op stop).
     */
    public static int drain(@Nullable Path socket) {
        try (SocketChannel ch = EngineWire.connect(socket)) {
            String bye = EngineWire.exchange(ch, ProtoLifecycle.shutdown(false));
            if (!EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye))) return -1;
            return ByeFrame.decode(bye).plans();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Force an immediate shutdown: the engine exits now via its clean-exit path (abandoning in-flight
     * job connections). {@code true} if acknowledged or nothing was
     * running; {@code false} if reachable but unresponsive (caller may {@link #hardKill} as fallback).
     *
     * <p>When a pid file is present for {@code socket}, waits for that process to actually die
     * so the next {@link EngineClient#ensureRunning} does not race a half-stopped generation.
     */
    public static boolean forceStop(@Nullable Path socket) {
        long pid = readPidForSocket(socket);
        SocketChannel ch;
        try {
            ch = EngineWire.connect(socket);
        } catch (IOException e) {
            // Nothing accepting — still wait out a leftover pid if the file is stale-but-alive.
            if (pid > 0) waitForDeathOrKill(pid, STOP_DEATH_WAIT);
            return true;
        }
        try (ch) {
            String bye = EngineWire.exchange(ch, ProtoLifecycle.shutdown(true));
            boolean ok = EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye));
            if (pid > 0) waitForDeathOrKill(pid, STOP_DEATH_WAIT);
            else if (!ok) {
                // bye missing but we connected — best-effort: try pid from a late status is gone;
                // nothing else to wait on.
            }
            return ok;
        } catch (IOException e) {
            if (pid > 0) waitForDeathOrKill(pid, STOP_DEATH_WAIT);
            return false;
        }
    }

    /**
     * Last-resort SIGTERM→SIGKILL when a clean {@link #forceStop} can't reach a wedged engine. Never
     * targets the calling process (in-process {@code EngineServer} tests share this JVM's pid).
     *
     * <p>Interrupt escalates immediately: a Ctrl-C during the 30s grace window means the
     * user wants out now, so the wedged engine gets SIGKILL right away instead of the pre-peel
     * behavior of waiting out the full deadline with the interrupt flag parked. The target is
     * already known-wedged — there is no clean-exit path worth 30 more seconds of a held terminal.
     */
    public static void hardKill(long pid) {
        if (pid <= 0 || pid == ProcessHandle.current().pid()) return;
        ProcessHandle.of(pid).ifPresent(h -> {
            h.destroy();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (h.isAlive() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (h.isAlive()) h.destroyForcibly();
        });
    }

    /**
     * Read the engine pid from the socket's sibling {@code .pid} file (generation-scoped). {@code -1}
     * when missing or unreadable.
     */
    static long readPidForSocket(@Nullable Path socket) {
        if (socket == null) return -1;
        return readPidFile(EnginePaths.pidFor(socket));
    }

    /**
     * The live process named by {@code socket}'s pid file when the socket itself does not answer —
     * a wedged engine still holding this directory's election, which every fresh spawn will lose
     * to. {@code 0} when nothing holds the state (the pid file is absent, stale, or its pid was
     * recycled by something visibly not a JVM), i.e. genuinely not running.
     */
    public static long unresponsiveHolderPid(Path socket) {
        long pid = readPidForSocket(socket);
        if (pid <= 0 || pid == ProcessHandle.current().pid()) return 0;
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                // A recycled pid must not get an engine's hard kill. An unreadable command
                // (privilege) stays a candidate — the pid file is jk-owned state, so a live pid
                // named there is overwhelmingly ours.
                .filter(h -> h.info()
                        .command()
                        .map(c -> c.contains("java") || c.contains("jk"))
                        .orElse(true))
                .map(ProcessHandle::pid)
                .orElse(0L);
    }

    /** First line of a pid file as a long, or {@code -1}. */
    static long readPidFile(Path pidFile) {
        try {
            if (!Files.isRegularFile(pidFile)) return -1;
            String first =
                    Files.readString(pidFile).lines().findFirst().orElse("").trim();
            if (first.isEmpty()) return -1;
            return Long.parseLong(first);
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Wait up to {@code timeout} for {@code pid} to exit; {@link #hardKill} if still alive. No-op when
     * the process is already gone, pid is non-positive, or pid is this JVM (in-process engine tests).
     */
    static void waitForDeathOrKill(long pid, Duration timeout) {
        if (pid <= 0 || pid == ProcessHandle.current().pid()) return;
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) return;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!handle.get().isAlive()) return;
            sleepQuietly(20);
        }
        if (handle.get().isAlive()) hardKill(pid);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
