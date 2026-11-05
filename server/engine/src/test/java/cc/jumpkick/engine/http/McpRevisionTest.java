// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.parseObject;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.http.mcp.McpConnection;
import cc.jumpkick.engine.http.mcp.McpElicitModes;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.mcp.McpRevision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Each revision jk speaks, as a client negotiating it sees the server: the same handshake and the
 * same default tool list, with the revision and the client's elicitation modes recorded on the
 * session.
 */
class McpRevisionTest {

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return 41L;
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

    private final McpHandler mcp = new McpHandler(
            () -> new StatusSnapshot(
                    "0.13.7",
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
            List::of,
            "0.13.7");

    @ParameterizedTest
    @EnumSource(McpRevision.class)
    void each_revision_is_answered_as_asked_with_the_same_handshake_and_tool_list(McpRevision revision) {
        McpHandler.Reply reply = initialize(revision.wire(), "{\"elicitation\":{}}");
        Map<String, Object> result = object(parseObject(reply.body()), "result");
        assertThat(result.get("protocolVersion")).isEqualTo(revision.wire());
        assertThat(object(result, "capabilities"))
                .containsOnlyKeys("tools", "resources", "prompts", "logging", "experimental");
        assertThat(withoutVersion(result)).isEqualTo(withoutVersion(baseline()));

        String session = requireNonNull(reply.openedSessionId());
        String listing = mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                        session,
                        null,
                        revision.wire(),
                        null)
                .body();
        String anonymous = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(listing).isEqualTo(anonymous);

        McpConnection c = requireNonNull(mcp.connection(session));
        assertThat(c.revision()).isEqualTo(revision);
        assertThat(c.elicitation())
                .isEqualTo(revision.formElicitation() ? new McpElicitModes(true, false) : McpElicitModes.NONE);
    }

    @Test
    void a_revision_jk_does_not_speak_is_answered_with_the_newest() {
        McpHandler.Reply reply = initialize("2099-01-01", "{}");
        assertThat(object(parseObject(reply.body()), "result").get("protocolVersion"))
                .isEqualTo(McpRevision.LATEST.wire());
        assertThat(requireNonNull(mcp.connection(reply.openedSessionId())).revision())
                .isEqualTo(McpRevision.LATEST);
    }

    @Test
    void the_newest_revision_records_form_and_url_as_declared() {
        assertThat(modes("2025-11-25", "{\"elicitation\":{\"form\":{},\"url\":{}}}"))
                .isEqualTo(new McpElicitModes(true, true));
        assertThat(modes("2025-11-25", "{\"elicitation\":{\"url\":{}}}")).isEqualTo(new McpElicitModes(false, true));
        assertThat(modes("2025-11-25", "{\"elicitation\":{}}")).isEqualTo(new McpElicitModes(true, false));
        assertThat(modes("2025-11-25", "{\"sampling\":{}}")).isEqualTo(McpElicitModes.NONE);
        // URL mode does not exist before 2025-11-25, and elicitation not before 2025-06-18.
        assertThat(modes("2025-06-18", "{\"elicitation\":{\"url\":{}}}")).isEqualTo(new McpElicitModes(true, false));
        assertThat(modes("2025-03-26", "{\"elicitation\":{}}")).isEqualTo(McpElicitModes.NONE);
    }

    @Test
    void a_session_from_2025_06_18_on_gets_no_batches() {
        String batch =
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]";
        String newer = requireNonNull(initialize("2025-06-18", "{}").openedSessionId());
        assertThat(mcp.handle(batch, newer).body()).contains("\"code\":-32600").contains("batches");
        String older = requireNonNull(initialize("2025-03-26", "{}").openedSessionId());
        assertThat(mcp.handle(batch, older).body()).startsWith("[");
        // The header names the revision when no session does.
        assertThat(mcp.handle(batch, null, null, "2025-11-25", null).body()).contains("\"code\":-32600");
    }

    @Test
    void a_protocol_version_header_jk_does_not_speak_is_refused() {
        assertThatThrownBy(() -> mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null, null, "1999-01-01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP-Protocol-Version");
    }

    @Test
    void a_response_from_the_client_gets_no_reply() {
        String session =
                requireNonNull(initialize("2025-11-25", "{\"elicitation\":{}}").openedSessionId());
        assertThat(mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":\"nothing-pending\",\"result\":{}}", session)
                        .body())
                .isEmpty();
    }

    private McpElicitModes modes(String version, String capabilities) {
        return requireNonNull(mcp.connection(initialize(version, capabilities).openedSessionId()))
                .elicitation();
    }

    private McpHandler.Reply initialize(String version, String capabilities) {
        return mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + version
                        + "\",\"capabilities\":" + capabilities
                        + ",\"clientInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}",
                null);
    }

    private Map<String, Object> baseline() {
        return object(
                parseObject(initialize(McpRevision.V2024_11_05.wire(), "{}").body()), "result");
    }

    private static Map<String, Object> withoutVersion(Map<String, Object> result) {
        Map<String, Object> copy = new LinkedHashMap<>(result);
        copy.remove("protocolVersion");
        return copy;
    }
}
