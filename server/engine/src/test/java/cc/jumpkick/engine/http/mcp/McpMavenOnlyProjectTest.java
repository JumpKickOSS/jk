// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static cc.jumpkick.engine.http.JsonFields.number;
import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A checkout with a {@code pom.xml} and no {@code jk.toml} binds, and its {@code jk mvn} run reads back. */
class McpMavenOnlyProjectTest {

    @TempDir
    Path tmp;

    @Test
    void bind_then_results_and_diagnostics_serve_the_mvn_run() throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><parent><groupId>com.example</groupId></parent><artifactId>app</artifactId></project>\n");
        Files.createDirectories(tmp.resolve("target"));
        Files.writeString(
                tmp.resolve("target").resolve("jk-results.md"), "# jk results — FAIL\n\ntrigger: cli · tool: mvn\n");
        String dir = tmp.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
        String run = "{\"id\":\"m1\",\"kind\":\"mvn\",\"dir\":\"" + dir + "\",\"projectId\":\"p\","
                + "\"success\":false,\"exitCode\":1,\"millis\":10,\"coord\":\"com.example:app\","
                + "\"modules\":[{\"coord\":\"com.example:app\",\"dir\":\"" + dir + "\",\"success\":false}],"
                + "\"diagnostics\":[{\"severity\":\"error\",\"code\":\"compiler\",\"dir\":\"" + dir + "\","
                + "\"task\":\"compiler:compile\",\"message\":\"" + dir + "/src/A.java:3:5: cannot find symbol\"}]}";
        McpHandler mcp = handler(List.of(run));
        String session = requireNonNull(
                mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}", null)
                        .openedSessionId());

        Map<String, Object> bound = call(mcp, session, "jk_bind", "{\"dir\":\"" + dir + "\"}");
        assertThat(bound.get("coord")).isEqualTo("com.example:app");
        assertThat(object(bound, "lastRun").get("kind")).isEqualTo("mvn");

        Map<String, Object> results = call(mcp, session, "jk_results", "{}");
        assertThat(results.get("run")).isEqualTo("m1");
        assertThat(String.valueOf(results.get("markdown"))).contains("tool: mvn");

        Map<String, Object> diagnostics = call(mcp, session, "jk_diagnostics", "{}");
        assertThat(number(diagnostics, "count").intValue()).isEqualTo(1);
        Map<String, Object> row = objects(diagnostics, "diagnostics").get(0);
        assertThat(number(row, "line").intValue()).isEqualTo(3);
        assertThat(number(row, "col").intValue()).isEqualTo(5);
        assertThat(String.valueOf(row.get("file"))).endsWith("src/A.java");
    }

    private static McpHandler handler(List<String> history) {
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
        return new McpHandler(
                () -> new StatusSnapshot(
                        "0.13.7",
                        1L,
                        System.currentTimeMillis(),
                        0,
                        0,
                        1L << 20,
                        2L << 20,
                        256L << 20,
                        -1L,
                        0,
                        8,
                        16L << 30),
                jobs,
                dir -> Map.of(),
                () -> history,
                "0.13.7");
    }

    private static Map<String, Object> call(McpHandler mcp, String session, String name, String argsJson) {
        String body = mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + name
                                + "\",\"arguments\":" + argsJson + "}}",
                        session)
                .body();
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        return object(object(resp, "result"), "structuredContent");
    }
}
