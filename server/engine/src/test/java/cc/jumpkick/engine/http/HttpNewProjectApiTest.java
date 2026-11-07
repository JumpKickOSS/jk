// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.parseObject;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.engine.runtime.NewProjectAgents;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The new-project form over real HTTP: the form with its rev and derived defaults, the preview, the
 * create with template answers and the agent wiring the engine's agents setting asks for, a stale rev's 409 and a misfit answer's 400, from a template on
 * disk. Fixture in {@link HttpEngineServerHarness}.
 */
@Tag("integration")
class HttpNewProjectApiTest extends HttpEngineServerHarness {

    private Path template;
    private Path parent;

    @BeforeEach
    void template() throws IOException {
        template = stateDir.resolve("templates/java/none/asking.g8");
        Files.createDirectories(template.resolve("src/main/g8/src"));
        Files.writeString(template.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "asking"
                layouts = ["traditional"]

                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "postgres"]
                default = "none"

                [[ask]]
                key = "cache"
                label = "Cache"
                kind = "bool"
                default = false
                when = "database == 'postgres'"
                """);
        Files.writeString(template.resolve("default.properties"), "name=demo\n");
        Files.writeString(template.resolve("src/main/g8/jk.toml"), """
                group = "$organization$"
                name = "$name$"
                db = "$database$"
                $if(cache.truthy)$
                cache = true
                $endif$
                """);
        Files.writeString(template.resolve("src/main/g8/src/Main.java"), "class Main {}\n");
        parent = Files.createDirectories(stateDir.resolve("apps"));
    }

    @AfterEach
    void agents() {
        NewProjectAgents.env(null);
    }

    @Test
    void the_form_needs_the_token_and_carries_rev_the_template_s_fields_and_derived_defaults() throws Exception {
        HttpResponse<String> noToken = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/forms/new-project"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(401);

        Map<String, Object> body = form();
        Map<String, Object> form = object(body, "form");
        assertThat(String.valueOf(form.get("rev"))).hasSize(16);
        assertThat(body.get("template")).isEqualTo(template.toString());
        assertThat(object(body, "derived")).containsKeys("git-email", "parent-dir");
        assertThat(MiniJson.write(form))
                .contains("\"key\":\"lang\"", "\"key\":\"name\"", "\"from\":\"parent-dir\"")
                .contains("\"key\":\"database\"", "\"when\":\"database == 'postgres'\"");
    }

    @Test
    void the_preview_lists_the_files_and_jk_toml_and_writes_nothing() throws Exception {
        HttpResponse<String> resp = post("api/projects/preview", body(rev(), answers("postgres", true)));
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        Map<String, Object> preview = parseObject(resp.body());
        assertThat(MiniJson.write(preview.get("files"))).contains("\"jk.toml\"", "\"src/Main.java\"");
        assertThat(String.valueOf(preview.get("manifest")))
                .contains("name = \"widget\"", "db = \"postgres\"", "cache = true", "group = \"org.example\"");
        assertThat(parent.resolve("widget")).doesNotExist();
    }

    @Test
    void a_create_scaffolds_the_template_answers_and_names_the_project() throws Exception {
        HttpResponse<String> resp = post("api/projects", body(rev(), answers("postgres", false)));
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        assertThat(parseObject(resp.body()))
                .containsEntry("path", parent.resolve("widget").toString());
        assertThat(parent.resolve("widget/jk.toml"))
                .content()
                .contains("db = \"postgres\"")
                .doesNotContain("cache = true");

        HttpResponse<String> again = post("api/projects", body(rev(), answers("none", false)));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(again.body()).contains("already exists");
    }

    @Test
    void a_create_wires_the_project_for_the_engine_s_agents_setting_and_says_so() throws Exception {
        NewProjectAgents.env(key -> "JK_AGENTS".equals(key) ? "all" : null);

        HttpResponse<String> preview = post("api/projects/preview", body(rev(), answers("none", false)));
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        assertThat(MiniJson.write(parseObject(preview.body()).get("files")))
                .contains("\"AGENTS.md\"", "\"CLAUDE.md\"", "\".mcp.json\"", "\".claude/settings.json\"");

        HttpResponse<String> resp = post("api/projects", body(rev(), answers("none", false)));
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        Map<String, Object> agents = object(parseObject(resp.body()), "agents");
        assertThat(MiniJson.write(agents.get("files"))).contains("\"CLAUDE.md\"", "\".mcp.json\"");
        assertThat(MiniJson.write(agents.get("notes")))
                .contains(AgentSetup.TRUST_NOTE, "Agents wired: claude, cursor, gemini, opencode, agy, codex");
        Path made = parent.resolve("widget");
        assertThat(made.resolve("AGENTS.md")).isRegularFile();
        assertThat(made.resolve(".claude/settings.json")).isRegularFile();
    }

    @Test
    void with_agents_off_a_create_writes_agents_md_only() throws Exception {
        NewProjectAgents.env(key -> "JK_AGENTS".equals(key) ? "none" : null);

        HttpResponse<String> resp = post("api/projects", body(rev(), answers("none", false)));
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        Path made = parent.resolve("widget");
        assertThat(made.resolve("AGENTS.md")).isRegularFile();
        assertThat(made.resolve("CLAUDE.md")).doesNotExist();
        assertThat(made.resolve(".mcp.json")).doesNotExist();
        assertThat(MiniJson.write(object(parseObject(resp.body()), "agents").get("notes")))
                .contains("Agents wired: none (JK_AGENTS=none)");
    }

    @Test
    void a_stale_rev_is_409_with_the_form_as_it_is_now() throws Exception {
        HttpResponse<String> resp = post("api/projects", body("0123456789abcdef", answers("none", false)));
        assertThat(resp.statusCode()).isEqualTo(409);
        Map<String, Object> body = parseObject(resp.body());
        assertThat(String.valueOf(body.get("error"))).contains("form changed");
        assertThat(object(body, "form").get("rev")).isEqualTo(rev());
        assertThat(parent.resolve("widget")).doesNotExist();
    }

    @Test
    void an_answer_that_does_not_fit_is_400_naming_its_field() throws Exception {
        Map<String, Object> answers = answers("oracle", false);
        answers.put("colour", "red");
        HttpResponse<String> resp = post("api/projects", body(rev(), answers));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body())
                .contains("\"key\":\"database\"", "database must be one of none, postgres")
                .contains("\"key\":\"colour\"");
        assertThat(parent.resolve("widget")).doesNotExist();
    }

    @Test
    void the_template_search_lists_one_language_when_asked() throws Exception {
        HttpResponse<String> resp = get("/api/templates?lang=kotlin");
        assertThat(resp.statusCode()).isEqualTo(200);
        List<?> rows = (List<?>) requireNonNull(MiniJson.parse(resp.body()));
        assertThat(rows).isNotEmpty();
        for (Object row : rows) assertThat(MiniJson.str(row, "language")).isEqualTo("kotlin");
    }

    private Map<String, Object> form() throws Exception {
        HttpResponse<String> resp = get(
                "/api/forms/new-project?template=" + URLEncoder.encode(template.toString(), StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        return parseObject(resp.body());
    }

    private String rev() throws Exception {
        return String.valueOf(object(form(), "form").get("rev"));
    }

    private Map<String, Object> answers(String database, boolean cache) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("lang", "java");
        a.put("template", template.toString());
        a.put("name", "widget");
        a.put("group", "org.example");
        a.put("dir", parent.toString());
        a.put("database", database);
        a.put("cache", cache);
        return a;
    }

    private static String body(String rev, Map<String, Object> answers) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("rev", rev);
        b.put("answers", answers);
        return MiniJson.write(b);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Authorization", "Bearer " + token())
                        .header("X-Jk-Engine-Epoch", SNAPSHOT.engineEpoch())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
