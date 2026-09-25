// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.number;
import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static cc.jumpkick.engine.http.JsonFields.string;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk://guards} and {@code jk://guards/<id>} read the same JSON {@code jk guard explain} prints. */
class McpGuardsResourceTest {

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
        return new McpHandler(
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
                "0.12.0");
    }

    private static void project(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"ws\"\nversion = \"0.0.1\"\njdk = 25\n");
        Files.writeString(root.resolve("jk-guards.toml"), """
                [guards.no-todo]
                kind    = "text"
                pattern = "TODO"
                instead = "a ticket"
                why     = "a TODO is a promise nobody tracks"
                """);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resp(String body) {
        return (Map<String, Object>) requireNonNull(MiniJson.parse(body));
    }

    private static String text(String body) {
        Map<String, Object> result = object(resp(body), "result");
        List<Map<String, Object>> contents = objects(result, "contents");
        assertThat(contents.get(0).get("mimeType")).isEqualTo("application/json");
        return string(contents.get(0), "text");
    }

    private static String read(McpHandler mcp, int id, String uri) {
        return read(mcp, null, id, uri);
    }

    /** {@code resources/read} on the connection {@code session} names; null reads anonymously. */
    private static String read(McpHandler mcp, @Nullable String session, int id, String uri) {
        return mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"method\":\"resources/read\",\"params\":{\"uri\":\"" + uri + "\"}}",
                        session)
                .body();
    }

    /** A connection bound to {@code root}: {@code initialize} mints it, {@code bind} binds it. */
    private static String boundTo(McpHandler mcp, Path root) {
        String session = requireNonNull(mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}", null)
                .openedSessionId());
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"bind\",\"arguments\":{\"dir\":\""
                        + root.toString().replace("\\", "\\\\") + "\"}}}",
                session);
        return session;
    }

    @Test
    void the_catalog_and_a_card_read_the_explain_json_and_an_unknown_id_is_a_parameter_error(@TempDir Path root)
            throws Exception {
        project(root);
        McpHandler mcp = handler();
        String list = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertThat(list).contains("\"jk://guards\"").contains("jk://guards/<id>");

        assertThat(text(read(mcp, 2, "jk://guards"))).contains("bind first");
        String session = boundTo(mcp, root);

        @SuppressWarnings("unchecked")
        Map<String, Object> catalog =
                (Map<String, Object>) requireNonNull(MiniJson.parse(text(read(mcp, session, 4, "jk://guards"))));
        List<Map<String, Object>> rules = objects(catalog, "rules");
        assertThat(rules).singleElement().satisfies(r -> {
            assertThat(r.get("id")).isEqualTo("no-todo");
            assertThat(r.get("kind")).isEqualTo("text");
            assertThat(r.get("why")).isEqualTo("a TODO is a promise nobody tracks");
            assertThat(r.get("instead")).isEqualTo("a ticket");
            assertThat(r).containsKeys("scope", "population", "baselineEntries", "lastOutcome", "source");
        });
        assertThat(catalog).containsKeys("rulesSha", "baselineSha");

        @SuppressWarnings("unchecked")
        Map<String, Object> card =
                (Map<String, Object>) requireNonNull(MiniJson.parse(text(read(mcp, session, 5, "jk://guards/no-todo"))));
        List<Map<String, Object>> one = objects(card, "rules");
        assertThat(one).singleElement().satisfies(r -> assertThat(r.get("id")).isEqualTo("no-todo"));

        Map<String, Object> unknown = resp(read(mcp, session, 6, "jk://guards/no-tod"));
        Map<String, Object> error = object(unknown, "error");
        assertThat(error).isNotNull();
        assertThat(number(error, "code").intValue()).isEqualTo(-32602);
        assertThat(String.valueOf(error.get("message")))
                .contains("unknown guard: no-tod")
                .contains("no-todo");

        // an engine validation's code reads as its card too
        assertThat(text(read(mcp, session, 7, "jk://guards/tiers")))
                .contains("\"validations\"")
                .contains("\"code\":\"tiers\"");
    }

    @Test
    void a_project_without_guards_says_so(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"ws\"\nversion = \"0.0.1\"\njdk = 25\n");
        McpHandler mcp = handler();
        String session = boundTo(mcp, root);
        assertThat(text(read(mcp, session, 2, "jk://guards"))).contains("no guards here");
    }
}
