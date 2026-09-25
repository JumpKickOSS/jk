// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsAgent;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.command.Invocation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI {@code --agent} print and the MCP results tool are one rendering of one run.
 */
class AgentReportParityTest {

    @TempDir
    Path tmp;

    @Test
    void agent_cli_and_mcp_print_the_same_text() throws Exception {
        BuildRecord.Diag err = new BuildRecord.Diag(
                "error",
                tmp.toString(),
                "compile-java",
                "javac",
                "';' expected",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "src/A.java",
                3,
                10,
                3,
                List.of("class A {"),
                0,
                "");
        BuildRecord record = new BuildRecord(
                "run-1",
                1,
                BuildRecord.SCHEMA,
                "build",
                tmp.toString(),
                "com.example:app",
                "p",
                1,
                2,
                700,
                false,
                false,
                1,
                "0.14.0",
                null,
                List.of(),
                List.of(),
                List.of(err),
                "cli",
                null,
                null,
                null,
                false,
                null,
                9,
                null,
                List.of());
        String expected = JkResultsAgent.render(record);
        Path target = Files.createDirectories(tmp.resolve(BuildLayout.TARGET));
        Files.writeString(target.resolve(ProjectBuilds.AGENT), expected, StandardCharsets.UTF_8);
        Path run = Files.createDirectories(tmp.resolve("run"));
        Files.writeString(run.resolve(ProjectBuilds.AGENT), expected, StandardCharsets.UTF_8);
        Files.writeString(run.resolve("details.jsonl"), "{}\n", StandardCharsets.UTF_8);
        // Json is package-private; the history row is the record the renderer just printed.
        String raw = MiniJson.write(Map.of(
                "id",
                "run-1",
                "kind",
                "build",
                "dir",
                tmp.toString(),
                "coord",
                "com.example:app",
                "success",
                false,
                "exitCode",
                1,
                "millis",
                700,
                "diagnostics",
                List.of(Map.of(
                        "severity",
                        "error",
                        "dir",
                        tmp.toString(),
                        "task",
                        "compile-java",
                        "code",
                        "javac",
                        "message",
                        "';' expected",
                        "file",
                        "src/A.java",
                        "line",
                        3,
                        "col",
                        10,
                        "snippetStart",
                        3,
                        "snippet",
                        List.of("class A {")))));
        // The file is what both sides print when it is on disk. Re-rendering the row must match it.
        assertThat(JkResultsAgent.render(Objects.requireNonNull(JkResultsAgent.recordOf(map(raw)))))
                .isEqualTo(expected);

        McpHandler mcp = handler(List.of(raw), run);
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run\",\"arguments\":{\"run\":\"latest\",\"dir\":\""
                + tmp.toString().replace("\\", "\\\\")
                + "\"}}}");
        assertThat(text(body)).isEqualTo(expected);

        String cli = Capture.stdout(() -> {
            try {
                int code = new ResultsCommand()
                        .run(Invocation.builder()
                                .flag("agent", true)
                                .addValue("dir", tmp.toString())
                                .build());
                assertThat(code).isZero();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(cli).isEqualTo(expected);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String raw) {
        return Objects.requireNonNull((Map<String, Object>) MiniJson.parse(raw));
    }

    @SuppressWarnings("unchecked")
    private static String text(String body) {
        Map<String, Object> resp = Objects.requireNonNull((Map<String, Object>) MiniJson.parse(body));
        Map<String, Object> result = Objects.requireNonNull((Map<String, Object>) resp.get("result"));
        List<Map<String, Object>> content = Objects.requireNonNull((List<Map<String, Object>>) result.get("content"));
        return String.valueOf(content.getFirst().get("text"));
    }

    private static McpHandler handler(List<String> history, Path run) {
        EngineHttpJobs jobs = new EngineHttpJobs() {
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
        };
        McpHandler mcp = new McpHandler(
                () -> new StatusSnapshot("0.14.0", 1L, 0, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30),
                jobs,
                dir -> Map.of(),
                () -> history,
                "0.14.0");
        mcp.detailsFile(id -> Optional.of(run.resolve("details.jsonl")));
        return mcp;
    }
}
