// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.HttpLive;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpVitalsEstimateTest {

    private static HttpLive.Run run(long jid, double progress, long remainingMs, long lowMs, long highMs) {
        return new HttpLive.Run(
                jid,
                1,
                "build",
                "/ws",
                "g:a",
                null,
                null,
                0,
                0,
                progress,
                "j",
                remainingMs,
                60_000,
                0,
                0,
                List.of(),
                List.of(),
                lowMs,
                highMs);
    }

    @Test
    void a_job_in_flight_carries_the_estimate_and_its_likely_range() {
        var rows = McpVitals.estimateRows(List.of(run(7, 41.26, 32_000, 26_000, 41_000)));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("jid", 7L).containsEntry("progress", 41.3);
            assertThat(row).containsEntry("remainingMs", 32_000L);
            assertThat(row).containsEntry("etaLowMs", 26_000L).containsEntry("etaHighMs", 41_000L);
        });
    }

    @Test
    void what_is_not_known_yet_is_left_out() {
        var rows = McpVitals.estimateRows(List.of(run(8, Double.NaN, -1, -1, -1)));
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.keySet())
                .containsExactly("jid"));
    }
}
