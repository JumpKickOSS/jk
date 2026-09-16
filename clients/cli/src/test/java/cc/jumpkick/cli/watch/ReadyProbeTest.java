// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.DeadEndpoint;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.protocol.ExecPlan;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The probe the app and every sidecar share: a URL, a pattern over the lines its owner feeds it,
 * or nothing; an exit first or a timeout is a sentence naming the subject in the session's voice.
 */
@DisabledOnOs(OS.WINDOWS)
class ReadyProbeTest {

    private static Process sleeping() throws Exception {
        return new ProcessBuilder("sh", "-c", "sleep 30").start();
    }

    @Test
    void the_app_with_no_probe_is_ready_the_moment_it_is_forked() throws Exception {
        Process p = sleeping();
        try {
            ReadyProbe probe = new ReadyProbe("app", new ExecPlan.Probe("", "", 5_000), 0, p, Clock.SYSTEM);
            assertThat(probe.declared()).isFalse();
            assertThat(probe.await()).isNull();
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void a_pattern_probe_passes_on_the_line_its_owner_feeds_it() throws Exception {
        Process p = sleeping();
        try {
            ReadyProbe probe =
                    new ReadyProbe("app", new ExecPlan.Probe("", "Started \\w+ in", 5_000), 0, p, Clock.SYSTEM);
            probe.sawLine("Starting Api");
            Thread.ofVirtual().start(() -> probe.sawLine("Started Api in 0.4 s"));
            assertThat(probe.await()).isNull();
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void a_url_probe_waits_for_a_2xx_and_says_so_in_the_subject_s_name_when_it_never_comes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        Process p = sleeping();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(new ReadyProbe("app", new ExecPlan.Probe(base + "/health", "", 5_000), 0, p, Clock.SYSTEM)
                            .await())
                    .isNull();
            assertThat(new ReadyProbe("app", new ExecPlan.Probe(base + "/missing", "", 1_000), 0, p, Clock.SYSTEM)
                            .await())
                    .isEqualTo("app was not ready after 1 s: " + base + "/missing never answered 2xx/3xx");
        } finally {
            p.destroyForcibly();
            server.stop(0);
        }
    }

    @Test
    void a_process_that_exits_before_its_probe_passes_fails_with_its_exit_code() throws Exception {
        Process p = new ProcessBuilder("sh", "-c", "exit 3").start();
        p.waitFor();
        assertThat(new ReadyProbe("app", new ExecPlan.Probe("", "never", 5_000), 0, p, Clock.SYSTEM).await())
                .isEqualTo("app exited with 3 before it was ready");
        try (DeadEndpoint never = DeadEndpoint.open()) {
            assertThat(new ReadyProbe(
                                    "sidecar `web`",
                                    new ExecPlan.Probe(never.uri().toString(), "", 5_000),
                                    0,
                                    p,
                                    Clock.SYSTEM)
                            .await())
                    .isEqualTo("sidecar `web` exited with 3 before it was ready");
        }
    }

    @Test
    void a_sidecar_with_no_probe_is_ready_after_a_second_alive() throws Exception {
        Process p = sleeping();
        try {
            long before = System.nanoTime();
            assertThat(new ReadyProbe(
                                    "sidecar `docs`",
                                    new ExecPlan.Probe("", "", 5_000),
                                    ReadyProbe.NO_PROBE_ALIVE_MILLIS,
                                    p,
                                    Clock.SYSTEM)
                            .await())
                    .isNull();
            assertThat(System.nanoTime() - before).isGreaterThanOrEqualTo(900_000_000L);
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void localhost_is_probed_on_both_loopbacks_and_other_hosts_as_given() {
        assertThat(ReadyProbe.readyCandidates(URI.create("http://localhost:8080/health")))
                .extracting(Object::toString)
                .containsExactly("http://127.0.0.1:8080/health", "http://[::1]:8080/health");
        assertThat(ReadyProbe.readyCandidates(URI.create("http://0.0.0.0:8001")))
                .extracting(Object::toString)
                .isEqualTo(List.of("http://0.0.0.0:8001"));
    }
}
