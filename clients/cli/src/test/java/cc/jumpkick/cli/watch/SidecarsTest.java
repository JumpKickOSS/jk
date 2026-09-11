// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
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

    private static ExecPlan.Sidecar sh(String name, String script, String readyPattern, String restart, Path cwd) {
        return new ExecPlan.Sidecar(
                name,
                List.of("sh", "-c", script),
                cwd.toString(),
                Map.of("GREETING", "hi"),
                "",
                readyPattern,
                5_000L,
                false,
                restart);
    }

    private static Sidecars start(List<ExecPlan.Sidecar> specs, Consumer<String> report) throws Exception {
        return Sidecars.start(specs, report, Clock.SYSTEM, NO_WAIT);
    }

    private static void awaitLine(List<String> lines, Predicate<String> match) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (lines.stream().noneMatch(match) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(lines).anyMatch(match);
    }

    @Test
    void output_is_prefixed_env_is_applied_and_the_pattern_probe_reads_it(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars =
                start(List.of(sh("web", "echo \"$GREETING ready\"; sleep 30", "ready", "never", dir)), lines::add)) {
            assertThat(sidecars.awaitReady()).isEmpty();
            String expected = "web" + Sidecars.PREFIX_SEPARATOR + "hi ready";
            assertThat(lines).contains(expected);
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
                    "web", List.of("sh", "-c", "sleep 30"), dir.toString(), Map.of(), url, "", 5_000L, true, "never");
            try (Sidecars sidecars = start(List.of(spec), line -> {})) {
                assertThat(sidecars.awaitReady()).isEmpty();
                assertThat(sidecars.frontDoor()).hasValue(url);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_probe_that_never_answers_times_out_with_the_reason(@TempDir Path dir) throws Exception {
        ExecPlan.Sidecar spec = new ExecPlan.Sidecar(
                "web",
                List.of("sh", "-c", "sleep 30"),
                dir.toString(),
                Map.of(),
                "",
                "never printed",
                300L,
                false,
                "never");
        try (Sidecars sidecars = start(List.of(spec), line -> {})) {
            assertThat(sidecars.awaitReady())
                    .hasValueSatisfying(msg -> assertThat(msg).contains("web").contains("never matched"));
        }
    }

    @Test
    void localhost_is_probed_on_both_loopbacks_and_other_hosts_as_given() {
        assertThat(Sidecars.readyCandidates(URI.create("http://localhost:5173/app?x=1")))
                .extracting(URI::toString)
                .containsExactly("http://127.0.0.1:5173/app?x=1", "http://[::1]:5173/app?x=1");
        assertThat(Sidecars.readyCandidates(URI.create("http://0.0.0.0:8001")))
                .extracting(URI::toString)
                .containsExactly("http://0.0.0.0:8001");
    }

    @Test
    void teardown_takes_the_sidecar_s_children_with_it(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<ProcessHandle> family = new ArrayList<>();
        String pidPrefix = "web" + Sidecars.PREFIX_SEPARATOR;
        try (Sidecars sidecars =
                start(List.of(sh("web", "echo $$; sleep 30; echo done", "", "never", dir)), lines::add)) {
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
                start(List.of(sh("web", "cat; echo eof-seen", "eof-seen", "never", dir)), line -> {})) {
            assertThat(sidecars.awaitReady()).isEmpty();
        }
    }

    @Test
    void an_exit_before_readiness_fails_the_probe_and_is_reported(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars = start(List.of(sh("web", "exit 3", "never-printed", "never", dir)), lines::add)) {
            assertThat(sidecars.awaitReady())
                    .hasValueSatisfying(msg -> assertThat(msg).contains("web").contains("exited with 3"));
            awaitLine(lines, "web exited with 3"::equals);
        }
    }

    @Test
    void on_exit_restarts_with_backoff_and_gives_up(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars = start(List.of(sh("flaky", "exit 1", "", "on-exit", dir)), lines::add)) {
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
        try (Sidecars sidecars =
                Sidecars.start(List.of(sh("flaky", script, "", "on-exit", dir)), lines::add, clock, NO_WAIT)) {
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
                List.of(sh("flaky", "echo started; exit 1", "", "on-exit", dir)), lines::add, Clock.SYSTEM, heldBack);
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
