// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * survives the app's restarts and is torn down — descendants first, so a package manager's child
 * server does not outlive it — when the session ends. A sidecar that exits on its own is reported
 * once, or restarted with backoff when its manifest says {@code restart = "on-exit"}, five failures
 * in a row being the limit. {@link #awaitReady} runs the probes: an HTTP URL polled for 2xx/3xx, a
 * regex over the output, or a second of staying alive; a probe that times out is a failure of the
 * session, not a warning.
 */
public final class Sidecars implements AutoCloseable {

    static final Duration TEARDOWN_GRACE = Duration.ofSeconds(5);
    static final long NO_PROBE_ALIVE_MILLIS = 1_000;
    static final int MAX_RESTARTS = 5;
    /** {@code " \u2502 "} — the light vertical bar between a sidecar's name and its line, as a code point so no encoding can bend it. */
    static final String PREFIX_SEPARATOR = " \u2502 ";

    private final List<Running> running = new ArrayList<>();
    private final Consumer<String> report;
    private final Clock clock;
    private volatile boolean closing;

    private Sidecars(Consumer<String> report, Clock clock) {
        this.report = report;
        this.clock = clock;
    }

    /** Start every sidecar; {@code report} receives each output line, prefixed, and lifecycle notes. */
    public static Sidecars start(List<ExecPlan.Sidecar> specs, Consumer<String> report, Clock clock)
            throws IOException {
        Sidecars sidecars = new Sidecars(report, clock);
        try {
            for (ExecPlan.Sidecar spec : specs) sidecars.running.add(sidecars.launch(spec, 0));
        } catch (IOException | RuntimeException e) {
            sidecars.close();
            throw e;
        }
        return sidecars;
    }

    public boolean isEmpty() {
        return running.isEmpty();
    }

    /**
     * Wait for every sidecar's probe. Returns the first failure — a timeout or an exit before
     * readiness — as a printable sentence, or empty when all are ready.
     */
    public Optional<String> awaitReady() throws InterruptedException {
        for (Running r : running) {
            String failure = r.awaitReady();
            if (failure != null) return Optional.of(failure);
        }
        return Optional.empty();
    }

    /** The URL the session prints once everything is ready: the sidecar marked {@code front-door}, if any. */
    public Optional<String> frontDoor() {
        return running.stream()
                .map(r -> r.spec)
                .filter(ExecPlan.Sidecar::frontDoor)
                .map(ExecPlan.Sidecar::ready)
                .filter(url -> !url.isEmpty())
                .findFirst();
    }

    @Override
    public void close() {
        closing = true;
        for (Running r : running) r.stop();
    }

    private Running launch(ExecPlan.Sidecar spec, int attempt) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spec.command())
                .directory(Path.of(spec.cwd()).toFile())
                .redirectErrorStream(true);
        pb.environment().putAll(spec.env());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException(
                    "sidecar `" + spec.name() + "`: cannot start "
                            + spec.command().getFirst() + " in " + spec.cwd() + ": " + e.getMessage(),
                    e);
        }
        Running r = new Running(spec, process, attempt, clock);
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
        r.exited(exit);
        if (closing) return;
        if ("on-exit".equals(r.spec.restart()) && r.attempt + 1 <= MAX_RESTARTS) {
            long backoff = Math.min(30_000, 500L * (1L << r.attempt));
            report.accept(r.spec.name() + " exited with " + exit + " — restarting in " + backoff + " ms");
            try {
                Thread.sleep(backoff);
                if (closing) return;
                Running next = launch(r.spec, r.attempt + 1);
                synchronized (running) {
                    running.set(running.indexOf(r), next);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                report.accept(e.getMessage());
            }
        } else if ("on-exit".equals(r.spec.restart())) {
            report.accept(r.spec.name() + " exited with " + exit + " — gave up after " + MAX_RESTARTS + " restarts");
        } else {
            report.accept(r.spec.name() + " exited with " + exit);
        }
    }

    /** One spawned sidecar: the process, its readiness state, and the teardown that takes its children with it. */
    private static final class Running {
        final ExecPlan.Sidecar spec;
        final Process process;
        final int attempt;
        final Clock clock;
        final long startedAt;
        private final CountDownLatch patternSeen = new CountDownLatch(1);
        private final @Nullable Pattern pattern;
        private volatile int exit = Integer.MIN_VALUE;

        Running(ExecPlan.Sidecar spec, Process process, int attempt, Clock clock) {
            this.spec = spec;
            this.process = process;
            this.attempt = attempt;
            this.clock = clock;
            this.startedAt = clock.nanos();
            this.pattern = spec.readyPattern().isEmpty() ? null : Pattern.compile(spec.readyPattern());
        }

        void sawLine(String line) {
            if (pattern != null && pattern.matcher(line).find()) patternSeen.countDown();
        }

        void exited(int code) {
            exit = code;
        }

        @Nullable
        String awaitReady() throws InterruptedException {
            long deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(spec.readyTimeoutMillis());
            if (pattern != null) {
                while (clock.nanos() < deadline) {
                    if (patternSeen.await(100, TimeUnit.MILLISECONDS)) return null;
                    if (exit != Integer.MIN_VALUE) return exitedEarly();
                }
                return timedOut("output never matched /" + spec.readyPattern() + "/");
            }
            if (!spec.ready().isEmpty()) {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(spec.ready()))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                while (clock.nanos() < deadline) {
                    if (exit != Integer.MIN_VALUE) return exitedEarly();
                    try {
                        int status = client.send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode();
                        if (status >= 200 && status < 400) return null;
                    } catch (IOException | RuntimeException notYet) {
                        // not listening yet
                    }
                    Thread.sleep(250);
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

        /** Descendants first: a `npm run dev` that execs nothing still has the real server as a child. */
        void stop() {
            List<ProcessHandle> family = new ArrayList<>(process.descendants().toList());
            family.add(process.toHandle());
            family.forEach(ProcessHandle::destroy);
            long deadline = clock.nanos() + TEARDOWN_GRACE.toNanos();
            for (ProcessHandle h : family) {
                while (h.isAlive() && clock.nanos() < deadline) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (h.isAlive()) h.destroyForcibly();
            }
        }
    }

    /** For tests: the live process handles of every sidecar, so a teardown can be checked for orphans. */
    List<ProcessHandle> handles() {
        List<ProcessHandle> out = new ArrayList<>();
        for (Running r : running) out.add(r.process.toHandle());
        return out;
    }

    /** For tests: everything the sidecars have spawned beneath them, at this instant. */
    List<ProcessHandle> descendants() {
        List<ProcessHandle> out = new ArrayList<>();
        for (Running r : running) out.addAll(r.process.descendants().toList());
        return out;
    }

    /** True while at least one sidecar process is alive. */
    public boolean anyAlive() {
        return running.stream().anyMatch(r -> r.process.isAlive());
    }
}
