// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.parseObject;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.engine.http.mcp.McpTools;
import cc.jumpkick.engine.http.mcp.NewProjectWizard;
import cc.jumpkick.engine.http.mcp.tools.NewTool;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.runtime.NewProjectAgents;
import cc.jumpkick.engine.runtime.NewProjectJava;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * that declines, one that declared URL elicitation alone, which is sent to the dashboard's wizard,
 * and one that declared none, which gets the missing keys and their values instead. A created
 * project is wired for the agent the client's {@code clientInfo.name} names, and for the engine's
 * agents setting.
 */
class McpNewFormTest {

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final NewProjectWizard wizard = new NewProjectWizard(() -> "http://127.0.0.1:8910/", () -> "T");
    private final McpHandler mcp = handler(wizard);

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
                $if(cache)$
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
        NewProjectAgents.env(null);
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
    void a_created_project_is_wired_for_the_engine_s_agents_setting_and_the_reply_says_what_was_left() {
        NewProjectAgents.env(key -> "JK_AGENTS".equals(key) ? "all" : null);
        String session = initialize("2025-11-25", "{}");
        Map<String, Object> params =
                Map.of("motto", "hi", "native", false, "database", "none", "libs", List.of("guava"));

        McpHandler.Reply reply = mcp.handle(call(Map.of("name", "widget", "params", params)), session);

        assertThat(reply.body()).contains("\"created\"", "AGENTS.md", "CLAUDE.md", AgentSetup.TRUST_NOTE);
        assertThat(parent.resolve("widget/AGENTS.md")).isRegularFile();
        assertThat(parent.resolve("widget/.mcp.json")).isRegularFile();
        assertThat(parent.resolve("widget/.claude/settings.json")).isRegularFile();
    }

    @Test
    void a_known_client_gets_its_own_agent_s_files_with_no_agents_setting_in_the_engine() {
        NewProjectAgents.env(key -> null);
        String session = initialize("2025-11-25", "{}", "claude-code");

        McpHandler.Reply reply = mcp.handle(call(Map.of("name", "widget", "params", answered())), session);

        assertThat(reply.body()).contains("\"created\"", "CLAUDE.md", "Agents wired: claude");
        assertThat(parent.resolve("widget/AGENTS.md")).isRegularFile();
        assertThat(parent.resolve("widget/CLAUDE.md")).isRegularFile();
        assertThat(parent.resolve("widget/.claude/settings.json")).isRegularFile();
        assertThat(parent.resolve("widget/.gemini")).doesNotExist();
    }

    @Test
    void an_unknown_client_is_wired_for_the_engine_s_agents_setting_alone() {
        NewProjectAgents.env(key -> null);
        String session = initialize("2025-11-25", "{}", "some-editor");

        McpHandler.Reply reply = mcp.handle(call(Map.of("name", "widget", "params", answered())), session);

        assertThat(reply.body()).contains("\"created\"", "Agents wired: none detected");
        assertThat(parent.resolve("widget/AGENTS.md")).isRegularFile();
        assertThat(parent.resolve("widget/CLAUDE.md")).doesNotExist();
        assertThat(parent.resolve("widget/.claude")).doesNotExist();
    }

    @Test
    void agents_off_in_the_engine_wire_none_even_for_a_known_client() {
        NewProjectAgents.env(key -> "JK_AGENTS".equals(key) ? "none" : null);
        String session = initialize("2025-11-25", "{}", "claude-code");

        McpHandler.Reply reply = mcp.handle(call(Map.of("name", "widget", "params", answered())), session);

        assertThat(reply.body()).contains("\"created\"", "Agents wired: none (JK_AGENTS=none)");
        assertThat(parent.resolve("widget/AGENTS.md")).isRegularFile();
        assertThat(parent.resolve("widget/CLAUDE.md")).doesNotExist();
        assertThat(parent.resolve("widget/.mcp.json")).doesNotExist();
        assertThat(parent.resolve("widget/.claude")).doesNotExist();
    }

    private static Map<String, Object> answered() {
        return Map.of("motto", "hi", "native", false, "database", "none", "libs", List.of("guava"));
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
    void url_elicitation_opens_the_wizard_pre_filled_and_returns_what_it_created() throws Exception {
        String session = initialize("2025-11-25", "{\"elicitation\":{\"url\":{}}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = CompletableFuture.supplyAsync(
                () -> mcp.handle(
                        call(Map.of("name", "widget", "params", Map.of("motto", "hi"))),
                        session,
                        null,
                        null,
                        sent::add),
                threads);

        Map<String, Object> ask = nextQuestion(sent);
        Map<String, Object> params = object(ask, "params");
        String id = String.valueOf(params.get("elicitationId"));
        assertThat(params).containsEntry("mode", "url");
        assertThat(String.valueOf(params.get("url")))
                .startsWith("http://127.0.0.1:8910/#new?")
                .contains("name=widget", "motto=hi", "dir=" + enc(parent.toString()), "template=")
                .contains("&elicitation=" + id, "&t=T");
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":" + Jsonl.quote(String.valueOf(ask.get("id")))
                        + ",\"result\":{\"action\":\"accept\"}}",
                session);
        // The wizard's create, as POST /api/projects hands it over.
        String made = parent.resolve("widget").toString();
        while (!wizard.complete(id, Map.of("path", made, "projectId", "abc123"))) Thread.sleep(20);

        assertThat(call.get(30, TimeUnit.SECONDS).body()).contains("\"created\"", "abc123");
        Map<String, Object> done = requireNonNull(sent.poll(5, TimeUnit.SECONDS), "no completion notice");
        assertThat(done.get("method")).isEqualTo("notifications/elicitation/complete");
        assertThat(object(done, "params")).containsEntry("elicitationId", id);
    }

    @Test
    void a_declined_wizard_link_creates_nothing() throws Exception {
        String session = initialize("2025-11-25", "{\"elicitation\":{\"url\":{}}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call = CompletableFuture.supplyAsync(
                () -> mcp.handle(call(Map.of("name", "widget")), session, null, null, sent::add), threads);

        Map<String, Object> ask = nextQuestion(sent);
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + ask.get("id") + "\",\"result\":{\"action\":\"decline\"}}", session);

        Map<String, Object> error = error(call.get(30, TimeUnit.SECONDS));
        assertThat(String.valueOf(error.get("message"))).contains("declined", "wizard", "nothing was created");
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

    @Test
    void the_app_template_targets_the_major_of_the_host_s_default_jdk() throws IOException {
        JdkInventory inventory = new JdkInventory(temp.resolve("jdks"), temp.resolve("jk-jdks.toml"));
        inventory.setDefault(new InstalledJdk("temurin-21.0.7", temp.resolve("jdks/temurin-21.0.7")));
        NewProjectJava.inventory(() -> inventory);
        try {
            String session = initialize("2025-11-25", "{}");
            String created = mcp.handle(
                            rpc(Map.of("template", "app", "name", "hosted", "parentDir", parent.toString())), session)
                    .body();
            assertThat(created).as(created).contains("created");
            assertThat(parent.resolve("hosted/jk.toml")).content().contains("java = 21");
        } finally {
            NewProjectJava.inventory(null);
        }
    }

    @Test
    void a_relative_template_path_resolves_against_the_bound_dir_for_the_form_and_the_scaffold() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work"));
        Path mine = Files.createDirectories(work.resolve("mine.g8/src/main/g8"));
        Files.writeString(work.resolve("mine.g8/default.properties"), "name=demo\n");
        Files.writeString(mine.resolve("jk.toml"), "name = \"$name$\"\nfrom = \"work\"\n");
        String session = initialize("2025-11-25", "{}");

        String form = mcp.handle(rpc(Map.of("action", "form", "template", "mine.g8", "dir", work.toString())), session)
                .body();
        assertThat(form).as(form).contains(work.resolve("mine.g8").toString());

        // No parentDir: the project goes beside the bound dir, and the template is still the bound dir's.
        String created = mcp.handle(
                        rpc(Map.of("template", "mine.g8", "name", "beside", "dir", work.toString())), session)
                .body();
        assertThat(created).as(created).contains("created");
        assertThat(temp.resolve("beside/jk.toml")).content().contains("from = \"work\"");
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
        return initialize(version, capabilities, "fake");
    }

    private String initialize(String version, String capabilities, String client) {
        return requireNonNull(mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                                + version + "\",\"capabilities\":" + capabilities
                                + ",\"clientInfo\":{\"name\":" + Jsonl.quote(client) + ",\"version\":\"1\"}}}",
                        null)
                .openedSessionId());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static McpHandler handler(NewProjectWizard wizard) {
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
        h.wizard(wizard);
        return h;
    }
}
