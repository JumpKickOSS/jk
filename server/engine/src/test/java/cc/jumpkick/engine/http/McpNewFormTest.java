// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.parseObject;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpTools;
import cc.jumpkick.engine.http.mcp.tools.NewTool;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MCP {@code new} with a template that asks every kind a template may ask, none of them defaulted,
 * through a fake client: one that answers form elicitation (on both revisions that have it), one
 * that declines, and one that declared none, which gets the missing keys and their values instead.
 */
class McpNewFormTest {

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final McpHandler mcp = handler();

    @TempDir
    Path temp;

    private Path template;
    private Path parent;

    @BeforeEach
    void template() throws IOException {
        template = temp.resolve("templates/java/none/asking.g8");
        Files.createDirectories(template.resolve("src/main/g8"));
        Files.writeString(template.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "asking"
                layouts = ["traditional"]

                [[ask]]
                key = "motto"
                label = "Motto"
                kind = "text"
                pattern = "[a-z]+"

                [[ask]]
                key = "native"
                label = "Native binary"
                kind = "bool"

                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "postgres"]

                [[ask]]
                key = "cache"
                label = "Cache"
                kind = "choice"
                options = ["off", "on"]
                when = "database == 'postgres'"

                [[ask]]
                key = "libs"
                label = "Libraries"
                kind = "multi"
                options = ["guava", "jackson"]

                [[ask]]
                key = "flavour"
                label = "Flavour"
                kind = "choice"
                options = ["plain", "sweet"]
                default = "plain"
                """);
        Files.writeString(template.resolve("default.properties"), "name=demo\n");
        Files.writeString(template.resolve("src/main/g8/jk.toml"), """
                name = "$name$"
                motto = "$motto$"
                db = "$database$"
                $if(database_postgres.truthy)$
                cache = "$cache$"
                $endif$
                libs = "$libs$"
                flavour = "$flavour$"
                $if(native.truthy)$
                [native]
                $endif$
                """);
        parent = Files.createDirectories(temp.resolve("apps"));
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    @Test
    void without_elicitation_the_error_lists_each_missing_key_and_what_it_takes() {
        String session = initialize("2025-11-25", "{}");
        Map<String, Object> error = error(mcp.handle(call(Map.of("name", "widget")), session));

        assertThat(error.get("code")).isEqualTo(-32602.0);
        assertThat(String.valueOf(error.get("message")))
                .contains("motto: text /[a-z]+/ (required)  # Motto")
                .contains("native: true|false (required)")
                .contains("database: none|postgres (required)")
                .contains("libs: [guava,jackson] (required)")
                .contains("params")
                .doesNotContain("flavour", "cache:");
        assertThat(parent.resolve("widget")).doesNotExist();
    }

    @Test
    void an_agent_that_passes_every_answer_is_never_asked() throws Exception {
        String session = initialize("2025-11-25", "{\"elicitation\":{\"form\":{}}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        Map<String, Object> params = Map.of(
                "motto", "hi", "native", true, "database", "postgres", "cache", "on", "libs", List.of("jackson"));

        McpHandler.Reply reply =
                mcp.handle(call(Map.of("name", "widget", "params", params)), session, null, null, sent::add);

        assertThat(reply.body()).contains("\"created\"");
        assertThat(sent).isEmpty();
        assertThat(Files.readString(parent.resolve("widget/jk.toml")))
                .contains("motto = \"hi\"", "db = \"postgres\"", "cache = \"on\"", "libs = \"jackson\"")
                .contains("flavour = \"plain\"", "[native]");
    }

    @Test
    void a_misfit_answer_is_refused_with_the_values_the_field_takes() {
        String session = initialize("2025-11-25", "{}");
        Map<String, Object> error =
                error(mcp.handle(call(Map.of("name", "widget", "params", Map.of("database", "oracle"))), session));
        assertThat(String.valueOf(error.get("message")))
                .contains("database must be one of none, postgres; got 'oracle' (none|postgres)");
    }

    @Test
    void form_elicitation_asks_exactly_the_missing_fields_one_step_at_a_time() throws Exception {
        String session = initialize("2025-11-25", "{\"elicitation\":{\"form\":{}}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = CompletableFuture.supplyAsync(
                () -> mcp.handle(call(Map.of()), session, null, null, sent::add), threads);

        Map<String, Object> project = nextQuestion(sent);
        assertThat(properties(project)).containsOnlyKeys("name");
        answer(session, project, Map.of("name", "widget"));

        Map<String, Object> asks = nextQuestion(sent);
        assertThat(object(project, "params").get("message")).isEqualTo("New project: Project");
        Map<String, Object> props = properties(asks);
        assertThat(props).containsOnlyKeys("motto", "native", "database", "libs");
        assertThat(object(props, "motto")).containsEntry("type", "string").containsEntry("pattern", "[a-z]+");
        assertThat(object(props, "native")).containsEntry("type", "boolean");
        assertThat(object(props, "database")).containsEntry("enum", List.of("none", "postgres"));
        assertThat(object(props, "libs")).containsEntry("type", "array");
        answer(
                session,
                asks,
                Map.of("motto", "hi", "native", false, "database", "postgres", "libs", List.of("guava", "jackson")));

        Map<String, Object> deferred = nextQuestion(sent);
        assertThat(properties(deferred)).containsOnlyKeys("cache");
        answer(session, deferred, Map.of("cache", "off"));

        assertThat(call.get(30, TimeUnit.SECONDS).body()).contains("\"created\"");
        assertThat(Files.readString(parent.resolve("widget/jk.toml")))
                .contains("motto = \"hi\"", "db = \"postgres\"", "cache = \"off\"", "libs = \"guava,jackson\"")
                .doesNotContain("[native]");
    }

    @Test
    void before_2025_11_25_a_multi_is_asked_as_one_boolean_per_option() throws Exception {
        String session = initialize("2025-06-18", "{\"elicitation\":{}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        Map<String, Object> params = Map.of("motto", "hi", "native", "yes", "database", "none");
        CompletableFuture<McpHandler.Reply> call = CompletableFuture.supplyAsync(
                () -> mcp.handle(call(Map.of("name", "widget", "params", params)), session, null, null, sent::add),
                threads);

        Map<String, Object> asks = nextQuestion(sent);
        assertThat(object(asks, "params")).doesNotContainKey("mode");
        assertThat(properties(asks)).containsOnlyKeys("libs.guava", "libs.jackson");
        answer(session, asks, Map.of("libs.guava", true, "libs.jackson", false));

        assertThat(call.get(30, TimeUnit.SECONDS).body()).contains("\"created\"");
        assertThat(Files.readString(parent.resolve("widget/jk.toml")))
                .contains("libs = \"guava\"", "db = \"none\"", "[native]");
    }

    @Test
    void a_declined_form_creates_nothing() throws Exception {
        String session = initialize("2025-11-25", "{\"elicitation\":{\"form\":{}}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = CompletableFuture.supplyAsync(
                () -> mcp.handle(call(Map.of("name", "widget")), session, null, null, sent::add), threads);

        Map<String, Object> asks = nextQuestion(sent);
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + asks.get("id") + "\",\"result\":{\"action\":\"decline\"}}", session);

        Map<String, Object> error = error(call.get(30, TimeUnit.SECONDS));
        assertThat(String.valueOf(error.get("message"))).contains("declined", "motto", "nothing was created");
        assertThat(parent.resolve("widget")).doesNotExist();
    }

    @Test
    void action_form_lists_one_line_per_field_and_templates_stay_lean() {
        String session = initialize("2025-11-25", "{}");
        String form = mcp.handle(
                        rpc(Map.of("action", "form", "template", template.toString(), "parentDir", parent.toString())),
                        session)
                .body();
        assertThat(form)
                .contains("new-form")
                .contains("database: none|postgres (required)  # Database")
                .contains("cache: off|on (required) when database == 'postgres'  # Cache")
                .contains("flavour: plain|sweet = plain  # Flavour")
                .doesNotContain("layout:");
        // jk's step comes first, from the same field list the CLI and the engine's form use; dir is
        // answered by parentDir here.
        assertThat(form)
                .contains("lang: java|kotlin|groovy|scala = java  # Language")
                .contains("template: search = app  # Template")
                .contains("name: text /[a-z][a-z0-9-]*/ (required)  # Name")
                .contains("group: text")
                .contains("parentDir: path = from parent-dir  # Directory");
        assertThat(form.indexOf("lang: ")).isLessThan(form.indexOf("database: "));

        String templates =
                mcp.handle(rpc(Map.of("action", "templates")), session).body();
        assertThat(templates).contains("\"templates\"").doesNotContain("\"layouts\"", "builtinLayouts", "\"source\"");
    }

    @Test
    void with_no_template_the_form_and_the_scaffold_are_the_app_template_s() {
        String session = initialize("2025-11-25", "{}");
        String form = mcp.handle(rpc(Map.of("action", "form", "parentDir", parent.toString())), session)
                .body();
        assertThat(form).contains("java/none/app").contains("assembly: true|false = false");

        String created = mcp.handle(rpc(Map.of("name", "plain", "parentDir", parent.toString())), session)
                .body();
        assertThat(created).as(created).contains("created");
        assertThat(parent.resolve("plain/jk.toml")).content().contains("[application]");
    }

    private String call(Map<String, Object> extra) {
        Map<String, Object> args = new LinkedHashMap<>(extra);
        args.put("template", template.toString());
        args.put("parentDir", parent.toString());
        return rpc(args);
    }

    private static String rpc(Map<String, Object> args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"new\",\"arguments\":"
                + MiniJson.write(args) + "}}";
    }

    private static Map<String, Object> nextQuestion(BlockingQueue<Map<String, Object>> sent) throws Exception {
        Map<String, Object> request = requireNonNull(sent.poll(20, TimeUnit.SECONDS), "no elicitation came");
        assertThat(request.get("method")).isEqualTo("elicitation/create");
        return request;
    }

    private static Map<String, Object> properties(Map<String, Object> request) {
        return object(object(object(request, "params"), "requestedSchema"), "properties");
    }

    private void answer(String session, Map<String, Object> request, Map<String, Object> content) {
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":" + Jsonl.quote(String.valueOf(request.get("id")))
                        + ",\"result\":{\"action\":\"accept\",\"content\":" + MiniJson.write(content) + "}}",
                session);
    }

    private static Map<String, Object> error(McpHandler.Reply reply) {
        Map<String, Object> body = parseObject(reply.body());
        assertThat(body).as(reply.body()).containsKey("error");
        return object(body, "error");
    }

    private String initialize(String version, String capabilities) {
        return requireNonNull(mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                                + version + "\",\"capabilities\":" + capabilities
                                + ",\"clientInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}",
                        null)
                .openedSessionId());
    }

    private static McpHandler handler() {
        McpHandler h = new McpHandler(
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
                List::of,
                "0.13.7");
        h.tools(new McpTools(List.of(new NewTool())));
        return h;
    }
}
