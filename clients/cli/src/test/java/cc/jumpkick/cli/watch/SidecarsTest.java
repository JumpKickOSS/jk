// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.Sidecar;
import cc.jumpkick.testing.FakeClock;
import cc.jumpkick.wire.protocol.ExecPlan;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The session owns its sidecars: their output is prefixed, their children die with them, exits are news. */
@DisabledOnOs(OS.WINDOWS)
class SidecarsTest {

    /** Backoff that does not wait: the schedule is asserted from the report lines, not the clock. */
    private static final Sidecars.Sleeper NO_WAIT = millis -> {};

    private static ExecPlan.Sidecar sh(
            String name, String script, String readyPattern, Sidecar.Restart restart, Path cwd) {
        return new ExecPlan.Sidecar(
                name,
                List.of("sh", "-c", script),
                cwd.toString(),
                Map.of("GREETING", "hi"),
                new ExecPlan.Probe("", readyPattern, 5_000L),
                false,
                restart);
    }

    /** The terminal listener with colour off, so a line reads {@code web │ text} and nothing else. */
    private static Sidecars.Listener plain(Consumer<String> sink) {
        return SidecarOutput.terminal(sink, false);
    }

    private static Sidecars start(List<ExecPlan.Sidecar> specs, Consumer<String> report) throws Exception {
        return Sidecars.start(specs, plain(report), Clock.SYSTEM, NO_WAIT);
    }

    /** Every listener call, flattened, so a test can assert on order and on fields at once. */
    private static Sidecars.Listener recording(List<String> into) {
        return new Sidecars.Listener() {
            @Override
            public void started(String name, long pid) {
                into.add("started " + name + " pid>0=" + (pid > 0));
            }

            @Override
            public void output(String name, String stream, String line) {
                into.add(stream + " " + name + " " + line);
            }

            @Override
            public void ready(String name, String url, boolean frontDoor) {
                into.add("ready " + name);
            }

            @Override
            public void exited(String name, long pid, int exit, long restartInMs, boolean gaveUp) {
                into.add("exited " + name + " " + exit + " restartInMs=" + restartInMs + " gaveUp=" + gaveUp);
            }

            @Override
            public void failed(String message) {
                into.add("failed " + message);
            }
        };
    }

    private static void awaitLine(List<String> lines, Predicate<String> match) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (lines.stream().noneMatch(match) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(lines).anyMatch(match);
    }

    @Test
    void output_is_prefixed_env_is_applied_and_the_pattern_probe_reads_it(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars = start(
                List.of(sh("web", "echo \"$GREETING ready\"; sleep 30", "ready", Sidecar.Restart.NEVER, dir)),
                lines::add)) {
            assertThat(sidecars.awaitReady()).isEmpty();
            String expected = "web" + Sidecars.PREFIX_SEPARATOR + "hi ready";
            assertThat(lines).contains(expected);
        }
    }

    @Test
    void stdout_and_stderr_are_told_apart_and_both_feed_the_pattern_probe(@TempDir Path dir) throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ExecPlan.Sidecar spec =
                sh("web", "echo out-line; echo err-line >&2; sleep 30", "err-line", Sidecar.Restart.NEVER, dir);
        try (Sidecars sidecars = Sidecars.start(List.of(spec), recording(events), Clock.SYSTEM, NO_WAIT)) {
            assertThat(sidecars.awaitReady()).isEmpty();
            assertThat(events)
                    .contains("started web pid>0=true", "stdout web out-line", "stderr web err-line", "ready web");
            assertThat(events.getFirst()).startsWith("started web");
        }
    }

    @Test
    void carriage_return_progress_reaches_the_listener_collapsed_and_the_exit_carries_the_pid(@TempDir Path dir)
            throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ExecPlan.Sidecar spec =
                sh("web", "printf 'bundling 10%%\\rbundling 100%%\\n'; exit 0", "", Sidecar.Restart.NEVER, dir);
        try (Sidecars sidecars = Sidecars.start(List.of(spec), recording(events), Clock.SYSTEM, NO_WAIT)) {
            awaitLine(events, l -> l.startsWith("exited web"));
            assertThat(events)
                    .contains("stdout web bundling 100%", "exited web 0 restartInMs=-1 gaveUp=false")
                    .doesNotContain("stdout web bundling 10%");
            assertThat(events.indexOf("stdout web bundling 100%"))
                    .as("every line lands before the exit does")
                    .isLessThan(events.indexOf("exited web 0 restartInMs=-1 gaveUp=false"));
        }
    }

    @Test
    void the_url_probe_waits_for_a_2xx_and_names_the_front_door(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            ExecPlan.Sidecar spec = new ExecPlan.Sidecar(
                    "web",
                    List.of("sh", "-c", "sleep 30"),
                    dir.toString(),
                    Map.of(),
                    new ExecPlan.Probe(url, "", 5_000L),
                    true,
                    Sidecar.Restart.NEVER);
            try (Sidecars sidecars = start(List.of(spec), line -> {})) {
                assertThat(sidecars.awaitReady()).isEmpty();
                assertThat(sidecars.frontDoor()).hasValue(url);
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * A front door off loopback is probed through the proxy the request's shell names, like every
     * other request jk makes. The proxy stub answers for any host, so {@code web.example.test} — a
     * name that resolves nowhere — is ready only through it.
     */
    @Test
    void the_url_probe_off_loopback_goes_through_the_proxy_the_shell_names(@TempDir Path dir) throws Exception {
        List<String> hosts = new CopyOnWriteArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            hosts.add(exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        proxy.start();
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve("config.toml"), "");
        System.setProperty("jk.env.JK_HOME", home.toString());
        try {
            Session request = Session.defaults()
                    .withVariant(
                            null,
                            Map.of(
                                    "http_proxy",
                                    "http://127.0.0.1:" + proxy.getAddress().getPort()));
            ExecPlan.Sidecar spec = new ExecPlan.Sidecar(
                    "web",
                    List.of("sh", "-c", "sleep 30"),
                    dir.toString(),
                    Map.of(),
                    new ExecPlan.Probe("http://web.example.test:8080/health", "", 5_000L),
                    true,
                    Sidecar.Restart.NEVER);
            try (Sidecars sidecars = start(List.of(spec), line -> {})) {
                assertThat(SessionContext.where(request, sidecars::awaitReady)).isEmpty();
            }
            assertThat(hosts).containsExactly("web.example.test:8080");
        } finally {
            System.clearProperty("jk.env.JK_HOME");
            proxy.stop(0);
        }
    }

    @Test
    void a_probe_that_never_answers_times_out_with_the_reason(@TempDir Path dir) throws Exception {
        ExecPlan.Sidecar spec = new ExecPlan.Sidecar(
                "web",
                List.of("sh", "-c", "sleep 30"),
                dir.toString(),
                Map.of(),
                new ExecPlan.Probe("", "never printed", 300L),
                false,
                Sidecar.Restart.NEVER);
        try (Sidecars sidecars = start(List.of(spec), line -> {})) {
            assertThat(sidecars.awaitReady())
                    .hasValueSatisfying(msg -> assertThat(msg).contains("web").contains("never matched"));
        }
    }

    @Test
    void localhost_is_probed_on_both_loopbacks_and_other_hosts_as_given() {
        assertThat(ReadyProbe.readyCandidates(URI.create("http://localhost:5173/app?x=1")))
                .extracting(URI::toString)
                .containsExactly("http://127.0.0.1:5173/app?x=1", "http://[::1]:5173/app?x=1");
        assertThat(ReadyProbe.readyCandidates(URI.create("http://0.0.0.0:8001")))
                .extracting(URI::toString)
                .containsExactly("http://0.0.0.0:8001");
    }

    @Test
    void teardown_takes_the_sidecar_s_children_with_it(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<ProcessHandle> family = new ArrayList<>();
        String pidPrefix = "web" + Sidecars.PREFIX_SEPARATOR;
        try (Sidecars sidecars =
                start(List.of(sh("web", "echo $$; sleep 30; echo done", "", Sidecar.Restart.NEVER, dir)), lines::add)) {
            awaitLine(lines, l -> l.startsWith(pidPrefix));
            long pid = Long.parseLong(lines.stream()
                    .filter(l -> l.startsWith(pidPrefix))
                    .findFirst()
                    .orElseThrow()
                    .substring(pidPrefix.length())
                    .trim());
            ProcessHandle shell = ProcessHandle.of(pid).orElseThrow();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (shell.descendants().findAny().isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
            shell.descendants().forEach(family::add);
            assertThat(family)
                    .as("sh -c with a trailing command keeps sleep as a child")
                    .isNotEmpty();
            family.add(shell);
        }
        assertThat(family).allSatisfy(h -> assertThat(h.isAlive())
                .as("pid " + h.pid() + " survived the teardown")
                .isFalse());
    }

    @Test
    void stdin_is_closed_so_a_sidecar_that_reads_it_sees_eof(@TempDir Path dir) throws Exception {
        try (Sidecars sidecars =
                start(List.of(sh("web", "cat; echo eof-seen", "eof-seen", Sidecar.Restart.NEVER, dir)), line -> {})) {
            assertThat(sidecars.awaitReady()).isEmpty();
        }
    }

    @Test
    void an_exit_before_readiness_fails_the_probe_and_is_reported(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars =
                start(List.of(sh("web", "exit 3", "never-printed", Sidecar.Restart.NEVER, dir)), lines::add)) {
            assertThat(sidecars.awaitReady())
                    .hasValueSatisfying(msg -> assertThat(msg).contains("web").contains("exited with 3"));
            awaitLine(lines, "web exited with 3"::equals);
        }
    }

    @Test
    void on_exit_restarts_with_backoff_and_gives_up(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars = start(List.of(sh("flaky", "exit 1", "", Sidecar.Restart.ON_EXIT, dir)), lines::add)) {
            awaitLine(lines, l -> l.contains("gave up"));
            assertThat(restartDelays(lines)).containsExactly(500L, 1_000L, 2_000L, 4_000L, 8_000L);
            assertThat(lines).anyMatch(l -> l.contains("gave up after " + Sidecars.MAX_RESTARTS + " restarts"));
        }
    }

    @Test
    void a_run_that_stayed_up_starts_the_restart_count_over(@TempDir Path dir) throws Exception {
        FakeClock clock = new FakeClock();
        List<String> lines = new CopyOnWriteArrayList<>();
        Path first = dir.resolve("first");
        Path go = dir.resolve("go");
        // The first run exits at once; the second waits to be released; every later one exits at once.
        String script = "if [ -e '" + first + "' ]; then echo waiting; while [ ! -e '" + go
                + "' ]; do sleep 0.02; done; exit 1; fi; touch '" + first + "'; exit 1";
        try (Sidecars sidecars = Sidecars.start(
                List.of(sh("flaky", script, "", Sidecar.Restart.ON_EXIT, dir)), plain(lines::add), clock, NO_WAIT)) {
            awaitLine(lines, l -> l.endsWith("waiting"));
            clock.advance(Duration.ofMillis(Sidecars.STABLE_RUN_MILLIS + 1_000));
            Files.createFile(go);
            awaitLine(lines, l -> l.contains("gave up"));
        }
        assertThat(restartDelays(lines))
                .as("the second run's exit is a first failure again")
                .containsExactly(500L, 500L, 1_000L, 2_000L, 4_000L, 8_000L);
    }

    @Test
    void close_suppresses_a_restart_that_is_waiting_out_its_backoff(@TempDir Path dir) throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Sidecars.Sleeper heldBack = millis -> {
            sleeping.countDown();
            release.await();
        };
        List<String> lines = new CopyOnWriteArrayList<>();
        Sidecars sidecars = Sidecars.start(
                List.of(sh("flaky", "echo started; exit 1", "", Sidecar.Restart.ON_EXIT, dir)),
                plain(lines::add),
                Clock.SYSTEM,
                heldBack);
        assertThat(sleeping.await(10, TimeUnit.SECONDS)).isTrue();
        sidecars.close();
        release.countDown();
        Thread.sleep(300);
        assertThat(lines.stream().filter(l -> l.endsWith("started")).count())
                .as("no second launch after close")
                .isEqualTo(1);
    }

    private static List<Long> restartDelays(List<String> lines) {
        List<Long> delays = new ArrayList<>();
        for (String line : lines) {
            int at = line.indexOf("restarting in ");
            if (at < 0) continue;
            String rest = line.substring(at + "restarting in ".length());
            delays.add(Long.parseLong(rest.substring(0, rest.indexOf(' '))));
        }
        return delays;
    }
}
