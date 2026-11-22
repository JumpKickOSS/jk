// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectIdCache;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.engine.savings.AgentUsage;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.Fact;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.jsonl.MiniJson;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code GET /api/sessions} and {@code /api/sessions/<id>}: bearer-gated, listed, paged. */
class HttpSessionsApiTest extends HttpEngineServerHarness {

    private static final String LABEL = "claude-code 3f9a";

    private final long[] now = {1_700_000_000_000L};
    private SessionLog log;
    private String id;

    @TempDir
    Path claude;

    @BeforeEach
    void sessions() {
        log = new SessionLog(
                SessionJournal.under(testJournal().buildsRoot()),
                () -> now[0],
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
                SessionExpiry.DEFAULT.toMillis());
        server.setSessions(log, new AgentUsage(claude, null, () -> true));
        id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        for (int i = 0; i < 3; i++) {
            log.action(
                    "mcp",
                    LABEL,
                    "claude-code",
                    Fact.action(now[0] + i, 2, "/ws/app", Fact.Kind.GRAPH, true, "", 0),
                    "query",
                    "query \"where is the weekend uplift applied\"",
                    "PricingRules.weekendUplift",
                    0);
        }
    }

    @Test
    void the_list_names_each_session_with_its_current_episode() throws Exception {
        HttpResponse<String> res = get("/api/sessions");
        assertThat(res.statusCode()).isEqualTo(200);
        List<?> sessions = MiniJson.list(MiniJson.parse(res.body()), "sessions");
        assertThat(sessions).hasSize(1);
        Map<?, ?> session = (Map<?, ?>) sessions.getFirst();
        assertThat(session.get("id")).isEqualTo(id);
        assertThat(session.get("label")).isEqualTo(LABEL);
        assertThat(session.get("state")).isEqualTo("live");
        assertThat(session.containsKey("host")).isFalse();
        assertThat(MiniJson.str(session.get("current"), "title")).isEqualTo("Exploring the code");
    }

    @Test
    void one_session_pages_its_actions_by_sequence() throws Exception {
        HttpResponse<String> res = get("/api/sessions/" + id + "?limit=2");
        assertThat(res.statusCode()).isEqualTo(200);
        Object body = requireNonNull(MiniJson.parse(res.body()));
        assertThat(MiniJson.str(MiniJson.get(body, "session"), "id")).isEqualTo(id);
        assertThat(MiniJson.list(body, "episodes")).hasSize(1);
        assertThat(MiniJson.list(body, "actions"))
                .extracting(a -> (Object) ((Map<?, ?>) a).get("seq"))
                .containsExactly(2L, 3L);
        Object saved = MiniJson.get(MiniJson.list(body, "episodes").getFirst(), "saved");
        assertThat(MiniJson.str(saved, "scope")).isEqualTo("episode");
        assertThat(((Map<?, ?>) requireNonNull(saved)).get("graphCalls"))
                .as("the receipt counts every action of the episode, not only the page's")
                .isEqualTo(3L);
        assertThat(MiniJson.get(body, "next")).isEqualTo(2L);

        Object older = requireNonNull(
                MiniJson.parse(get("/api/sessions/" + id + "?before=2&limit=2").body()));
        assertThat(MiniJson.list(older, "actions")).hasSize(1);
        assertThat(MiniJson.get(older, "next")).isNull();
    }

    @Test
    void an_episode_carries_the_tokens_its_agent_counted_and_no_text_of_its_session_file() throws Exception {
        String secret = "sk-ant-SECRET-77aa";
        Path transcript = claude.resolve("projects")
                .resolve(AgentUsage.claudeDir("/ws/app"))
                .resolve("s.jsonl");
        Files.createDirectories(transcript.getParent());
        String line = "{\"type\":\"assistant\",\"timestamp\":\"" + Instant.ofEpochMilli(now[0] + 1)
                + "\",\"message\":{\"id\":\"m1\",\"model\":\"claude-opus-5-5\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"" + secret + "\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":30}}}";
        Files.writeString(transcript, line + "\n");

        String raw = get("/api/sessions/" + id).body();

        Object saved =
                MiniJson.get(MiniJson.list(MiniJson.parse(raw), "episodes").getFirst(), "saved");
        Object tokens = MiniJson.get(saved, "tokens");
        assertThat(MiniJson.str(tokens, "agent")).isEqualTo("Claude Code");
        assertThat(MiniJson.get(tokens, "total")).isEqualTo(42L);
        assertThat(raw).doesNotContain(secret);
    }

    @Test
    void a_secret_in_a_summary_is_masked_on_disk_and_in_the_api_as_a_run_record_masks_it(@TempDir Path project)
            throws Exception {
        Files.writeString(project.resolve(".env"), "TOKEN=s3cret-in-a-question\n");
        String dir = project.toString();
        String summary = "query \"who reads s3cret-in-a-question\"";
        log.action(
                "mcp",
                LABEL,
                "claude-code",
                Fact.action(now[0] + 10, 2, dir, Fact.Kind.GRAPH, true, "", 0),
                "query",
                summary,
                "s3cret-in-a-question is read by Config.token",
                0);

        String masked = EventRedaction.redactText(dir, summary);
        assertThat(masked).isEqualTo("query \"who reads " + SecretRedactor.MASK + "\"");
        Object body = requireNonNull(MiniJson.parse(get("/api/sessions/" + id).body()));
        Map<?, ?> last = (Map<?, ?>) MiniJson.list(body, "actions").getLast();
        assertThat(last.get("summary")).isEqualTo(masked);
        assertThat(last.get("headline")).isEqualTo(SecretRedactor.MASK + " is read by Config.token");
        try (Stream<Path> files = Files.walk(testJournal().buildsRoot())) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(f)).as("%s", f).doesNotContain("s3cret-in-a-question");
            }
        }
    }

    @Test
    void a_project_lists_only_the_sessions_that_worked_on_it_each_with_its_episodes_there(
            @TempDir Path hotel, @TempDir Path shop) throws Exception {
        for (Path dir : List.of(hotel, shop)) {
            Files.writeString(
                    dir.resolve("jk.toml"), "group = \"g\"\nname = \"" + dir.getFileName() + "\"\nversion = \"1\"\n");
        }
        String hotelId = ProjectIdCache.idOf(hotel.toString());
        String shopId = ProjectIdCache.idOf(shop.toString());
        assertThat(hotelId).isNotNull().isNotEqualTo(shopId);
        String agent = log.open("mcp", "claude-code", "claude-code aaaa", hotel.toString());
        act("claude-code aaaa", 10, hotel);
        // A session that moved on: its current episode and own project are the shop's now.
        String moved = log.open("mcp", "claude-code", "claude-code bbbb", hotel.toString());
        act("claude-code bbbb", 20, hotel);
        act("claude-code bbbb", 30, shop);

        List<?> onHotel = MiniJson.list(
                MiniJson.parse(get("/api/sessions?project=" + hotelId).body()), "sessions");
        assertThat(onHotel).extracting(r -> (Object) ((Map<?, ?>) r).get("id")).containsExactly(moved, agent);
        Map<?, ?> movedRow = (Map<?, ?>) onHotel.getFirst();
        assertThat(MiniJson.list(movedRow, "episodes"))
                .as("only its episode on the hotel")
                .singleElement()
                .satisfies(e -> assertThat(((Map<?, ?>) e).get("projectId")).isEqualTo(hotelId));
        assertThat(MiniJson.str(movedRow.get("current"), "projectId")).isEqualTo(shopId);

        List<?> onShop = MiniJson.list(
                MiniJson.parse(get("/api/sessions?project=" + shopId).body()), "sessions");
        assertThat(onShop).extracting(r -> (Object) ((Map<?, ?>) r).get("id")).containsExactly(moved);
        List<?> all = MiniJson.list(MiniJson.parse(get("/api/sessions").body()), "sessions");
        assertThat(all).as("no filter, every session as before").hasSize(3);
        assertThat(all).allSatisfy(r -> assertThat(((Map<?, ?>) r).containsKey("episodes"))
                .isFalse());
        assertThat(get("/api/sessions?project=nobody").body()).isEqualTo("{\"sessions\":[]}");
    }

    /** A graph question from the session labelled {@code label}, {@code offset} ms on, in {@code dir}. */
    private void act(String label, long offset, Path dir) {
        log.action(
                "mcp",
                label,
                "claude-code",
                Fact.action(now[0] + offset, 2, dir.toString(), Fact.Kind.GRAPH, true, "", 0),
                "query",
                "query \"rates\"",
                "Rates.of",
                0);
    }

    @Test
    void an_unknown_or_malformed_id_is_refused_and_every_route_wants_the_bearer() throws Exception {
        assertThat(get("/api/sessions/mcp-0000-zz").statusCode()).isEqualTo(404);
        assertThat(get("/api/sessions/bad%20id").statusCode()).isEqualTo(400);
        assertThat(get("/api/sessions", "Authorization", "Bearer wrong").statusCode())
                .isEqualTo(401);
        assertThat(get("/api/sessions/" + id, "Authorization", "Bearer wrong").statusCode())
                .isEqualTo(401);
    }
}
