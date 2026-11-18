// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.decisions.Dashboards;
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
    void a_full_registry_keeps_the_token_of_a_job_still_running() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.live(jid -> jid == 1L);
        tokens.bind(null, "running", 1L);
        for (int i = 0; i < 300; i++) tokens.bind(null, "t" + i, 100L + i);

        assertThat(tokens.resolve(null, "running")).isEqualTo(1L);
        assertThat(tokens.resolve(null, "t299")).isEqualTo(399L);
        assertThat(tokens.resolve(null, "t0"))
                .as("the oldest finished job's token goes first")
                .isNull();
        assertThat(tokens.size()).isLessThanOrEqualTo(ProgressTokenRegistry.MAX_ENTRIES);
    }

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
    void resolve_canonicalizes_double_form_queries_and_leaves_strings_alone() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.bind(null, "7", 99L);
        assertThat(tokens.resolve(null, "7.0")).isEqualTo(99L);
        tokens.bind(null, "tok-1", 100L);
        assertThat(tokens.resolve(null, "tok-1")).isEqualTo(100L);
        assertThat(ProgressTokenRegistry.canonicalText("5.0")).isEqualTo("5");
        assertThat(ProgressTokenRegistry.canonicalText("-3.00")).isEqualTo("-3");
        assertThat(ProgressTokenRegistry.canonicalText("v1.0")).isEqualTo("v1.0");
        assertThat(ProgressTokenRegistry.canonicalText("5.5")).isEqualTo("5.5");
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

    @Test
    void a_busy_session_evicts_its_own_running_tokens_before_another_sessions() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.live(jid -> true);
        tokens.bind("ab12", "7", 1L);
        for (int i = 0; i < 300; i++) tokens.bind("cd34", "t" + i, 100L + i);

        assertThat(tokens.resolve("ab12", "7")).isEqualTo(1L);
        assertThat(tokens.resolve("cd34", "t299")).isEqualTo(399L);
        assertThat(tokens.resolve("cd34", "t0")).isNull();
        assertThat(tokens.size()).isLessThanOrEqualTo(ProgressTokenRegistry.MAX_ENTRIES);
    }

    @Test
    void forgetting_a_session_drops_only_its_tokens() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.bind("ab12", "7", 41L);
        tokens.bind("cd34", "7", 42L);
        tokens.bind(null, "7", 43L);
        tokens.forget("ab12");
        assertThat(tokens.resolve("ab12", "7")).isNull();
        assertThat(tokens.resolve("cd34", "7")).isEqualTo(42L);
        assertThat(tokens.resolve(null, "7")).isEqualTo(43L);
    }
}
