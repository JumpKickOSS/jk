// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.Sidecar;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The processes {@code jk dev} runs beside the application. Each sidecar is spawned once per
 * session with its stdout and stderr pumped line by line to a {@link Listener} — the terminal
 * prefix or the JSONL event is the listener's business — and it survives the app's restarts and is
 * torn down with the session — together with the app and with everything it spawned, in one pass. A sidecar that exits on its own is reported once, or
 * restarted with backoff when its manifest says {@code restart = "on-exit"}; five failures in a
 * row is the limit, and a run that passed its probe or stayed up {@link #STABLE_RUN_MILLIS} starts
 * the count over. {@link #awaitReady} runs the probes ({@link ReadyProbe}: an HTTP URL polled for
 * 2xx/3xx, a regex over the output, or a second of staying alive); a probe that times out is a
 * failure of the session, not a warning.
 */
public final class Sidecars implements AutoCloseable {

    static final int MAX_RESTARTS = 5;
    /** A run that lasted this long was a working sidecar; its exit is the first failure, not the next. */
    static final long STABLE_RUN_MILLIS = 30_000;
    /** {@code " │ "} — the light vertical bar between a sidecar's name and its line, as a code point so no encoding can bend it. */
    static final String PREFIX_SEPARATOR = " \u2502 ";
    /** How long the output pipes may lag the exit before the exit is reported without them. */
    static final long DRAIN_AFTER_EXIT_MILLIS = 2_000;

    /**
     * What the session hears from its sidecars: one call per output line, one per lifecycle change.
     * Calls arrive on the sidecar's own threads, in arrival order, with no buffering beyond line
     * assembly; a line is reported before it can count towards a pattern probe.
     */
    public interface Listener {
        void started(String name, long pid);

        /** {@code stream} is {@code stdout} or {@code stderr}; {@code line} has its CR repaints collapsed. */
        void output(String name, String stream, String line);

        /** The probe passed; {@code url} is the {@code ready} URL or empty. */
        void ready(String name, String url, boolean frontDoor);

        /** {@code restartInMs} is negative when no restart is scheduled; {@code gaveUp} when the budget is spent. */
        void exited(String name, long pid, int exit, long restartInMs, boolean gaveUp);

        /** A relaunch that could not start — the one failure with no process to hang it on. */
        void failed(String message);
    }

    /** How the restart path waits out its backoff; {@link #REAL} is the wall clock, a test's returns at once. */
    public interface Sleeper {
        Sleeper REAL = Thread::sleep;

        void sleep(long millis) throws InterruptedException;
    }

    /** Guarded by {@code this}, as is {@code closing}: a restart may not launch across a close. */
    private final List<Running> running = new ArrayList<>();

    private final Listener listener;
    private final Clock clock;
    private final Sleeper sleeper;
    private volatile boolean closing;

    private Sidecars(Listener listener, Clock clock, Sleeper sleeper) {
        this.listener = listener;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Start every sidecar; {@code listener} hears each output line and lifecycle change. */
    public static Sidecars start(List<ExecPlan.Sidecar> specs, Listener listener, Clock clock, Sleeper sleeper)
            throws IOException {
        Sidecars sidecars = new Sidecars(listener, clock, sleeper);
        try {
            synchronized (sidecars) {
                for (ExecPlan.Sidecar spec : specs) sidecars.running.add(sidecars.launch(spec, 0));
            }
        } catch (IOException | RuntimeException e) {
            sidecars.close();
            throw e;
        }
        return sidecars;
    }

    public boolean isEmpty() {
        return snapshot().isEmpty();
    }

    /**
     * Wait for every sidecar's probe. Returns the first failure — a timeout or an exit before
     * readiness — as a printable sentence, or empty when all are ready.
     */
    public Optional<String> awaitReady() throws InterruptedException {
        for (Running r : snapshot()) {
            String failure = r.awaitReady();
            if (failure != null) return Optional.of(failure);
            listener.ready(r.spec.name(), r.spec.probe().ready(), r.spec.frontDoor());
        }
        return Optional.empty();
    }

    /** The URL the session prints once everything is ready: the sidecar marked {@code front-door}, if any. */
    public Optional<String> frontDoor() {
        return snapshot().stream()
                .map(r -> r.spec)
                .filter(ExecPlan.Sidecar::frontDoor)
                .map(s -> s.probe().ready())
                .filter(url -> !url.isEmpty())
                .findFirst();
    }

    @Override
    public void close() {
        stopAlongside(List.of());
    }

    /**
     * Stop every sidecar and {@code companions} — the app — in one pass: one signal, one shared
     * grace, one force-kill, so the session goes down inside the SIGINT hook's bound however many
     * processes it owns. No restart launches once this has begun.
     */
    public void stopAlongside(Collection<Process> companions) {
        List<ProcessHandle> roots = new ArrayList<>();
        synchronized (this) {
            closing = true;
            for (Running r : running) roots.add(r.process.toHandle());
        }
        for (Process p : companions) roots.add(p.toHandle());
        ProcessTrees.stop(roots, clock);
    }

    private synchronized List<Running> snapshot() {
        return List.copyOf(running);
    }

    /** {@code failures} is how many times in a row the sidecar has exited before this launch. */
    private Running launch(ExecPlan.Sidecar spec, int failures) throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder(spec.command()).directory(Path.of(spec.cwd()).toFile());
        pb.environment().putAll(spec.env());
        Process process;
        try {
            process = pb.start();
            // A child that reads stdin sees EOF at once instead of waiting on a pipe nobody writes.
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new IOException(
                    "sidecar `" + spec.name() + "`: cannot start "
                            + spec.command().getFirst() + " in " + spec.cwd() + ": " + e.getMessage(),
                    e);
        }
        Running r = new Running(spec, process, failures, clock);
        listener.started(spec.name(), process.pid());
        CountDownLatch drained = new CountDownLatch(2);
        Thread.ofVirtual()
                .name("sidecar-" + spec.name() + "-out")
                .start(() -> pump(r, "stdout", process.getInputStream(), drained));
        Thread.ofVirtual()
                .name("sidecar-" + spec.name() + "-err")
                .start(() -> pump(r, "stderr", process.getErrorStream(), drained));
        Thread.ofVirtual().name("sidecar-" + spec.name()).start(() -> supervise(r, drained));
        return r;
    }

    private void pump(Running r, String stream, InputStream in, CountDownLatch drained) {
        try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            OutputLines.read(reader, line -> {
                listener.output(r.spec.name(), stream, line); // reported before it can count as ready
                r.sawLine(line);
            });
        } catch (IOException ignored) {
            // the pipe closes when the process goes; the exit is the news
        } finally {
            drained.countDown();
        }
    }

    /**
     * Wait for the exit, let the pipes catch up so every line lands before the exit does, then
     * report it and — under {@code restart = "on-exit"} — schedule the next launch. A grandchild
     * holding the pipes open past the exit gets {@link #DRAIN_AFTER_EXIT_MILLIS}, not a veto.
     */
    private void supervise(Running r, CountDownLatch drained) {
        int exit;
        try {
            exit = r.process.waitFor();
            drained.await(DRAIN_AFTER_EXIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        boolean stable = r.proven();
        if (closing) return;
        long pid = r.process.pid();
        if (r.spec.restart() != Sidecar.Restart.ON_EXIT) {
            listener.exited(r.spec.name(), pid, exit, -1, false);
            return;
        }
        int failures = stable ? 1 : r.failures + 1;
        if (failures > MAX_RESTARTS) {
            listener.exited(r.spec.name(), pid, exit, -1, true);
            return;
        }
        long backoff = Math.min(30_000, 500L << (failures - 1));
        listener.exited(r.spec.name(), pid, exit, backoff, false);
        try {
            sleeper.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            if (closing) return;
            try {
                running.set(running.indexOf(r), launch(r.spec, failures));
            } catch (IOException e) {
                listener.failed(String.valueOf(e.getMessage()));
            }
        }
    }

    /** One spawned sidecar: the process, its readiness state, and how many exits in a row preceded it. */
    private static final class Running {
        final ExecPlan.Sidecar spec;
        final Process process;
        final int failures;
        final Clock clock;
        final long startedAt;
        private final ReadyProbe probe;
        private volatile boolean ready;

        Running(ExecPlan.Sidecar spec, Process process, int failures, Clock clock) {
            this.spec = spec;
            this.process = process;
            this.failures = failures;
            this.clock = clock;
            this.startedAt = clock.nanos();
            this.probe = new ReadyProbe(
                    "sidecar `" + spec.name() + "`", spec.probe(), ReadyProbe.NO_PROBE_ALIVE_MILLIS, process, clock);
        }

        void sawLine(String line) {
            probe.sawLine(line);
        }

        /** True when this run had proven itself — probe passed or a stable stretch alive — by the time it exited. */
        boolean proven() {
            return ready || clock.nanos() - startedAt >= TimeUnit.MILLISECONDS.toNanos(STABLE_RUN_MILLIS);
        }

        @Nullable
        String awaitReady() throws InterruptedException {
            String failure = probe.await();
            if (failure == null) ready = true;
            return failure;
        }
    }
}
