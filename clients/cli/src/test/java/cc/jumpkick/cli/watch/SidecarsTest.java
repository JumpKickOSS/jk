// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.protocol.ExecPlan;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The session owns its sidecars: their output is prefixed, their children die with them, exits are news. */
@DisabledOnOs(OS.WINDOWS)
class SidecarsTest {

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

    @Test
    void output_is_prefixed_env_is_applied_and_the_pattern_probe_reads_it(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars = Sidecars.start(
                List.of(sh("web", "echo \"$GREETING ready\"; sleep 30", "ready", "never", dir)),
                lines::add,
                Clock.SYSTEM)) {
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
            try (Sidecars sidecars = Sidecars.start(List.of(spec), line -> {}, Clock.SYSTEM)) {
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
        try (Sidecars sidecars = Sidecars.start(List.of(spec), line -> {}, Clock.SYSTEM)) {
            assertThat(sidecars.awaitReady())
                    .hasValueSatisfying(msg -> assertThat(msg).contains("web").contains("never matched"));
        }
    }

    @Test
    void teardown_takes_the_sidecar_s_children_with_it(@TempDir Path dir) throws Exception {
        List<ProcessHandle> family;
        try (Sidecars sidecars =
                Sidecars.start(List.of(sh("web", "sleep 30; echo done", "", "never", dir)), line -> {}, Clock.SYSTEM)) {
            assertThat(sidecars.awaitReady()).isEmpty(); // no probe: alive for a second is ready
            family = sidecars.descendants();
            assertThat(family)
                    .as("sh -c with a trailing command keeps sleep as a child")
                    .isNotEmpty();
            family.addAll(sidecars.handles());
        }
        for (ProcessHandle h : family) {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (h.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(h.isAlive())
                    .as("pid " + h.pid() + " survived the teardown")
                    .isFalse();
        }
    }

    @Test
    void an_exit_before_readiness_fails_the_probe_and_is_reported(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars =
                Sidecars.start(List.of(sh("web", "exit 3", "never-printed", "never", dir)), lines::add, Clock.SYSTEM)) {
            assertThat(sidecars.awaitReady())
                    .hasValueSatisfying(msg -> assertThat(msg).contains("web").contains("exited with 3"));
        }
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!lines.contains("web exited with 3") && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(lines).contains("web exited with 3");
    }

    @Test
    void on_exit_restarts_with_backoff_and_gives_up(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (Sidecars sidecars =
                Sidecars.start(List.of(sh("flaky", "exit 1", "", "on-exit", dir)), lines::add, Clock.SYSTEM)) {
            long deadline = System.nanoTime() + 40_000_000_000L;
            while (lines.stream().noneMatch(l -> l.contains("gave up")) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(lines).anyMatch(l -> l.contains("restarting in 500 ms"));
            assertThat(lines).anyMatch(l -> l.contains("gave up after " + Sidecars.MAX_RESTARTS + " restarts"));
            assertThat(sidecars.anyAlive()).isFalse();
        }
    }
}
