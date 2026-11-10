// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final long EXPIRY = SessionExpiry.DEFAULT.toMillis();

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
        SessionLog l = new SessionLog(journal, now::get, EpisodeInference.DEFAULT_IDLE_GAP_MILLIS, EXPIRY);
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
    void a_quiet_session_goes_idle_after_the_gap_and_ends_only_at_the_expiry() {
        String mcp = log.open("mcp", "claude-code", LABEL, "/ws/app");
        String shell = sessionOf(log.placeRun("cli", null, "build", "/ws/app", T));
        now.set(T + EpisodeInference.DEFAULT_IDLE_GAP_MILLIS + 1);
        log.sweep();

        assertThat(log.session(mcp, 0, 1).orElseThrow().session()).containsEntry("state", "idle");
        assertThat(log.session(shell, 0, 1).orElseThrow().session()).containsEntry("state", "idle");
        assertThat(events).contains("session:idle").doesNotContain("session:ended");

        callers(now.get());
        assertThat(log.session(mcp, 0, 1).orElseThrow().session()).containsEntry("state", "live");
        assertThat(events).contains("session:live");
        assertThat(sessionOf(log.placeRun("cli", null, "build", "/ws/app", now.get())))
                .as("the shell's run after a quiet gap stays in its session")
                .isEqualTo(shell);

        now.addAndGet(EXPIRY + 60_000);
        log.sweep();
        assertThat(log.session(mcp, 0, 1).orElseThrow().session()).containsEntry("state", "ended");
        assertThat(log.session(shell, 0, 1).orElseThrow().session()).containsEntry("state", "ended");
        assertThat(sessionOf(log.placeRun("cli", null, "build", "/ws/app", now.get())))
                .as("the shell's next run after the expiry is another session")
                .isNotEqualTo(shell);
    }

    @Test
    void a_quiet_sessions_open_episode_settles_at_the_idle_gap_judged_by_its_last_run() {
        String episode = Objects.requireNonNull(log.placeRun("cli", null, "build", "/ws/app", T));
        log.runFinished(run(episode, 27, T, true));
        String shell = sessionOf(episode);
        now.set(T + 900 + EpisodeInference.DEFAULT_IDLE_GAP_MILLIS + 1);
        log.sweep();

        SessionLog.Page page = log.session(shell, 0, 1).orElseThrow();
        assertThat(page.session()).containsEntry("state", "idle");
        assertThat(page.episodes())
                .singleElement()
                .as("nothing works on it any more: its wall stops at its last fact")
                .satisfies(e -> assertThat(e).containsEntry("outcome", "green"));
        assertThat(events).endsWith("episode:outcome", "session:idle");
        assertThat(root.resolve("sessions").resolve(shell).resolve("session.json"))
                .content()
                .contains("\"closed\":\"IDLE\"");

        events.clear();
        log.sweep();
        assertThat(events).as("settled once").isEmpty();
        assertThat(log.placeRun("cli", null, "build", "/ws/app", now.get()))
                .as("the next run starts the next episode")
                .isEqualTo(shell + ".2");
    }

    @Test
    void a_session_with_a_run_in_flight_stays_live_and_working_past_the_idle_gap() {
        AtomicBoolean building = new AtomicBoolean(true);
        log.running((origin, label) -> building.get() && origin.equals("cli") && label == null);
        String episode = Objects.requireNonNull(log.placeRun("cli", null, "build", "/ws/app", T));
        String shell = sessionOf(episode);
        now.set(T + 3 * EpisodeInference.DEFAULT_IDLE_GAP_MILLIS);
        log.sweep();

        SessionLog.Page page = log.session(shell, 0, 1).orElseThrow();
        assertThat(page.session()).containsEntry("state", "live");
        assertThat(page.episodes()).singleElement().satisfies(e -> assertThat(e).containsEntry("outcome", "working"));
        assertThat(log.sessions(10)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("state", "live"));

        building.set(false);
        log.sweep();
        assertThat(log.session(shell, 0, 1).orElseThrow().session())
                .as("its build is gone and it acted long ago")
                .containsEntry("state", "idle");
        assertThat(log.session(shell, 0, 1).orElseThrow().episodes())
                .singleElement()
                .satisfies(e -> assertThat(e).as("nothing judged it").containsEntry("outcome", "abandoned"));
    }

    @Test
    void a_new_engine_settles_the_open_episode_of_a_session_with_no_connection_behind_it() {
        String episode = Objects.requireNonNull(log.placeRun("cli", null, "build", "/ws/app", T));
        log.runFinished(run(episode, 27, T, false));
        String shell = sessionOf(episode);
        assertThat(log.session(shell, 0, 1).orElseThrow().episodes())
                .singleElement()
                .satisfies(e -> assertThat(e).containsEntry("outcome", "working"));

        SessionLog next = newLog();
        events.clear();
        now.set(T + 60_000);
        assertThat(next.adopt()).as("the session itself carries on").isEqualTo(1);
        SessionLog.Page page = next.session(shell, 0, 1).orElseThrow();
        assertThat(page.session()).containsEntry("state", "live");
        assertThat(page.episodes())
                .singleElement()
                .as("no run of the engine that watched it is in flight here")
                .satisfies(e -> assertThat(e).containsEntry("outcome", "red"));
        assertThat(events).containsExactly("episode:outcome");
        assertThat(next.placeRun("cli", null, "build", "/ws/app", T + 61_000))
                .as("the shell's next run is the next episode of the same session")
                .isEqualTo(shell + ".2");
    }

    @Test
    void an_attached_session_ends_after_the_expiry_and_its_connection_is_forgotten() {
        List<String> forgotten = new ArrayList<>();
        log.whenConnectionEnds(forgotten::add);
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        log.saveConnection("mcp", LABEL, Map.of("id", "3f9a", "lastSeenAt", T));
        Path connection = root.resolve("sessions").resolve(id).resolve("connection.json");
        assertThat(connection).exists();
        assertThat(log.connection("3f9a")).as("attached").isPresent();

        // A request on the connection is activity, though it logged no fact.
        now.set(T + EXPIRY - 1);
        log.saveConnection("mcp", LABEL, Map.of("id", "3f9a", "lastSeenAt", T + EXPIRY - 1));
        now.set(T + EXPIRY + 1);
        log.sweep();
        assertThat(log.session(id, 0, 1).orElseThrow().session()).containsEntry("state", "idle");
        assertThat(forgotten).isEmpty();

        now.set(T + 2 * EXPIRY);
        log.sweep();
        assertThat(log.session(id, 0, 1).orElseThrow().session()).containsEntry("state", "ended");
        assertThat(forgotten).containsExactly("3f9a");
        assertThat(log.connection("3f9a")).isEmpty();
        assertThat(log.holdsConnection("3f9a")).isFalse();
        assertThat(connection).as("forgotten on disk too").doesNotExist();
        assertThat(newLog().adopt()).as("nothing left to adopt").isZero();
    }

    @Test
    void a_new_engine_adopts_an_open_session_and_carries_on_its_episode() {
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        log.saveConnection("mcp", LABEL, Map.of("id", "3f9a", "client", "claude-code"));
        String episode = Objects.requireNonNull(log.placeRun("mcp", LABEL, "test", "/ws/app", T + 1_000));
        log.runFinished(run(episode, 27, T + 1_000, false));

        SessionLog next = newLog();
        assertThat(next.adopt()).isEqualTo(1);
        assertThat(next.connection("3f9a"))
                .hasValueSatisfying(c -> assertThat(c).containsEntry("client", "claude-code"));
        assertThat(next.holdsConnection("3f9a")).isTrue();
        now.set(T + 5_000);
        // Same session, same episode: the red streak the first engine saw is the one this green run ends.
        assertThat(next.placeRun("mcp", LABEL, "test", "/ws/app", T + 5_000)).isEqualTo(episode);
        next.runFinished(run(episode, 28, T + 5_000, true));

        SessionLog.Page page = next.session(id, 0, 10).orElseThrow();
        assertThat(page.session()).containsEntry("state", "live");
        assertThat(page.episodes()).singleElement().satisfies(e -> assertThat(e)
                .containsEntry("title", "Fixing QuoteTest#threeNightWeekendStay")
                .containsEntry("outcome", "green")
                .containsEntry("runs", 2));
        assertThat(page.actions()).extracting(a -> a.get("seq")).containsExactly(1L, 2L);
        assertThat(next.sessions(10)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("id", id));
        assertThat(next.adopt()).as("adoption runs once").isZero();
    }

    @Test
    void a_session_a_dead_engine_left_open_is_adopted_survives_the_idle_gap_and_ends_at_the_expiry() {
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        log.saveConnection("mcp", LABEL, Map.of("id", "3f9a"));
        callers(T + 1_000);

        SessionLog next = newLog();
        next.adopt();
        now.set(T + 1_000 + 3 * EpisodeInference.DEFAULT_IDLE_GAP_MILLIS);
        next.sweep();
        assertThat(next.session(id, 0, 1).orElseThrow().session())
                .as("an adopted session nobody returned to outlasts the idle gap")
                .containsEntry("state", "idle");
        assertThat(next.holdsConnection("3f9a")).isTrue();

        now.set(T + 60_000 + EXPIRY);
        next.sweep();
        SessionLog.Page page = next.session(id, 0, 10).orElseThrow();
        assertThat(page.session()).containsEntry("state", "ended");
        assertThat(page.episodes())
                .singleElement()
                .satisfies(e -> assertThat(e).as("judged by its last action").containsEntry("outcome", "green"));
        assertThat(next.connection("3f9a"))
                .as("an ended session's connection is gone")
                .isEmpty();
        assertThat(next.holdsConnection("3f9a")).isFalse();
        assertThat(root.resolve("sessions").resolve(id).resolve("connection.json"))
                .doesNotExist();
    }

    @Test
    void an_adopted_session_whose_connection_returns_goes_idle_and_does_not_end() {
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        log.saveConnection("mcp", LABEL, Map.of("id", "3f9a"));

        SessionLog next = newLog();
        assertThat(next.connection("3f9a")).isPresent();
        now.set(T + EpisodeInference.DEFAULT_IDLE_GAP_MILLIS + 1);
        next.sweep();

        assertThat(next.session(id, 0, 1).orElseThrow().session()).containsEntry("state", "idle");
    }

    @Test
    void a_displaced_log_writes_nothing_and_hands_its_runs_to_the_successor() throws Exception {
        AtomicBoolean primary = new AtomicBoolean(true);
        List<FinishedRun> handed = new ArrayList<>();
        log.writeWhile(primary::get, handed::add);
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        String episode = Objects.requireNonNull(log.placeRun("mcp", LABEL, "test", "/ws/app", T + 1_000));
        Path dir = root.resolve("sessions").resolve(id);
        String before = Files.readString(dir.resolve("session.json"));

        SessionLog successor = newLog();
        // The displaced engine is alive and still finishing the run: adoption leaves its episode open.
        successor.adoptAfter(() -> true);
        successor.adopt();
        primary.set(false);
        log.runFinished(run(episode, 27, T + 1_000, false));
        callers(T + 2_000);
        primary.set(true);
        log.sweep();
        callers(T + 3_000);

        assertThat(Files.readString(dir.resolve("session.json")))
                .as("once displaced, never again")
                .isEqualTo(before);
        assertThat(Files.exists(dir.resolve("actions.jsonl"))).isFalse();
        assertThat(handed).singleElement().satisfies(r -> assertThat(r.episode())
                .isEqualTo(episode));

        successor.runFinished(
                Objects.requireNonNull(FinishedRun.of(handed.getFirst().toMap())));
        SessionLog.Page page = successor.session(id, 0, 10).orElseThrow();
        assertThat(page.episodes()).singleElement().satisfies(e -> assertThat(e)
                .containsEntry("title", "Fixing QuoteTest#threeNightWeekendStay")
                .containsEntry("outcome", "working")
                .containsEntry("runs", 1));
        assertThat(page.actions()).singleElement().satisfies(a -> assertThat(a)
                .containsEntry("summary", "test #27")
                .containsEntry("outcome", "red"));
    }

    /**
     * A write the predecessor had past its check when the successor came to adopt lands first: the
     * successor's adoption waits for the predecessor to stop writing, so it reads that write, and its
     * own next write carries it on rather than overwriting it.
     */
    @Test
    void a_successor_adopts_only_after_the_predecessors_write_in_flight_has_landed() throws Exception {
        String id = log.open("mcp", "claude-code", LABEL, "/ws/app");
        String episode = Objects.requireNonNull(log.placeRun("mcp", LABEL, "test", "/ws/app", T + 1_000));
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        log.writeWhile(
                () -> {
                    if (first.getAndSet(false)) {
                        checking.countDown();
                        await(held);
                    }
                    return true;
                },
                r -> {});
        Thread writer = Thread.ofPlatform().start(() -> log.runFinished(run(episode, 27, T + 1_000, false)));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();

        SessionLog successor = newLog();
        successor.adoptAfter(() -> {
            log.stopWriting();
            return true;
        });
        Thread adopting = Thread.ofPlatform().start(successor::adopt);
        adopting.join(Duration.ofMillis(300));
        assertThat(adopting.isAlive())
                .as("adoption waits for the write in flight")
                .isTrue();
        held.countDown();
        assertThat(adopting.join(Duration.ofSeconds(10))).isTrue();
        assertThat(writer.join(Duration.ofSeconds(10))).isTrue();

        now.set(T + 5_000);
        successor.runFinished(run(episode, 28, T + 5_000, true));
        log.runFinished(run(episode, 29, T + 6_000, true));

        SessionLog.Page page = successor.session(id, 0, 10).orElseThrow();
        assertThat(page.actions())
                .as("the predecessor's last write, then the successor's, and nothing after the hand-off")
                .extracting(a -> a.get("summary"))
                .containsExactly("test #27", "test #28");
        assertThat(page.episodes())
                .singleElement()
                .satisfies(e -> assertThat(e).containsEntry("runs", 2).containsEntry("outcome", "green"));
        assertThat(MiniJson.list(journal.read(id).orElseThrow(), "episodes"))
                .as("the record on disk counts both runs")
                .singleElement()
                .satisfies(e -> assertThat(Episode.num((Map<?, ?>) e, "runs")).isEqualTo(2L));
        assertThat(Files.readAllLines(root.resolve("sessions").resolve(id).resolve("actions.jsonl")))
                .extracting(line -> ActionRecord.of((Map<?, ?>) Objects.requireNonNull(MiniJson.parse(line)))
                        .seq())
                .containsExactly(1L, 2L);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        SessionLog memory = new SessionLog(null, now::get, EpisodeInference.DEFAULT_IDLE_GAP_MILLIS, EXPIRY);
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
    void a_secret_in_a_goal_is_masked_before_it_is_stored_served_or_streamed(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve(".env"), "TOKEN=s3cret-goal-value\n");
        String dir = project.toString();
        List<Map<String, Object>> streamed = new ArrayList<>();
        log.events((type, payload) -> {
            if (type.equals("episode")) streamed.add(payload);
        });
        String id = log.open("mcp", "claude-code", LABEL, dir);

        log.goal("mcp", LABEL, dir, "Rotate  s3cret-goal-value\nout of the config");

        String masked = "Rotate *** out of the config";
        assertThat(streamed).singleElement().satisfies(p -> assertThat(p.get("episode"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("title", masked)
                .containsEntry("titleSource", "goal"));
        assertThat(log.session(id, 0, 10).orElseThrow().episodes())
                .singleElement()
                .satisfies(e -> assertThat(e).containsEntry("title", masked));
        assertThat(log.sessions(10).getFirst().get("current"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("title", masked);
        try (var files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(f)).as("%s", f).doesNotContain("s3cret-goal-value");
            }
        }
        // The same goal again, as the agent repeats it, is the same episode.
        log.goal("mcp", LABEL, dir, "Rotate s3cret-goal-value out of the config");
        assertThat(log.session(id, 0, 10).orElseThrow().episodes()).hasSize(1);
    }

    @Test
    void a_long_goal_is_clipped_to_one_line() {
        String goal = SessionLog.maskedGoal("", "word ".repeat(60));
        assertThat(goal)
                .hasSize(SessionLog.GOAL_CHARS)
                .endsWith(ActionRecord.CLIP)
                .doesNotContain("\n");
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
