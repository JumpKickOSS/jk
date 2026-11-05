// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The session log as the engine drives it: MCP connections, runs and actions in, records and events out. */
class SessionLogTest {

    private static final long T = 1_700_000_000_000L;
    private static final String LABEL = "claude-code 3f9a";

    @TempDir
    Path root;

    private final AtomicLong now = new AtomicLong(T);
    private final List<String> events = new ArrayList<>();
    private SessionJournal journal;
    private SessionLog log;

    @BeforeEach
    void setUp() {
        journal = new SessionJournal(root.resolve("sessions"));
        log = newLog();
    }

    private SessionLog newLog() {
        SessionLog l = new SessionLog(journal, now::get, EpisodeInference.DEFAULT_IDLE_GAP_MILLIS);
        l.events((type, payload) -> events.add(type + ":" + change(payload)));
        return l;
    }

    @Test
    void an_mcp_session_journals_its_episodes_runs_and_actions() throws Exception {
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        callers(T + 1_000);
        String episode = Objects.requireNonNull(log.placeRun("mcp", LABEL, "test", "/ws/app", T + 2_000));
        assertThat(episode).isEqualTo(id + ".1");
        log.runFinished(run(episode, 27, T + 2_000, false));

        Map<String, Object> doc = journal.read(id).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) doc.get("session");
        assertThat(session)
                .containsEntry("origin", "mcp")
                .containsEntry("client", "claude-code")
                .containsEntry("label", LABEL)
                .containsEntry("project", "/ws/app")
                .containsEntry("state", "live")
                .containsEntry("episode", episode)
                .doesNotContainKey("host");
        assertThat(MiniJson.list(doc, "episodes")).singleElement().satisfies(e -> assertThat(e)
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("title", "Fixing QuoteTest#threeNightWeekendStay")
                .containsEntry("titleSource", "inferred")
                .containsEntry("outcome", "working"));
        List<String> lines =
                Files.readAllLines(root.resolve("sessions").resolve(id).resolve("actions.jsonl"));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"kind\":\"callers\"", "\"summary\":\"callers PricingRules.weekendUplift\"");
        assertThat(lines.get(1)).contains("\"kind\":\"run\"", "\"summary\":\"test #27\"", "\"outcome\":\"red\"");
        assertThat(events)
                .containsExactly("session:started", "episode:started", "session:episode", "action:", "episode:title");

        log.end("mcp", LABEL);
        assertThat(events).endsWith("episode:outcome", "session:ended");
        Map<String, Object> ended = log.session(id, 0, 10).orElseThrow().session();
        assertThat(ended).containsEntry("state", "ended");
        assertThat(log.session(id, 0, 10).orElseThrow().episodes())
                .singleElement()
                .satisfies(e -> assertThat(e).containsEntry("outcome", "red"));
    }

    @Test
    void actions_page_newest_first_by_sequence() {
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        for (int i = 0; i < 5; i++) callers(T + i);
        SessionLog.Page newest = log.session(id, 0, 2).orElseThrow();
        assertThat(newest.actions()).extracting(a -> a.get("seq")).containsExactly(4L, 5L);
        assertThat(newest.next()).isEqualTo(4);
        SessionLog.Page older = log.session(id, newest.next(), 2).orElseThrow();
        assertThat(older.actions()).extracting(a -> a.get("seq")).containsExactly(2L, 3L);
        SessionLog.Page first = log.session(id, older.next(), 2).orElseThrow();
        assertThat(first.actions()).extracting(a -> a.get("seq")).containsExactly(1L);
        assertThat(first.next()).isZero();
    }

    @Test
    void a_quiet_connection_goes_idle_and_a_quiet_shell_ends() {
        String mcp = log.open("mcp", "claude-code", LABEL, "/ws/app");
        String shell = sessionOf(log.placeRun("cli", null, "build", "/ws/app", T));
        now.set(T + EpisodeInference.DEFAULT_IDLE_GAP_MILLIS + 1);
        log.sweep();

        assertThat(log.session(mcp, 0, 1).orElseThrow().session()).containsEntry("state", "idle");
        assertThat(log.session(shell, 0, 1).orElseThrow().session()).containsEntry("state", "ended");
        assertThat(events).contains("session:idle", "session:ended");

        callers(now.get());
        assertThat(log.session(mcp, 0, 1).orElseThrow().session()).containsEntry("state", "live");
        assertThat(events).contains("session:live");
        // The shell's next run is another session.
        assertThat(sessionOf(log.placeRun("cli", null, "build", "/ws/app", now.get())))
                .isNotEqualTo(shell);
    }

    @Test
    void a_new_engine_ends_the_sessions_a_dead_one_left_open() {
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        log.placeRun("mcp", LABEL, "test", "/ws/app", T + 1);

        SessionLog next = newLog();
        assertThat(next.endStale()).isEqualTo(1);
        SessionLog.Page page = next.session(id, 0, 10).orElseThrow();
        assertThat(page.session()).containsEntry("state", "ended");
        assertThat(page.episodes()).singleElement().satisfies(e -> assertThat(e).containsEntry("outcome", "abandoned"));
        assertThat(next.endStale()).isZero();
    }

    @Test
    void the_engines_own_runs_belong_to_no_session() {
        assertThat(log.placeRun("graph", null, "compile", "/ws/app", T)).isNull();
        assertThat(log.placeRun("calibrate", null, "build", "/ws/app", T)).isNull();
        assertThat(log.sessions(10)).isEmpty();
    }

    @Test
    void the_list_carries_live_and_ended_sessions_newest_first_with_their_current_episode() {
        String first = log.open("mcp", "claude-code", LABEL, "/ws/app");
        callers(T + 1);
        log.end("mcp", LABEL);
        now.set(T + 5_000);
        String second = log.open("mcp", "cursor", "cursor 9c01", "/ws/lib");
        log.action(
                "mcp",
                "cursor 9c01",
                "cursor",
                Fact.action(T + 5_000, 3, "/ws/lib", Fact.Kind.UPDATE, true, "", 14),
                "update",
                "update 14 pins",
                null);

        List<Map<String, Object>> rows = log.sessions(10);
        assertThat(rows).extracting(r -> r.get("id")).containsExactly(second, first);
        assertThat(rows.getFirst().get("current"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("title", "Updating 14 pins");
        assertThat(rows.get(1)).containsEntry("state", "ended");
        assertThat(log.sessions(1)).hasSize(1);
    }

    @Test
    void without_a_journal_a_live_session_is_kept_in_memory() {
        SessionLog memory = new SessionLog(null, now::get, EpisodeInference.DEFAULT_IDLE_GAP_MILLIS);
        String id = memory.open("mcp", "claude-code", LABEL, "/ws/app");
        memory.action(
                "mcp",
                LABEL,
                "claude-code",
                Fact.action(T, 1, "/ws/app", Fact.Kind.GRAPH, true, "", 0),
                "query",
                "query \"where\"",
                null);
        assertThat(memory.session(id, 0, 10).orElseThrow().actions()).hasSize(1);
        memory.end("mcp", LABEL);
        assertThat(memory.session(id, 0, 10)).isEmpty();
        assertThat(Files.exists(root.resolve("sessions"))).isFalse();
    }

    @Test
    void the_action_event_carries_the_masked_summary_and_a_secret_the_clip_cut_short_is_masked_too(
            @TempDir Path project) throws Exception {
        Files.writeString(project.resolve(".env"), "TOKEN=s3cret-token-value\n");
        String dir = project.toString();
        List<Map<String, Object>> actions = new ArrayList<>();
        log.events((type, payload) -> {
            if (type.equals("action")) actions.add(payload);
        });
        log.open("mcp", "claude-code", LABEL, dir);
        log.action(
                "mcp",
                LABEL,
                "claude-code",
                Fact.action(T, 4, dir, Fact.Kind.GRAPH, true, "", 0),
                "query",
                "query \"where is s3cret-token-value read\"",
                "uses s3cret-tok" + ActionRecord.CLIP);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().get("action"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("summary", "query \"where is *** read\"")
                .containsEntry("headline", "uses ***" + ActionRecord.CLIP);
    }

    private void callers(long at) {
        log.action(
                "mcp",
                LABEL,
                "claude-code",
                Fact.action(at, 4, "/ws/app", Fact.Kind.GRAPH, true, "", 0),
                "callers",
                "callers PricingRules.weekendUplift",
                "3 callers");
    }

    private static String sessionOf(@Nullable String placed) {
        String episode = Objects.requireNonNull(placed);
        return episode.substring(0, episode.lastIndexOf('.'));
    }

    private static String change(Map<String, Object> payload) {
        Object change = payload.get("change");
        return change == null ? "" : change.toString();
    }

    private static BuildRecord run(String episode, long n, long startedAt, boolean success) {
        List<BuildRecord.Diag> diags = success
                ? List.of()
                : List.of(new BuildRecord.Diag(
                        "error",
                        "/ws/app",
                        "test",
                        "junit",
                        "expected 3 nights",
                        "threeNightWeekendStay()",
                        "org.opentest4j.AssertionFailedError",
                        "g:app",
                        "junit-jupiter",
                        "dev.hotel.QuoteTest",
                        "threeNightWeekendStay",
                        ""));
        return new BuildRecord(
                        null,
                        n,
                        BuildRecord.SCHEMA,
                        "test",
                        "/ws/app",
                        "g:app",
                        null,
                        startedAt,
                        startedAt + 900,
                        900,
                        success,
                        false,
                        success ? 0 : 1,
                        "9.9",
                        new BuildRecord.Tests(12, success ? 12 : 11, success ? 0 : 1, 0),
                        List.of(),
                        List.of(),
                        diags,
                        "mcp",
                        LABEL,
                        null,
                        null,
                        false,
                        null,
                        n)
                .withEpisode(episode);
    }
}
