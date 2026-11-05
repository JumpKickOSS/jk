// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.MetricsRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The engine's metrics stream over a real socket: the aggregate rows of the server's own metrics
 * file, then a terminal. The rows are seeded as recent runs: the engine's idle housekeeping prunes
 * that file by age at the user's limits whenever it runs (at startup when its maintenance is due,
 * and at every idle boundary), and a run dated years back is a row it evicts.
 */
@Tag("integration")
class EngineServerMetricsTest extends EngineServerHarness {

    @Test
    void metrics_request_streams_aggregate_rows_and_a_terminal() throws Exception {
        Path stateDir = shortTempDir();
        Path metricsFile = stateDir.resolve("metrics.json");
        long now = System.currentTimeMillis();
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
                now - 2_000L);
        BuildMetrics.record(
                metricsFile,
                new BuildMetrics.Outcome("build", "/proj/b", "g:b", false, false, 400, List.of()),
                now - 1_000L);

        EnginePaths.Paths p = paths(stateDir);
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        server.metricsFileForTests(metricsFile);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            // Unfiltered: global build row + both project rows + global/project step rows.
            Streamed all = metrics(c, null);
            assertThat(all.rows()).as("rows: %s", all.rows()).hasSize(5).hasSize(all.count());

            String global = all.rows().stream()
                    .filter(r -> BuildMetrics.SCOPE_GLOBAL.equals(Jsonl.str(r, "scope")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Jsonl.longValue(global, "okCount", -1)).isEqualTo(1);
            assertThat(Jsonl.longValue(global, "failCount", -1)).isEqualTo(1); // the /proj/b failure
            assertThat(Jsonl.longValue(global, "okAvgMillis", -1)).isEqualTo(1200);

            String failedProject = all.rows().stream()
                    .filter(r -> "/proj/b".equals(Jsonl.str(r, "dir")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Jsonl.longValue(failedProject, "okCount", -1)).isZero();
            assertThat(Jsonl.longValue(failedProject, "failTotalMillis", -1)).isEqualTo(400);

            // Filtered: /proj/a's rows plus the always-included global tiers; /proj/b drops out.
            Streamed filtered = metrics(c, "/proj/a");
            assertThat(filtered.rows()).as("rows: %s", filtered.rows()).hasSize(4);
            assertThat(filtered.rows()).noneMatch(r -> "/proj/b".equals(Jsonl.str(r, "dir")));
            String taskRow = filtered.rows().stream()
                    .filter(r -> BuildMetrics.SCOPE_PROJECT_TASK.equals(Jsonl.str(r, "scope")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Jsonl.str(taskRow, "task")).isEqualTo("compile-java");
            assertThat(Jsonl.longValue(taskRow, "okTotalMillis", -1)).isEqualTo(700);
        }
        server.close();
    }

    /** The entry rows of one metrics request and the count its terminal reports. */
    private record Streamed(List<String> rows, int count) {}

    private static Streamed metrics(Client c, @Nullable String dir) throws Exception {
        c.sendLine(new MetricsRequest(dir).encode());
        List<String> rows = new ArrayList<>();
        String line;
        while ((line = c.readLine()) != null && EngineProtocol.METRICS_ENTRY.equals(EngineProtocol.typeOf(line))) {
            rows.add(line);
        }
        String terminal = Objects.requireNonNull(line, () -> "the stream ended without a terminal after rows: " + rows);
        assertThat(EngineProtocol.typeOf(terminal))
                .as("terminal after rows: %s", rows)
                .isEqualTo(EngineProtocol.METRICS_DONE);
        return new Streamed(rows, Jsonl.intValue(terminal, "count", -1));
    }
}
