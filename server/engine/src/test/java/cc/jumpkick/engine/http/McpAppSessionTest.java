// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.http.mcp.McpApp;
import cc.jumpkick.engine.jobs.JobSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An app a session started is stopped when that session ends ({@code DELETE /mcp}), and only then. */
class McpAppSessionTest {

    private static final String IDLE = """
            public class Idle {
                public static void main(String[] a) throws Exception {
                    Thread.sleep(600_000);
                }
            }
            """;

    private final McpHandler mcp = new McpHandler(
            () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
            new EngineHttpJobs() {
                @Override
                public long trigger(JobSpec spec) {
                    return 1L;
                }

                @Override
                public boolean cancel(long requestId) {
                    return false;
                }

                @Override
                public int cancelDir(String dir) {
                    return 0;
                }
            },
            dir -> Map.of(),
            List::of,
            "0");

    @Test
    void closing_a_session_stops_the_app_it_started(@TempDir Path dir) throws IOException {
        String session = initialize("claude-code");
        String other = initialize("codex");
        McpApp app = idle(dir.resolve("a"), session, "claude-code " + session.substring(0, 4));
        McpApp kept = idle(dir.resolve("b"), other, "codex " + other.substring(0, 4));
        try {
            assertThat(mcp.apps()).hasSize(2);

            assertThat(mcp.closeConnection(session)).isTrue();

            assertThat(app.alive()).isFalse();
            assertThat(kept.alive()).isTrue();
            assertThat(mcp.apps()).singleElement().satisfies(row -> assertThat(row)
                    .containsEntry("startedBy", "codex " + other.substring(0, 4)));
        } finally {
            mcp.stopApps();
        }
        assertThat(kept.alive()).as("the engine's stop takes every app with it").isFalse();
    }

    private String initialize(String client) {
        McpHandler.Reply reply = mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\""
                        + client + "\",\"version\":\"1\"}}}",
                null);
        return requireNonNull(reply.openedSessionId());
    }

    private McpApp idle(Path project, String owner, String startedBy) throws IOException {
        Files.createDirectories(project);
        Path source = Files.writeString(project.resolve("Idle.java"), IDLE);
        Path log = project.resolve("app.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, source.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        McpApp app = new McpApp(project.toString(), project.getFileName().toString(), p, log, 0L, owner, startedBy);
        mcp.appRegistry().add(app);
        return app;
    }
}
