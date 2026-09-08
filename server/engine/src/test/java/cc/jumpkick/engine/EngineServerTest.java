// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.MetricsRequest;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The engine's socket lifecycle: hello/ping/status, the election, shutdown, the loopback-TCP
 * transport, a stale socket file, and the metrics stream. A real-socket contract test — the
 * fixture lives in {@link EngineServerHarness}.
 */
@Tag("integration")
class EngineServerTest extends EngineServerHarness {

    @Test
    void serves_hello_ping_and_status_over_the_socket() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "9.9.9-test", null);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String ack = c.send(ProtoLifecycle.hello("9.9.9-test"));
            assertThat(EngineProtocol.typeOf(ack)).isEqualTo(EngineProtocol.HELLO_ACK);
            assertThat(Jsonl.str(ack, "version")).isEqualTo("9.9.9-test");
            assertThat(Jsonl.longValue(ack, "pid", -1))
                    .isEqualTo(ProcessHandle.current().pid());

            String pong = c.send(ProtoLifecycle.ping());
            assertThat(EngineProtocol.typeOf(pong)).isEqualTo(EngineProtocol.PONG);

            String status = c.send(ProtoLifecycle.statusRequest());
            assertThat(EngineProtocol.typeOf(status)).isEqualTo(EngineProtocol.STATUS_ACK);
            assertThat(Jsonl.intValue(status, "activeRequests", -1)).isEqualTo(1); // this very connection
            // Memory usage is best-effort, but heap numbers always exist on a live JVM.
            assertThat(Jsonl.longValue(status, "heapUsedBytes", -1)).isPositive();
            assertThat(Jsonl.longValue(status, "heapCommittedBytes", -1))
                    .isGreaterThanOrEqualTo(Jsonl.longValue(status, "heapUsedBytes", -1));
            long rss = Jsonl.longValue(status, "rssBytes", -99);
            assertThat(rss == -1 || rss > 0).isTrue(); // -1 only where the OS exposes no RSS
        }
        server.close();
    }

    @Test
    void status_ack_tracks_the_sidecar_aot_trainer_while_it_lives() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "9.9.9-test", null);
        // A stand-in trainer: any real child process the server can track and reap. The server
        // must invoke this factory only after winning its election and starting to serve.
        Process[] trainer = new Process[1];
        server.aotTrainerSpawner(() -> {
            try {
                trainer[0] = new ProcessBuilder("sleep", "30")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return trainer[0];
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        waitUntil(Duration.ofSeconds(5), () -> trainer[0] != null && trainer[0].isAlive());

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.send(ProtoLifecycle.hello("9.9.9-test"));
            String status = c.send(ProtoLifecycle.statusRequest());
            assertThat(Jsonl.longValue(status, "aotTrainingPid", -99)).isEqualTo(trainer[0].pid());

            // Trainer exits (self-terminates in real life) → the pid leaves the status snapshot.
            trainer[0].destroy();
            waitUntil(Duration.ofSeconds(5), () -> {
                try {
                    return Jsonl.longValue(c.send(ProtoLifecycle.statusRequest()), "aotTrainingPid", -99) == -1;
                } catch (IOException e) {
                    return false;
                }
            });
        } finally {
            if (trainer[0] != null) trainer[0].destroyForcibly();
            server.close();
        }
    }

    @Test
    void metrics_request_streams_aggregate_rows_and_a_terminal() throws Exception {
        Path stateDir = shortTempDir();
        Path metricsFile = stateDir.resolve("metrics.json");
        // Seed the store the same way a finished build/test does at request-finish.
        BuildMetrics.record(
                metricsFile,
                new BuildMetrics.Outcome(
                        "build",
                        "/proj/a",
                        "g:a",
                        true,
                        false,
                        1200,
                        List.of(new BuildMetrics.StepSample("/proj/a", "compile-java", "SUCCESS", 700))),
                1_700_000_000_000L);
        BuildMetrics.record(
                metricsFile,
                new BuildMetrics.Outcome("build", "/proj/b", "g:b", false, false, 400, List.of()),
                1_700_000_000_001L);

        EnginePaths.Paths p = paths(stateDir);
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        server.metricsFileForTests(metricsFile);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            // Unfiltered: global build row + both project rows + global/project step rows.
            c.sendLine(new MetricsRequest(null).encode());
            List<String> rows = new ArrayList<>();
            String line;
            while ((line = c.readLine()) != null && EngineProtocol.METRICS_ENTRY.equals(EngineProtocol.typeOf(line))) {
                rows.add(line);
            }
            assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.METRICS_DONE);
            assertThat(Jsonl.intValue(line, "count", -1)).isEqualTo(rows.size());
            assertThat(rows).hasSize(5);

            String global = rows.stream()
                    .filter(r -> BuildMetrics.SCOPE_GLOBAL.equals(Jsonl.str(r, "scope")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Jsonl.longValue(global, "okCount", -1)).isEqualTo(1);
            assertThat(Jsonl.longValue(global, "failCount", -1)).isEqualTo(1); // the /proj/b failure
            assertThat(Jsonl.longValue(global, "okAvgMillis", -1)).isEqualTo(1200);

            String failedProject = rows.stream()
                    .filter(r -> "/proj/b".equals(Jsonl.str(r, "dir")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Jsonl.longValue(failedProject, "okCount", -1)).isZero();
            assertThat(Jsonl.longValue(failedProject, "failTotalMillis", -1)).isEqualTo(400);

            // Filtered: /proj/a's rows plus the always-included global tiers; /proj/b drops out.
            c.sendLine(new MetricsRequest("/proj/a").encode());
            List<String> filtered = new ArrayList<>();
            while ((line = c.readLine()) != null && EngineProtocol.METRICS_ENTRY.equals(EngineProtocol.typeOf(line))) {
                filtered.add(line);
            }
            assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.METRICS_DONE);
            assertThat(filtered).hasSize(4);
            assertThat(filtered).noneMatch(r -> "/proj/b".equals(Jsonl.str(r, "dir")));
            String taskRow = filtered.stream()
                    .filter(r -> BuildMetrics.SCOPE_PROJECT_TASK.equals(Jsonl.str(r, "scope")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Jsonl.str(taskRow, "task")).isEqualTo("compile-java");
            assertThat(Jsonl.longValue(taskRow, "okTotalMillis", -1)).isEqualTo(700);
        }
        server.close();
    }

    @Test
    void a_second_instance_loses_the_election_and_returns_false() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer first = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        runInBackground(first);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        EngineServer second = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        assertThat(second.run()).isFalse(); // loses the tryLock race immediately, does not block

        first.close();
    }

    @Test
    void shutdown_message_stops_the_server() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String bye = c.send(ProtoLifecycle.shutdown());
            assertThat(EngineProtocol.typeOf(bye)).isEqualTo(EngineProtocol.BYE);
        }
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
        assertThat(Files.exists(EnginePaths.endpoint(p))).isFalse(); // endpoint retired at exit
        assertThat(Files.exists(p.lock())).isFalse();
    }

    /**
     * There's no real Windows box in this test run, but {@link EngineTransport#useLoopbackTcp}
     * only ever reads {@code os.name} — overriding that system property exercises the exact same
     * bind/auth/connect code path a real Windows host would take, without needing one.
     */
    @Test
    void loopback_tcp_transport_gates_every_connection_on_the_token() throws Exception {
        String previousOsName = System.getProperty("os.name");
        System.setProperty("os.name", "Windows 11");
        try {
            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            Path liveSocket = EnginePaths.activeSocket(p);
            int port = Integer.parseInt(Files.readString(liveSocket).trim());
            String token = Files.readString(EnginePaths.tokenFor(liveSocket)).trim();
            assertThat(token).isNotBlank();

            // Wrong token: the server closes the connection without ever replying.
            try (SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                BufferedReader r =
                        new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                w.write(ProtoLifecycle.auth("not-the-real-token"));
                w.write('\n');
                w.write(ProtoLifecycle.ping());
                w.write('\n');
                w.flush();
                // Typed refusal, then close: distinguishable from a crash.
                String refusal = r.readLine();
                assertThat(EngineProtocol.typeOf(refusal)).isEqualTo(EngineProtocol.ERROR);
                assertThat(Jsonl.str(refusal, "code")).isEqualTo(EngineProtocol.ERR_AUTH);
                assertThat(r.readLine()).isNull(); // and nothing was dispatched
            }

            // Correct token: the connection behaves exactly like the Unix-domain-socket transport.
            try (SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                BufferedReader r =
                        new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                w.write(ProtoLifecycle.auth(token));
                w.write('\n');
                w.write(ProtoLifecycle.ping());
                w.write('\n');
                w.flush();
                assertThat(EngineProtocol.typeOf(r.readLine())).isEqualTo(EngineProtocol.PONG);
            }

            server.close();
            serverThread.join(5_000);
            assertThat(Files.exists(EnginePaths.tokenFor(liveSocket))).isFalse(); // cleaned up on shutdown
        } finally {
            if (previousOsName != null) System.setProperty("os.name", previousOsName);
            else System.clearProperty("os.name");
        }
    }

    @Test
    void a_stale_socket_file_from_a_killed_engine_does_not_block_a_fresh_start() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        Files.createDirectories(p.dir());
        // Simulate a kill -9'd engine's leftovers: a dead generation socket file plus an
        // endpoint that still names it.
        EnginePaths.Paths gen1 = EnginePaths.generation(p, 1);
        Files.createFile(gen1.socket());
        EnginePaths.writeEndpoint(p, gen1.socket());

        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> {
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                return EngineProtocol.PONG.equals(EngineProtocol.typeOf(c.send(ProtoLifecycle.ping())));
            } catch (IOException e) {
                return false;
            }
        });
        server.close();
        serverThread.join(5_000);
    }
}
