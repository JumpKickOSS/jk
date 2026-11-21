// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.decisions.Dashboards;
import cc.jumpkick.engine.http.api.AdmissionYield;
import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.ProgressTokenRegistry;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MCP progress tokens: numeric ones match their SSE query text form, a stream may open before the
 * bind, and each session's tokens are its own.
 */
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
                + "\"params\":{\"name\":\"run\",\"arguments\":{\"dir\":\"/tmp/demo\",\"kind\":\"build\",\"wait\":false},"
                + "\"_meta\":{\"progressToken\":5}}}");
        assertThat(tokens.resolve(null, "5")).isEqualTo(42L); // raw SSE query text
        assertThat(body).contains("jid=42");
        assertThat(body).doesNotContain("5.0");
    }

    @Test
    void a_stream_opened_before_the_call_binds_its_token_follows_that_job_and_no_other() throws Exception {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        HttpEvents hub = new HttpEvents();
        try (LiveVitals vitals = new LiveVitals(hub, () -> null, () -> null)) {
            SseEndpoint sse = new SseEndpoint(hub, new Dashboards(), vitals, tokens, line -> {});
            try (HttpEvents.Subscription s =
                    hub.subscribeFollowing(HttpEvents.FrameStyle.MCP, sse.mcpEventFilter(null, "progressToken=t-1"))) {
                hub.publish("eta", JsonOut.object().put("jid", 42L));
                assertThat(s.next(20)).as("unbound token delivers nothing").isNull();
                tokens.bind(null, "t-1", 42L);
                hub.publish("eta", JsonOut.object().put("jid", 41L));
                hub.publish("eta", JsonOut.object().put("jid", 42L));
                assertThat(s.next(1_000)).contains("\"jid\":42");
                assertThat(s.next(20)).isNull();
            }
        }
    }

    @Test
    void a_stream_follows_the_token_its_own_session_bound_and_never_another_sessions() throws Exception {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.bind("ab12", "7", 41L);
        tokens.bind("cd34", "7", 42L);
        tokens.bind("ab12", "only-a", 43L);
        HttpEvents hub = new HttpEvents();
        try (LiveVitals vitals = new LiveVitals(hub, () -> null, () -> null)) {
            SseEndpoint sse = new SseEndpoint(hub, new Dashboards(), vitals, tokens, line -> {});
            assertThat(requireNonNull(sse.mcpEventFilter("ab12", "progressToken=7"))
                            .get())
                    .isEqualTo(41L);
            assertThat(requireNonNull(sse.mcpEventFilter("cd34", "progressToken=7"))
                            .get())
                    .isEqualTo(42L);
            assertThat(requireNonNull(sse.mcpEventFilter("cd34", "progressToken=only-a"))
                            .get())
                    .as("B's session never reaches a token only A bound")
                    .isNull();
            assertThat(requireNonNull(sse.mcpEventFilter(null, "progressToken=7"))
                            .get())
                    .as("a stream with no session finds only tokens bound with none")
                    .isNull();
        }
    }
}
