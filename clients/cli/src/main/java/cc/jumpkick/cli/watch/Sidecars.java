// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.Sidecar;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The processes {@code jk dev} runs beside the application. Each sidecar is spawned once per
 * session with its output pumped line by line to {@code report}, prefixed with its name; it
 * survives the app's restarts and is torn down with the session — together with the app and with
 * everything it spawned, in one pass. A sidecar that exits on its own is reported once, or
 * restarted with backoff when its manifest says {@code restart = "on-exit"}; five failures in a
 * row is the limit, and a run that passed its probe or stayed up {@link #STABLE_RUN_MILLIS} starts
 * the count over. {@link #awaitReady} runs the probes: an HTTP URL polled for 2xx/3xx, a regex over
 * the output, or a second of staying alive; a probe that times out is a failure of the session, not
 * a warning.
 */
public final class Sidecars implements AutoCloseable {

    static final long NO_PROBE_ALIVE_MILLIS = 1_000;
    static final int MAX_RESTARTS = 5;
    /** A run that lasted this long was a working sidecar; its exit is the first failure, not the next. */
    static final long STABLE_RUN_MILLIS = 30_000;
    /** {@code " │ "} — the light vertical bar between a sidecar's name and its line, as a code point so no encoding can bend it. */
    static final String PREFIX_SEPARATOR = " \u2502 ";

    /** How the restart path waits out its backoff; {@link #REAL} is the wall clock, a test's returns at once. */
    public interface Sleeper {
        Sleeper REAL = Thread::sleep;

        void sleep(long millis) throws InterruptedException;
    }

    /** Guarded by {@code this}, as is {@code closing}: a restart may not launch across a close. */
    private final List<Running> running = new ArrayList<>();

    private final Consumer<String> report;
    private final Clock clock;
    private final Sleeper sleeper;
    private volatile boolean closing;

    private Sidecars(Consumer<String> report, Clock clock, Sleeper sleeper) {
        this.report = report;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Start every sidecar; {@code report} receives each output line, prefixed, and lifecycle notes. */
    public static Sidecars start(List<ExecPlan.Sidecar> specs, Consumer<String> report, Clock clock, Sleeper sleeper)
            throws IOException {
        Sidecars sidecars = new Sidecars(report, clock, sleeper);
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
        }
        return Optional.empty();
    }

    /** The URL the session prints once everything is ready: the sidecar marked {@code front-door}, if any. */
    public Optional<String> frontDoor() {
        return snapshot().stream()
                .map(r -> r.spec)
                .filter(ExecPlan.Sidecar::frontDoor)
                .map(ExecPlan.Sidecar::ready)
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
        ProcessBuilder pb = new ProcessBuilder(spec.command())
                .directory(Path.of(spec.cwd()).toFile())
                .redirectErrorStream(true);
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
        Thread.ofVirtual().name("sidecar-" + spec.name()).start(() -> pump(r));
        return r;
    }

    private void pump(Running r) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(r.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                report.accept(r.spec.name() + PREFIX_SEPARATOR + line); // reported before it can count as ready
                r.sawLine(line);
            }
        } catch (IOException ignored) {
            // the pipe closes when the process goes; the exit below is the news
        }
        int exit;
        try {
            exit = r.process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        boolean stable = r.exited(exit);
        if (closing) return;
        if (r.spec.restart() != Sidecar.Restart.ON_EXIT) {
            report.accept(r.spec.name() + " exited with " + exit);
            return;
        }
        int failures = stable ? 1 : r.failures + 1;
        if (failures > MAX_RESTARTS) {
            report.accept(r.spec.name() + " exited with " + exit + " — gave up after " + MAX_RESTARTS + " restarts");
            return;
        }
        long backoff = Math.min(30_000, 500L << (failures - 1));
        report.accept(r.spec.name() + " exited with " + exit + " — restarting in " + backoff + " ms");
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
                report.accept(e.getMessage());
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
        private final CountDownLatch patternSeen = new CountDownLatch(1);
        private final @Nullable Pattern pattern;
        private volatile int exit = Integer.MIN_VALUE;
        private volatile boolean ready;

        Running(ExecPlan.Sidecar spec, Process process, int failures, Clock clock) {
            this.spec = spec;
            this.process = process;
            this.failures = failures;
            this.clock = clock;
            this.startedAt = clock.nanos();
            this.pattern = spec.readyPattern().isEmpty() ? null : Pattern.compile(spec.readyPattern());
        }

        void sawLine(String line) {
            if (pattern != null && pattern.matcher(line).find()) patternSeen.countDown();
        }

        /** Record the exit; true when this run had proven itself — probe passed or a stable stretch alive. */
        boolean exited(int code) {
            exit = code;
            return ready || clock.nanos() - startedAt >= TimeUnit.MILLISECONDS.toNanos(STABLE_RUN_MILLIS);
        }

        @Nullable
        String awaitReady() throws InterruptedException {
            String failure = probe();
            if (failure == null) ready = true;
            return failure;
        }

        @Nullable
        private String probe() throws InterruptedException {
            long deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(spec.readyTimeoutMillis());
            if (pattern != null) {
                while (clock.nanos() < deadline) {
                    if (patternSeen.await(100, TimeUnit.MILLISECONDS)) return null;
                    if (exit != Integer.MIN_VALUE) return exitedEarly();
                }
                return timedOut("output never matched /" + spec.readyPattern() + "/");
            }
            if (!spec.ready().isEmpty()) {
                // HTTP/1.1 only: a dev server that ignores the h2c upgrade would otherwise hang the
                // probe until its timeout, and none of them speak HTTP/2 on plain TCP anyway.
                try (HttpClient client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
                    List<HttpRequest> requests = new ArrayList<>();
                    for (URI candidate : readyCandidates(URI.create(spec.ready()))) {
                        requests.add(HttpRequest.newBuilder(candidate)
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build());
                    }
                    while (clock.nanos() < deadline) {
                        if (exit != Integer.MIN_VALUE) return exitedEarly();
                        for (HttpRequest request : requests) {
                            try {
                                int status = client.send(request, HttpResponse.BodyHandlers.discarding())
                                        .statusCode();
                                if (status >= 200 && status < 400) return null;
                            } catch (IOException | RuntimeException notYet) {
                                // not listening on this address yet
                            }
                        }
                        Thread.sleep(250);
                    }
                }
                return timedOut(spec.ready() + " never answered 2xx/3xx");
            }
            long aliveUntil = startedAt + TimeUnit.MILLISECONDS.toNanos(NO_PROBE_ALIVE_MILLIS);
            while (clock.nanos() < aliveUntil) {
                if (exit != Integer.MIN_VALUE) return exitedEarly();
                Thread.sleep(50);
            }
            return null;
        }

        private String exitedEarly() {
            return "sidecar `" + spec.name() + "` exited with " + exit + " before it was ready";
        }

        private String timedOut(String what) {
            return "sidecar `" + spec.name() + "` was not ready after " + spec.readyTimeoutMillis() / 1000 + " s: "
                    + what;
        }
    }

    /**
     * The addresses a {@code ready} URL is tried on. {@code localhost} becomes both loopbacks:
     * Node binds {@code ::1} alone on a dual-stack host while the JDK client resolves the name to
     * {@code 127.0.0.1}, and a probe that only tried one of them would call a serving Vite "not
     * ready" for the whole timeout.
     */
    static List<URI> readyCandidates(URI url) {
        String host = url.getHost();
        if (host == null || !host.equalsIgnoreCase("localhost")) return List.of(url);
        return List.of(withHost(url, "127.0.0.1"), withHost(url, "[::1]"));
    }

    private static URI withHost(URI url, String host) {
        String authority = url.getPort() < 0 ? host : host + ":" + url.getPort();
        try {
            return new URI(url.getScheme(), authority, url.getPath(), url.getQuery(), url.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
