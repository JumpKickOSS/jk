// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The {@code drain-status} channel between a lame-duck engine and the successor that displaced it.
 *
 * <p>Both ends of that exchange live here because they are one protocol and every engine plays
 * both roles over its life: a draining engine pushes {@code drain-status} on a tick and one final
 * {@code drain-done}, and a serving engine records what its predecessors report so an operator can
 * see the handover in the log instead of guessing why a port is still busy.
 *
 * <p>It also carries the session records' one writer across the hand-off: a displaced engine writes
 * no session record, so each run it finishes is {@link #finished queued} and sent in the next
 * {@code drain-status}, and the successor {@link #predecessorRuns folds} it into its episode. The
 * last run's record lands after its plan slot is released, so once the drain is over the loop keeps
 * reporting until {@code settled} says every finished run is queued, for at most {@link
 * #SETTLE_MS}.
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

    /** The longest a drained engine waits for its last runs' records before it signs off. */
    static final long SETTLE_MS = 5_000;

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
    private final BooleanSupplier settled;
    private final Connector connector;
    private final Consumer<String> log;
    private final long tickMillis;

    private final AtomicBoolean started = new AtomicBoolean();

    /** Runs finished here and not yet reported, each one JSON object. */
    private final List<String> unreported = new ArrayList<>();

    private volatile @Nullable Thread reporting;

    /** Where a predecessor's finished runs go: the session log's fold. */
    private volatile Consumer<Map<String, Object>> predecessorRun = run -> {};

    /** Predecessor pids currently reporting drain-status to this (successor) engine. */
    private final ConcurrentHashMap<Long, Integer> predecessors = new ConcurrentHashMap<>();

    DrainReporter(
            long pid,
            String version,
            Supplier<Path> successorSocket,
            BooleanSupplier stillDraining,
            IntSupplier activePlans,
            BooleanSupplier settled,
            Connector connector,
            Consumer<String> log,
            long tickMillis) {
        this.pid = pid;
        this.version = version;
        this.successorSocket = successorSocket;
        this.stillDraining = stillDraining;
        this.activePlans = activePlans;
        this.settled = settled;
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
        // Engine-lifetime report to the successor while draining; reads no session.
        reporting = Thread.ofVirtual().name("jk-engine-drain-report").start(this::report);
    }

    /** Wait up to {@code millis} for the report loop to sign off; the engine calls it before it exits. */
    void awaitReported(long millis) {
        Thread t = reporting;
        if (t == null) return;
        try {
            t.join(Duration.ofMillis(millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A run this engine finished and no longer folds itself: report it to the successor. */
    void finished(Map<String, Object> run) {
        String json = MiniJson.write(run);
        synchronized (unreported) {
            unreported.add(json);
        }
    }

    /** Fold each run a predecessor reports with {@code fold}. */
    void predecessorRuns(Consumer<Map<String, Object>> fold) {
        this.predecessorRun = fold != null ? fold : run -> {};
    }

    /** The report loop. Package-private so a test can drive it on the calling thread. */
    void report() {
        Link link = null;
        long settleBy = -1;
        try {
            while (true) {
                if (!stillDraining.getAsBoolean()) {
                    if (settleBy < 0)
                        settleBy = Clock.SYSTEM.nanos()
                                + Duration.ofMillis(SETTLE_MS).toNanos();
                    if (settled.getAsBoolean() || Clock.SYSTEM.nanos() - settleBy >= 0) break;
                }
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
                    send(link);
                } catch (IOException e) {
                    if (link != null) link.close();
                    link = null;
                }
                Thread.sleep(tickMillis);
            }
            if (link != null) {
                if (hasUnreported()) send(link);
                link.send(ProtoLifecycle.drainDone(pid));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // successor gone — we still exit when jobs finish
        } finally {
            if (link != null) link.close();
        }
    }

    /** One {@code drain-status}: the plan count and every run not yet reported, kept for the next one if the send fails. */
    private void send(Link link) throws IOException {
        List<String> runs;
        synchronized (unreported) {
            runs = List.copyOf(unreported);
        }
        link.send(ProtoLifecycle.drainStatus(pid, activePlans.getAsInt(), version, runs));
        synchronized (unreported) {
            unreported.subList(0, runs.size()).clear();
        }
    }

    private boolean hasUnreported() {
        synchronized (unreported) {
            return !unreported.isEmpty();
        }
    }

    /** A predecessor's {@code drain-status} line: its plan count, and the runs it finished, folded here. */
    void predecessorReported(String line) {
        Object parsed;
        try {
            parsed = MiniJson.parse(line);
        } catch (RuntimeException e) {
            return;
        }
        if (!(parsed instanceof Map<?, ?> status)) return;
        long from = status.get("pid") instanceof Number n ? n.longValue() : -1;
        int plans = status.get("plans") instanceof Number n ? n.intValue() : 0;
        predecessorDraining(from, plans);
        if (!(status.get("runs") instanceof List<?> runs)) return;
        for (Object run : runs) {
            try {
                if (run instanceof String text && MiniJson.parse(text) instanceof Map<?, ?> m) {
                    predecessorRun.accept(cast(m));
                }
            } catch (RuntimeException e) {
                log.accept("jk engine: a run predecessor pid " + from + " reported was not folded: " + e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
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
