// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.testing.FakeClock;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An app {@code run} started is ready when the port it says it listens on answers; one that exits
 * first is a failure quoting its log; and it is stopped with the session that started it.
 */
class McpAppReadyTest {

    /** A server that logs its port the way Netty does, or, given {@code fail}, a startup that fails. */
    private static final String SERVER = """
            import java.net.*;
            public class Serve {
                public static void main(String[] a) throws Exception {
                    if (a.length > 0 && a[0].equals("fail")) {
                        System.err.println("APPLICATION FAILED TO START: port taken");
                        System.exit(3);
                    }
                    try (ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                        System.out.println("Netty started on port " + s.getLocalPort());
                        System.out.flush();
                        while (true) s.accept().close();
                    }
                }
            }
            """;

    @TempDir
    Path dir;

    private final McpApps apps = new McpApps();

    @AfterEach
    void stopEverything() {
        apps.stopAll();
    }

    @Test
    void the_port_a_server_logs_is_the_one_waited_for() {
        assertThat(McpAppReady.listensOn("o.s.b.w.e.tomcat.TomcatWebServer : Tomcat started on port 8080 (http)"))
                .isEqualTo(8080);
        assertThat(McpAppReady.listensOn("Tomcat started on port(s): 8081 (http) with context path ''"))
                .isEqualTo(8081);
        assertThat(McpAppReady.listensOn("Listening on: http://0.0.0.0:8082")).isEqualTo(8082);
        assertThat(McpAppReady.listensOn("Server Running: http://localhost:8083/"))
                .isEqualTo(8083);
        assertThat(McpAppReady.listensOn("HikariPool-1 - Added connection jdbc:postgresql://localhost:5432/shop"))
                .as("an address the app connects to is not where it listens")
                .isZero();
        assertThat(McpAppReady.listensOn("connected to redis at localhost:6379"))
                .isZero();
        assertThat(McpAppReady.portOf("http://localhost:9000/health")).isEqualTo(9000);
        assertThat(McpAppReady.portOf("https://localhost/health")).isEqualTo(443);
        assertThat(McpAppReady.portOf("")).isZero();
    }

    @Test
    void a_server_is_ready_once_the_port_it_logged_answers() throws Exception {
        McpApp app = start("ready", "claude-code 91be", "sid-1");

        McpAppReady.Result r = new McpAppReady(app, ExecPlan.Probe.NONE, 0, 30_000, Clock.SYSTEM).await();
        String verdict = McpAppRuns.verdict(app, r, 30_000);

        assertThat(r.outcome()).isEqualTo(McpAppReady.Outcome.READY);
        assertThat(r.port()).isPositive();
        assertThat(verdict).startsWith("READY app demo pid " + app.process().pid() + " · http://localhost:" + r.port());
        assertThat(app.alive()).isTrue();
        assertThat(apps.rows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("startedBy", "claude-code 91be");
            assertThat(row).containsEntry("url", "http://localhost:" + r.port());
            assertThat(row).containsEntry("name", "demo");
        });
    }

    @Test
    void a_startup_that_exits_first_is_a_failure_quoting_its_log() throws Exception {
        McpApp app = start("fail", "codex 1a2b", "sid-2");

        McpAppReady.Result r = new McpAppReady(app, ExecPlan.Probe.NONE, 0, 30_000, Clock.SYSTEM).await();
        String verdict = McpAppRuns.verdict(app, r, 30_000);

        assertThat(r.outcome()).isEqualTo(McpAppReady.Outcome.EXITED);
        assertThat(verdict)
                .startsWith("FAIL app demo pid ")
                .contains("exited with 3 before the app answered")
                .contains("APPLICATION FAILED TO START: port taken")
                .endsWith("log: " + McpAppRuns.LOG + "\n");
        assertThat(apps.rows())
                .as("an app that exited is not listed as running")
                .isEmpty();
    }

    @Test
    void a_declared_port_that_never_answers_times_out_and_the_app_is_stopped() throws Exception {
        McpApp app = start("ready", "claude-code 91be", "sid-3");
        int nobody;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            nobody = s.getLocalPort();
        }

        // Each look moves the clock a second: the wait ends at its second look, not after 1.5 s.
        FakeClock clock = new FakeClock();
        Clock stepping = new Clock() {
            @Override
            public long millis() {
                return clock.advance(Duration.ofSeconds(1)).millis();
            }

            @Override
            public long nanos() {
                return clock.nanos();
            }
        };
        McpAppReady.Result r = new McpAppReady(app, ExecPlan.Probe.NONE, nobody, 1_500, stepping).await();
        String verdict = McpAppRuns.verdict(app, r, 1_500);

        assertThat(r.outcome()).isEqualTo(McpAppReady.Outcome.TIMED_OUT);
        assertThat(verdict).contains("port " + nobody + " did not answer in 1.5s; stopped it");
        assertThat(app.alive()).isFalse();
        assertThat(app.state()).isEqualTo("stopped");
    }

    @Test
    void an_ended_session_stops_the_apps_it_started_and_no_other() throws Exception {
        McpApp mine = start("ready", "claude-code 91be", "sid-4");
        McpApp theirs = startIn(Files.createDirectories(dir.resolve("other")), "codex 1a2b", "sid-5");

        assertThat(apps.stopOwnedBy("sid-4")).isEqualTo(1);

        assertThat(mine.alive()).isFalse();
        assertThat(theirs.alive()).isTrue();
        assertThat(apps.rows()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("startedBy", "codex 1a2b"));
    }

    private McpApp start(String mode, String startedBy, String owner) throws IOException {
        Path project = Files.createDirectories(dir.resolve("demo"));
        return launch(project, mode, startedBy, owner);
    }

    private McpApp startIn(Path project, String startedBy, String owner) throws IOException {
        return launch(project, "ready", startedBy, owner);
    }

    private McpApp launch(Path project, String mode, String startedBy, String owner) throws IOException {
        Path source = project.resolve("Serve.java");
        Files.writeString(source, SERVER);
        Path log = Files.createDirectories(project.resolve("target")).resolve("jk-app.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(List.of(java, source.toString(), mode))
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        McpApp app = new McpApp(project.toString(), "demo", p, log, System.currentTimeMillis(), owner, startedBy);
        apps.add(app);
        return app;
    }
}
