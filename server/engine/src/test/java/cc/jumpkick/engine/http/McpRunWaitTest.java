// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.number;
import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** {@code jk_run wait=true} journal attribution — no HTTP bind. */
class McpRunWaitTest {

    private static final long JID = 45L;

    private static final String FINISHED_OK =
            "{\"id\":\"r9\",\"buildNumber\":9,\"kind\":\"build\",\"dir\":\"/ws\",\"projectId\":\"p\","
                    + "\"success\":true,\"exitCode\":0,\"millis\":10,\"coord\":\"g:a\",\"requestId\":45,"
                    + "\"startedAt\":1700000000000,\"modules\":[],\"diagnostics\":[]}";

    private final AtomicInteger historyScans = new AtomicInteger();

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return JID;
        }

        @Override
        public boolean cancel(long requestId) {
            return false;
        }

        public int cancelDir(String dir) {
            return 0;
        }
    };

    /**
     * Live-run feed that reports the triggered jid live exactly once, then gone. waitUntilGone's
     * not-seen grace is 250ms of real sleeping — with an always-empty feed every wait in this
     * class paid it; seen-then-gone exits on the next 50ms poll.
     */
    private static Supplier<List<HttpLive.Run>> liveOnce() {
        AtomicInteger polls = new AtomicInteger();
        long now = System.currentTimeMillis();
        HttpLive.Run run = new HttpLive.Run(
                JID, 9, "build", "/ws", "g:a", null, null, now, now, 0.5, "r9", -1, -1, 0, 0, List.of(), List.of());
        return () -> polls.getAndIncrement() == 0 ? List.of(run) : List.of();
    }

    private McpHandler handler(Supplier<List<String>> history, LongFunction<String> finishedRecords) {
        McpHandler mcp = newHandler(history, finishedRecords);
        // These stubs either answer the first by-jid poll or never will — the production 1s
        // journal-settle window only adds wall time here.
        mcp.journalSettleMs(50);
        return mcp;
    }

    private McpHandler newHandler(Supplier<List<String>> history, LongFunction<String> finishedRecords) {
        return new McpHandler(
                () -> new StatusSnapshot(
                        "0.12.0",
                        1L,
                        System.currentTimeMillis() - 5_000,
                        0,
                        0,
                        1L << 20,
                        2L << 20,
                        256L << 20,
                        -1L,
                        8,
                        16L << 30),
                jobs,
                dir -> Map.of("coord", "com.example:demo"),
                history,
                "0.12.0",
                new ProgressTokenRegistry(),
                liveOnce(),
                AdmissionYield.NONE,
                finishedRecords);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(String body) {
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        Map<String, Object> result = object(resp, "result");
        return object(result, "structuredContent");
    }

    private static String runWait(McpHandler mcp) {
        return mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_run\",\"arguments\":"
                + "{\"kind\":\"build\",\"dir\":\"/ws\",\"wait\":true,\"timeout_s\":2}}}");
    }

    @Test
    void wait_uses_the_by_jid_lookup_and_never_rescans_history() {
        McpHandler mcp = handler(
                () -> {
                    historyScans.incrementAndGet();
                    return List.of();
                },
                jid -> jid == JID ? FINISHED_OK : null);
        String body = runWait(mcp);
        Map<String, Object> fields = structured(body);
        assertThat(fields.get("finished")).isEqualTo(true);
        assertThat(fields.get("success")).isEqualTo(true);
        assertThat(number(fields, "jid").longValue()).isEqualTo(JID);
        assertThat(textOf(body)).startsWith("OK build a ·");
        assertThat(historyScans).hasValue(0);
    }

    @Test
    void wait_with_no_journal_row_never_reports_a_previous_runs_outcome() {
        // History disabled: no row for this jid; the newest row on disk is an old success.
        String stale = "{\"id\":\"old\",\"kind\":\"build\",\"dir\":\"/ws\",\"success\":true,"
                + "\"exitCode\":0,\"startedAt\":1700000000000,\"modules\":[],\"diagnostics\":[]}";
        McpHandler mcp = handler(() -> List.of(stale), jid -> null);
        Map<String, Object> fields = structured(runWait(mcp));
        assertThat(fields.get("finished")).isEqualTo(true);
        assertThat(fields).doesNotContainKey("success");
    }

    @Test
    void wait_fallback_accepts_an_unstamped_row_started_after_the_trigger() {
        String fresh = "{\"id\":\"new\",\"kind\":\"build\",\"dir\":\"/ws\",\"success\":true,"
                + "\"exitCode\":0,\"startedAt\":" + (System.currentTimeMillis() + 60_000)
                + ",\"modules\":[],\"diagnostics\":[]}";
        McpHandler mcp = handler(() -> List.of(fresh), jid -> null);
        String body = runWait(mcp);
        Map<String, Object> fields = structured(body);
        assertThat(fields.get("success")).isEqualTo(true);
        assertThat(textOf(body)).startsWith("OK build");
    }

    @Test
    void run_returns_the_failure_then_the_next_runs_ok() {
        String failed = "{\"id\":\"rf\",\"kind\":\"build\",\"dir\":\"/ws\",\"success\":false,"
                + "\"exitCode\":1,\"millis\":700,\"coord\":\"g:a\",\"requestId\":45,"
                + "\"startedAt\":1700000000000,\"modules\":[],"
                + "\"diagnostics\":[{\"severity\":\"error\",\"dir\":\"/ws\",\"file\":\"src/A.java\","
                + "\"line\":3,\"col\":10,\"message\":\"';' expected\"}]}";
        AtomicInteger served = new AtomicInteger();
        McpHandler mcp = handler(() -> List.of(), jid -> {
            if (jid != JID) return null;
            return served.getAndIncrement() == 0 ? failed : FINISHED_OK;
        });
        String first = textOf(runWait(mcp));
        String second = textOf(runWait(mcp));
        assertThat(first).startsWith("FAIL build a").contains("src/A.java:3:10").contains("';' expected");
        assertThat(first).doesNotContain("jk_results", "dashboard", "session");
        assertThat(second).startsWith("OK build a").doesNotContain("jk_results");
        assertThat(served).hasValue(2);
    }

    @Test
    void failed_wait_outside_the_bound_dir_still_attaches_diagnostics() {
        String failed = "{\"id\":\"rf\",\"kind\":\"build\",\"dir\":\"/ws/b\",\"success\":false,"
                + "\"exitCode\":1,\"requestId\":45,\"startedAt\":1700000000000,\"modules\":[],"
                + "\"diagnostics\":[{\"severity\":\"error\",\"dir\":\"/ws/b\","
                + "\"message\":\"/ws/b/Bad.java:1: error: cannot find symbol\"}]}";
        McpHandler mcp = handler(() -> List.of(failed), jid -> jid == JID ? failed : null);
        mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_bind\",\"arguments\":{\"dir\":\"/ws/a\"}}}");
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,"
                + "\"method\":\"tools/call\",\"params\":{\"name\":\"jk_run\",\"arguments\":"
                + "{\"kind\":\"build\",\"dir\":\"/ws/b\",\"wait\":true,\"timeout_s\":2}}}");
        Map<String, Object> fields = structured(body);
        assertThat(fields.get("success")).isEqualTo(false);
        assertThat(fields).doesNotContainKeys("diagnostics", "dashboard", "session");
        assertThat(textOf(body)).contains("Bad.java").contains("cannot find symbol");
    }

    @SuppressWarnings("unchecked")
    private static String textOf(String body) {
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        List<Map<String, Object>> content = objects(object(resp, "result"), "content");
        return String.valueOf(content.getFirst().get("text"));
    }
}
