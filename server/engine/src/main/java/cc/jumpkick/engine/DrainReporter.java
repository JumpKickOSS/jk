// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The {@code drain-status} channel between a lame-duck engine and the successor that displaced it.
 *
 * <p>Both ends of that exchange live here because they are one protocol and every engine plays
 * both roles over its life: a draining engine pushes {@code drain-status} on a tick and one final
 * {@code drain-done}, and a serving engine records what its predecessors report so an operator can
 * see the handover in the log instead of guessing why a port is still busy.
 *
 * <p><b>Reporting, never deciding.</b> Whether this engine is draining, and when it may exit, are
 * decided under the engine's lifecycle lock together with the plan-slot claim. This type reads that
 * decision through {@code stillDraining} and holds no lifecycle state of its own: it takes no lock,
 * claims no slot, and writes nothing another thread reads to make a decision. So the invariant that
 * binds the drain check to the plan claim is not split by this boundary — the worst a broken
 * reporter can do is report a stale count, which is what the successor's own watchdog already
 * tolerates.
 */
final class DrainReporter {

    /** How often a draining engine re-reports to its successor. */
    static final long TICK_MS = 500;

    /** One connection to the successor. An interface so the loop is testable without a socket. */
    interface Link extends AutoCloseable {
        boolean isOpen();

        /** Write one JSONL line and flush it. */
        void send(String line) throws IOException;

        /** Best-effort; never throws. */
        @Override
        void close();
    }

    /** Opens a {@link Link} to whichever engine now owns the endpoint. */
    @FunctionalInterface
    interface Connector {
        Link connect(Path successor) throws IOException;
    }

    private final long pid;
    private final String version;
    private final Supplier<Path> successorSocket;
    private final BooleanSupplier stillDraining;
    private final IntSupplier activePlans;
    private final Connector connector;
    private final Consumer<String> log;
    private final long tickMillis;

    private final AtomicBoolean started = new AtomicBoolean();

    /** Predecessor pids currently reporting drain-status to this (successor) engine. */
    private final ConcurrentHashMap<Long, Integer> predecessors = new ConcurrentHashMap<>();

    DrainReporter(
            long pid,
            String version,
            Supplier<Path> successorSocket,
            BooleanSupplier stillDraining,
            IntSupplier activePlans,
            Connector connector,
            Consumer<String> log,
            long tickMillis) {
        this.pid = pid;
        this.version = version;
        this.successorSocket = successorSocket;
        this.stillDraining = stillDraining;
        this.activePlans = activePlans;
        this.connector = connector;
        this.log = log != null ? log : s -> {};
        this.tickMillis = tickMillis;
    }

    /**
     * Start pushing {@code drain-status} to whoever now owns the endpoint. Idempotent — {@code
     * EngineServer.enterDrain()} is the single caller, and the CAS keeps a re-entered drain from
     * running a second reporter. A no-op in effect when the pointer still names us or nothing is
     * listening: a voluntary {@code jk engine stop} has no successor to tell.
     */
    /** Whether {@link #start} has run — the observable that says drain was entered. */
    boolean started() {
        return started.get();
    }

    void start() {
        if (!started.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("jk-engine-drain-report").start(this::report);
    }

    /** The report loop. Package-private so a test can drive it on the calling thread. */
    void report() {
        Link link = null;
        try {
            while (stillDraining.getAsBoolean()) {
                Path dest = successorSocket.get();
                if (dest == null || !Files.exists(dest)) {
                    Thread.sleep(tickMillis);
                    continue;
                }
                try {
                    if (link != null && !link.isOpen()) {
                        link.close();
                        link = null;
                    }
                    if (link == null) {
                        link = connector.connect(dest);
                        link.send(ProtoLifecycle.hello(version, "probe"));
                    }
                    link.send(ProtoLifecycle.drainStatus(pid, activePlans.getAsInt(), version));
                } catch (IOException e) {
                    if (link != null) link.close();
                    link = null;
                }
                Thread.sleep(tickMillis);
            }
            if (link != null) link.send(ProtoLifecycle.drainDone(pid));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // successor gone — we still exit when jobs finish
        } finally {
            if (link != null) link.close();
        }
    }

    /** A predecessor reports {@code plans} jobs still in flight. Logged once per predecessor. */
    void predecessorDraining(long predecessorPid, int plans) {
        // In-process handover uses one pid for both generations; still record.
        if (predecessorPid <= 0) return;
        if (predecessors.put(predecessorPid, plans) == null) {
            log.accept("jk engine: predecessor pid " + predecessorPid + " is draining (" + plans + " job(s))");
        }
    }

    /** A predecessor finished draining and is about to exit. */
    void predecessorFinished(long predecessorPid) {
        if (predecessorPid <= 0) return;
        predecessors.remove(predecessorPid);
        log.accept("jk engine: predecessor pid " + predecessorPid + " finished draining");
    }

    /** The real transport: an engine→engine client connection, token-gated where the wire is TCP. */
    static Connector sockets() {
        return dest -> new SocketLink(EngineElection.openClient(dest));
    }

    private static final class SocketLink implements Link {
        private final SocketChannel channel;
        private final BufferedWriter writer;

        SocketLink(SocketChannel channel) {
            this.channel = channel;
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8));
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void send(String line) throws IOException {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
