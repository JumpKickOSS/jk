// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code jk://runs/latest} resources answer for the project the connection is bound to. */
class McpResourcesLatestTest {

    @TempDir
    Path tmp;

    @Test
    void a_bound_connection_reads_its_own_projects_newest_run_from_every_latest_resource() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        McpContext ctx = context(List.of(row("20260101-000002", 9, b), row("20260101-000001", 4, a)));
        McpConnection bound = new McpConnection("c1", "test");
        bound.bind(a.toString());

        Map<String, Object> latest = json(McpResources.read(ctx, Map.of("uri", "jk://runs/latest"), bound));
        String results = text(McpResources.read(ctx, Map.of("uri", "jk://runs/latest/results"), bound));

        assertThat(latest).extractingByKey("record").satisfies(r -> assertThat(r.toString())
                .contains("buildNumber=4")
                .contains(a.toString()));
        assertThat(results).doesNotContain("#9");
    }

    @Test
    void an_unbound_reader_is_told_to_bind_first() {
        McpContext ctx = context(List.of(row("20260101-000001", 4, tmp)));
        for (String uri : List.of("jk://runs/latest", "jk://runs/latest/results", "jk://runs/latest/details")) {
            assertThat(text(McpResources.read(ctx, Map.of("uri", uri), new McpConnection("c2", "test"))))
                    .as(uri)
                    .contains("bind first");
        }
    }

    private static String row(String id, long n, Path dir) {
        return MiniJson.write(Map.of(
                "id",
                id,
                "kind",
                "build",
                "buildNumber",
                n,
                "dir",
                dir.toString(),
                "running",
                false,
                "success",
                true,
                "exitCode",
                0,
                "millis",
                10));
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> read) {
        List<Map<String, Object>> contents = (List<Map<String, Object>>) requireNonNull(read.get("contents"));
        return String.valueOf(contents.getFirst().get("text"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Map<String, Object> read) {
        return (Map<String, Object>) requireNonNull(MiniJson.parse(text(read)));
    }

    private static McpContext context(List<String> history) {
        return new McpContext(
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
                () -> history,
                "0",
                null,
                List::of,
                null,
                null);
    }
}
