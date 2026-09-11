// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.number;
import static cc.jumpkick.engine.http.JsonFields.object;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** MCP disk reads ride the shared memoized {@link CacheSnapshot} — no per-call store walks. */
class McpDiskTest {

    private final AtomicInteger walks = new AtomicInteger();

    private final CacheSnapshot snap = new CacheSnapshot(
            /* casCount */ 10,
            /* casBytes */ 1_000,
            /* actionsCount */ 5,
            /* actionsBytes */ 200,
            /* cacheCasCount */ 2,
            /* cacheCasBytes */ 50,
            /* workerJarsCount */ 1,
            /* workerJarsBytes */ 100,
            /* formatStampsCount */ 4,
            /* formatStampsBytes */ 20,
            /* incrementalCount */ 6,
            /* incrementalBytes */ 4_096,
            /* incrementalMaxBytes */ 1L << 30,
            /* actionMaxBytes */ 4L << 30,
            /* lastPrunedMillis */ 0,
            /* mavenLocalCount */ 0,
            /* mavenLocalBytes */ 0,
            /* derivedCount */ 0,
            /* derivedBytes */ 0,
            /* totalCount */ 22,
            /* totalBytes */ 4_366);

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return 1L;
        }

        @Override
        public boolean cancel(long requestId) {
            return false;
        }

        public int cancelDir(String dir) {
            return 0;
        }
    };

    private McpHandler handler() {
        McpHandler mcp = new McpHandler(
                () -> new StatusSnapshot(
                        "0.12.0",
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
                List::of,
                "0.12.0");
        mcp.cacheSnapshot(CacheSnapshot.memoizing(
                () -> {
                    walks.incrementAndGet();
                    return snap;
                },
                60_000));
        return mcp;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(String body) {
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        Map<String, Object> result = object(resp, "result");
        return object(result, "structuredContent");
    }

    @Test
    void disk_bytes_agree_with_the_api_cache_surfaces_and_repeated_calls_hit_the_memo() {
        McpHandler mcp = handler();
        Map<String, Object> disk = structured(mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"method\":\"tools/call\",\"params\":{\"name\":\"jk_disk\",\"arguments\":{}}}"));
        // Same accounting as GET /api/cache: cache tier vs artifact store, exclusive bytes.
        assertThat(number(disk, "cacheBytes").longValue()).isEqualTo(snap.actionCacheBytes());
        assertThat(number(disk, "storeBytes").longValue()).isEqualTo(snap.artifactStorageBytes());
        assertThat(walks).hasValue(1);
        mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_disk\",\"arguments\":{}}}");
        Map<String, Object> doctor = structured(mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":3,"
                + "\"method\":\"tools/call\",\"params\":{\"name\":\"jk_doctor\",\"arguments\":{}}}"));
        Map<String, Object> doctorDisk = object(doctor, "disk");
        assertThat(number(doctorDisk, "storeBytes").longValue()).isEqualTo(snap.artifactStorageBytes());
        mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\"," + "\"params\":{\"uri\":\"jk://disk\"}}");
        assertThat(walks).hasValue(1); // one walk serves jk_disk, jk_doctor, and jk://disk
    }
}
