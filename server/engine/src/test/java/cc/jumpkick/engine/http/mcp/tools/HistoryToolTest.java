// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@code history} pages summaries and reads only the records a page needs. */
class HistoryToolTest {

    private static final int ROWS = 200;

    private final AtomicInteger reads = new AtomicInteger();

    /** 200 records of about 64 KiB each, counting every one read. */
    private final List<String> history = new AbstractList<>() {
        @Override
        public String get(int i) {
            reads.incrementAndGet();
            return MiniJson.write(Map.of(
                    "id",
                    "r" + i,
                    "kind",
                    "build",
                    "buildNumber",
                    ROWS - i,
                    "dir",
                    "/ws",
                    "running",
                    false,
                    "success",
                    false,
                    "stdoutTail",
                    "x".repeat(64 * 1024)));
        }

        @Override
        public int size() {
            return ROWS;
        }
    };

    @Test
    void a_page_reads_no_more_records_than_it_skips_and_shows() {
        Map<String, Object> fields = structured(call(Map.of("dir", "/ws", "limit", 10, "next", 20)));

        assertThat(reads.get()).isLessThanOrEqualTo(30);
        assertThat((List<?>) fields.get("records")).hasSize(10);
        assertThat(fields).containsEntry("truncated", true);
        assertThat(String.valueOf(fields.get("next"))).isEqualTo("30");
    }

    @Test
    void the_raw_view_is_not_served_so_a_full_page_stays_small() {
        Map<String, Object> result = call(Map.of("dir", "/ws", "limit", ROWS, "view", "full"));

        assertThat(MiniJson.write(result).length())
                .as("200 summaries, never 200 raw records")
                .isLessThan(256 * 1024);
    }

    private Map<String, Object> call(Map<String, Object> args) {
        return new HistoryTool().call(new McpCall(context(), args, null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(Map<String, Object> result) {
        return (Map<String, Object>) requireNonNull(result.get("structuredContent"));
    }

    private McpContext context() {
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
