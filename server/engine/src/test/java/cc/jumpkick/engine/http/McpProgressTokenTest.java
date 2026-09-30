// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.jobs.JobSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MCP progress tokens: numeric ones match their SSE query text form, and a stream may open before the bind. */
class McpProgressTokenTest {

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return 42L;
        }

        @Override
        public boolean cancel(long requestId) {
            return false;
        }

        public int cancelDir(String dir) {
            return 0;
        }
    };

    @Test
    void integer_meta_token_binds_under_its_integral_text_form() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
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
                        8,
                        16L << 30),
                jobs,
                dir -> Map.of(),
                List::of,
                "0.12.0",
                tokens,
                List::of,
                AdmissionYield.NONE,
                null);
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"build\",\"arguments\":{\"dir\":\"/tmp/demo\"},"
                + "\"_meta\":{\"progressToken\":5}}}");
        assertThat(tokens.resolve("5")).isEqualTo(42L); // raw SSE query text
        assertThat(body).contains("jid=42");
        assertThat(body).doesNotContain("5.0");
    }

    @Test
    void a_stream_opened_before_the_call_binds_its_token_follows_that_job_and_no_other() throws Exception {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        HttpEvents hub = new HttpEvents();
        try (LiveVitals vitals = new LiveVitals(hub, () -> null, () -> null)) {
            SseEndpoint sse = new SseEndpoint(hub, vitals, tokens, line -> {});
            try (HttpEvents.Subscription s =
                    hub.subscribeFollowing(HttpEvents.FrameStyle.MCP, sse.mcpEventFilter("progressToken=t-1"))) {
                hub.publish("eta", JsonOut.object().put("jid", 42L));
                assertThat(s.next(20)).as("unbound token delivers nothing").isNull();
                tokens.bind("t-1", 42L);
                hub.publish("eta", JsonOut.object().put("jid", 41L));
                hub.publish("eta", JsonOut.object().put("jid", 42L));
                assertThat(s.next(1_000)).contains("\"jid\":42");
                assertThat(s.next(20)).isNull();
            }
        }
    }

    @Test
    void resolve_canonicalizes_double_form_queries_and_leaves_strings_alone() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.bind("7", 99L);
        assertThat(tokens.resolve("7.0")).isEqualTo(99L);
        tokens.bind("tok-1", 100L);
        assertThat(tokens.resolve("tok-1")).isEqualTo(100L);
        assertThat(ProgressTokenRegistry.canonicalText("5.0")).isEqualTo("5");
        assertThat(ProgressTokenRegistry.canonicalText("-3.00")).isEqualTo("-3");
        assertThat(ProgressTokenRegistry.canonicalText("v1.0")).isEqualTo("v1.0");
        assertThat(ProgressTokenRegistry.canonicalText("5.5")).isEqualTo("5.5");
    }
}
