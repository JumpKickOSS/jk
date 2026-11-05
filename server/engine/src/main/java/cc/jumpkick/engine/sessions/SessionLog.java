// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.EventCatalog;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The engine's sessions: who is talking to it, the episodes {@link EpisodeInference} groups their
 * work into, and every run and action they took. Each change is written to the {@link
 * SessionJournal} and published as an SSE {@code session}, {@code episode} or {@code action} event.
 *
 * <p>A session is keyed by its origin and its label: an MCP connection ({@code mcp}, {@code
 * claude-code 3f9a}) is opened at {@code initialize}; a BSP client, the dashboard or the shell is
 * opened by its first run. Every session ends the same way: at {@code DELETE /mcp}, or once it has
 * had no activity for the session expiry ({@link #sweep}). Its MCP connection is forgotten with it,
 * so a client that comes back after that is a stranger. The idle gap only marks a session idle and
 * splits its episodes. The code graph's own compiles and fixture runs belong to no session.
 *
 * <p>Sessions outlive the engine. A new engine {@link #adopt adopts} every session a previous one
 * left open, with its episodes as they stood and the MCP connection behind it ({@link
 * #saveConnection}), so an agent that returns keeps its session. Only the primary engine writes the
 * records: a successor adopts only once the engine it displaced has {@link #stopWriting stopped
 * writing} ({@link #adoptAfter}), and a displaced engine hands the runs it finishes to its successor
 * instead ({@link #writeWhile}).
 */
public final class SessionLog {

    /** Where the log's events go: the SSE hub's dashboard stream. */
    @FunctionalInterface
    public interface Events {
        void publish(String type, Map<String, Object> payload);
    }

    /** Triggers that run on the engine's own account and never belong to a session. */
    private static final Set<String> SESSIONLESS = Set.of("graph", "optimize", "calibrate", "synthetic");

    /** Actions a session keeps in memory when there is no journal to page them from. */
    static final int MEMORY_ACTIONS = 1000;

    private final @Nullable SessionJournal journal;
    private final LongSupplier clock;
    private final long idleGapMillis;
    private final long expiryMillis;
    private volatile Events events = (type, payload) -> {};
    private volatile BooleanSupplier primary = () -> true;
    private volatile Consumer<FinishedRun> successor = run -> {};
    private volatile Runnable predecessorStopped = () -> {};
    private volatile Consumer<String> connectionEnded = id -> {};

    private final Map<String, Live> byKey = new LinkedHashMap<>();
    private final Map<String, Live> byId = new LinkedHashMap<>();
    private final Map<String, Live> byConnection = new HashMap<>();
    private final Map<String, @Nullable String> projectIds = new HashMap<>();

    /** Whether {@link #adopt} has run. */
    private boolean adopted;

    /** Set once {@link #primary} fails or {@link #stopWriting} runs: the log writes nothing from then on. */
    private boolean retired;

    /** One open session: its record, its episodes, the sequence its next action takes, its MCP connection. */
    private static final class Live {
        SessionRecord record;
        final EpisodeInference episodes;
        long seq;
        final List<ActionRecord> memory = new ArrayList<>();

        @Nullable
        Map<String, Object> connection;

        Live(SessionRecord record, EpisodeInference episodes) {
            this.record = record;
            this.episodes = episodes;
        }
    }

    /**
     * @param journal where sessions are written; {@code null} keeps them in memory, for an engine
     *     whose {@code [history]} is off
     * @param idleGapMillis how long a quiet session takes to go idle and its next fact to start a new
     *     episode ({@link EpisodeInference#DEFAULT_IDLE_GAP_MILLIS})
     * @param expiryMillis how long a session with no activity lasts before it ends ({@code [mcp]
     *     session-expiry})
     */
    public SessionLog(@Nullable SessionJournal journal, LongSupplier clock, long idleGapMillis, long expiryMillis) {
        if (expiryMillis <= 0) throw new IllegalArgumentException("session expiry must be positive: " + expiryMillis);
        this.journal = journal;
        this.clock = clock;
        this.idleGapMillis = idleGapMillis;
        this.expiryMillis = expiryMillis;
    }

    /** Publish every change to {@code events}. */
    public void events(Events events) {
        this.events = events != null ? events : (type, payload) -> {};
    }

    /**
     * Write records only while {@code primary} holds. Once it fails the log writes nothing more and
     * hands each run that finishes here to {@code successor}, the engine that now writes them.
     */
    public void writeWhile(BooleanSupplier primary, Consumer<FinishedRun> successor) {
        this.primary = primary;
        this.successor = successor;
    }

    /**
     * Tell {@code ended} the id of each MCP connection whose session ended, so the connection table
     * forgets it too. Called outside this log's lock.
     */
    public void whenConnectionEnds(Consumer<String> ended) {
        this.connectionEnded = ended != null ? ended : id -> {};
    }

    /**
     * Before adopting, run {@code predecessorStopped}: it returns once the engine this one displaced
     * has stopped writing session records, or has been given up on.
     */
    public void adoptAfter(Runnable predecessorStopped) {
        this.predecessorStopped = predecessorStopped != null ? predecessorStopped : () -> {};
    }

    /**
     * Write nothing more, for good: a successor is about to adopt these sessions. Returns once any
     * write in flight has landed, so the successor's adoption reads it.
     */
    public synchronized void stopWriting() {
        retired = true;
    }

    public long idleGapMillis() {
        return idleGapMillis;
    }

    /** True when runs of {@code trigger} belong to a session. */
    public static boolean ownsTrigger(@Nullable String trigger) {
        return trigger != null
                && !trigger.isBlank()
                && !SESSIONLESS.contains(trigger.trim().toLowerCase(Locale.ROOT));
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /**
     * Take over every stored session a previous engine left open, as it stood: its episodes, its
     * timeline's sequence and its MCP connection. An adopted session stays open under the same
     * expiry as any other ({@link #sweep}). Runs once, and every other entry point runs it first,
     * after the engine this one displaced has stopped writing ({@link #adoptAfter}). Answers how
     * many it adopted.
     */
    public synchronized int adopt() {
        if (adopted) return 0;
        adopted = true;
        if (journal == null) return 0;
        predecessorStopped.run();
        int n = 0;
        for (Map<String, Object> doc : journal.list()) {
            if (!(doc.get("session") instanceof Map<?, ?> s)) continue;
            SessionRecord r = SessionRecord.of(s);
            if (r.id().isBlank() || SessionRecord.ENDED.equals(r.state()) || byId.containsKey(r.id())) continue;
            Map<?, ?> state = doc.get("inference") instanceof Map<?, ?> m ? m : null;
            Live live = new Live(r, EpisodeInference.resume(r.id(), idleGapMillis, episodesOf(doc), state));
            List<Map<String, Object>> actions = journal.actions(r.id());
            live.seq = actions.isEmpty() ? 0 : Episode.num(actions.getLast(), "seq");
            byId.put(r.id(), live);
            Live other = byKey.get(key(r.origin(), r.label()));
            if (other == null || other.record.lastActiveAt() < r.lastActiveAt())
                byKey.put(key(r.origin(), r.label()), live);
            Map<String, Object> connection = journal.connection(r.id()).orElse(null);
            String cid = connection == null ? null : MiniJson.str(connection, "id");
            if (connection != null && cid != null) {
                live.connection = connection;
                byConnection.put(cid, live);
            }
            n++;
        }
        return n;
    }

    /**
     * Open a session for a connection that just introduced itself ({@code initialize}). Answers the
     * session's id; one already open under the same origin and label is answered as it is.
     */
    public synchronized String open(
            String origin, @Nullable String client, @Nullable String label, @Nullable String project) {
        adopt();
        Live live = sessionFor(origin, label, client, clock.getAsLong());
        if (project != null && !project.isBlank() && live.record.project() == null) {
            live.record = withProject(live.record, project, live.record.lastActiveAt());
            persist(live);
        }
        return live.record.id();
    }

    /** The connection behind {@code origin} and {@code label} closed: end its session. */
    public void end(String origin, @Nullable String label) {
        List<String> ended = new ArrayList<>();
        synchronized (this) {
            adopt();
            Live live = byKey.get(key(origin, label));
            if (live != null) end(live, clock.getAsLong(), ended);
        }
        ended.forEach(connectionEnded);
    }

    /**
     * Store the MCP connection behind the session of {@code origin} and {@code label}, as it opened
     * or changed, so an engine that adopts the session answers the connection too. Nothing while no
     * such session is open.
     */
    public synchronized void saveConnection(String origin, @Nullable String label, Map<String, Object> connection) {
        adopt();
        Live live = byKey.get(key(origin, label));
        if (live == null) return;
        Map<String, Object> previous = live.connection;
        String was = previous == null ? null : MiniJson.str(previous, "id");
        if (was != null) byConnection.remove(was, live);
        live.connection = Map.copyOf(connection);
        String id = MiniJson.str(connection, "id");
        if (id != null) byConnection.put(id, live);
        if (journal == null || !writing()) return;
        try {
            journal.writeConnection(live.record.id(), live.connection);
        } catch (IOException | RuntimeException e) {
            Log.debug("session connection not written: " + live.record.id(), e);
        }
    }

    /** The stored MCP connection {@code id} of a session still open; empty when no open session has it. */
    public synchronized Optional<Map<String, Object>> connection(String id) {
        adopt();
        Live live = byConnection.get(id);
        Map<String, Object> connection = live == null ? null : live.connection;
        return Optional.ofNullable(connection);
    }

    /** True when an open session holds MCP connection {@code id}, so a new connection must not take it. */
    public synchronized boolean holdsConnection(String id) {
        adopt();
        return byConnection.containsKey(id);
    }

    /**
     * End every session with no activity for the session expiry, and announce the ones that went
     * idle. Activity is the session's last fact or the last request on its MCP connection, whichever
     * is later. Called on the engine's maintenance tick and before every read.
     */
    public void sweep() {
        List<String> ended = new ArrayList<>();
        synchronized (this) {
            adopt();
            long now = clock.getAsLong();
            for (Live live : List.copyOf(byId.values())) {
                if (now - lastActivity(live) >= expiryMillis) {
                    end(live, now, ended);
                    continue;
                }
                String state = live.record.stateAt(now, idleGapMillis);
                if (state.equals(live.record.state())) continue;
                live.record = live.record.withState(state);
                persist(live);
                publishSession(live, state);
            }
        }
        ended.forEach(connectionEnded);
    }

    /** When {@code live} last acted, or its connection last served a request. */
    private static long lastActivity(Live live) {
        long last = live.record.lastActiveAt();
        Map<String, Object> connection = live.connection;
        if (connection != null && connection.get("lastSeenAt") instanceof Number seen) {
            last = Math.max(last, seen.longValue());
        }
        return last;
    }

    // ---- facts ---------------------------------------------------------------------------------

    /**
     * Place a run admitted at {@code at} in its session's episode, opening the session when it is
     * the origin's first fact. Answers the episode id the run record carries, or {@code null} for a
     * trigger no session owns.
     */
    public synchronized @Nullable String placeRun(
            String trigger, @Nullable String label, String kind, String dir, long at) {
        if (!ownsTrigger(trigger) || dir == null || dir.isBlank()) return null;
        adopt();
        Live live = sessionFor(trigger, label, clientOf(label), at);
        EpisodeInference.Placement p = live.episodes.place(at, projectOr(live, dir));
        touch(live, dir, at, p.episode());
        persist(live);
        publish(live, p.changes());
        return p.episode();
    }

    /** Fold a finished run into the episode its admission placed it in, and log it as the session's action. */
    public void runFinished(BuildRecord r) {
        FinishedRun run = FinishedRun.of(r);
        if (run != null) runFinished(run);
    }

    /**
     * {@link #runFinished(BuildRecord)} for a run as {@link FinishedRun} carries it: one finished
     * here, or one a displaced engine reported. A log that no longer writes hands it to its
     * successor.
     */
    public synchronized void runFinished(FinishedRun r) {
        if (!writing()) {
            successor.accept(r);
            return;
        }
        adopt();
        String episode = r.episode();
        int dot = episode.lastIndexOf('.');
        Live live = dot > 0 ? byId.get(episode.substring(0, dot)) : null;
        if (live == null) return;
        List<EpisodeInference.Change> changes =
                r.cancelled() ? List.of() : live.episodes.fold(episode, RunFacts.fact(r));
        touch(live, r.dir(), r.finishedAt(), live.record.episode());
        ActionRecord action = RunFacts.action(r);
        append(live, action);
        persist(live);
        publish(live, changes);
    }

    /**
     * Log a non-run action of the session behind {@code origin} and {@code label}, placing it in an
     * episode. {@code fact} carries what inference reads; {@code kind}, {@code summary} and {@code
     * headline} are what the timeline shows.
     */
    public synchronized void action(
            String origin,
            @Nullable String label,
            @Nullable String client,
            Fact fact,
            String kind,
            String summary,
            @Nullable String headline) {
        if (!ownsTrigger(origin)) return;
        adopt();
        Live live = sessionFor(origin, label, client, fact.at());
        String project = projectOr(live, fact.project());
        EpisodeInference.Placement p = live.episodes.place(fact.at(), project);
        Fact placed = project.equals(fact.project())
                ? fact
                : new Fact(
                        fact.at(),
                        fact.millis(),
                        project,
                        fact.module(),
                        fact.kind(),
                        fact.ok(),
                        fact.failingTests(),
                        fact.target(),
                        fact.count());
        List<EpisodeInference.Change> changes = new ArrayList<>(p.changes());
        changes.addAll(live.episodes.fold(p.episode(), placed));
        touch(live, project, fact.end(), p.episode());
        ActionRecord action = new ActionRecord(
                0,
                fact.at(),
                kind,
                summary,
                headline,
                fact.ok() ? ActionRecord.OK : ActionRecord.ERROR,
                fact.millis(),
                p.episode(),
                project.isBlank() ? null : project,
                0);
        ActionRecord logged = append(live, action);
        persist(live);
        publish(live, changes);
        Map<String, Object> payload = envelope(EventCatalog.SSE_ACTION);
        payload.put("session", live.record.id());
        payload.put("action", logged.toMap());
        events.publish(EventCatalog.SSE_ACTION, payload);
    }

    /**
     * The agent named its goal: the session's current episode when it already pursues {@code text},
     * else a new episode titled by it. Answers the episode id.
     */
    public synchronized @Nullable String goal(String origin, @Nullable String label, String project, String text) {
        if (!ownsTrigger(origin)) return null;
        adopt();
        long now = clock.getAsLong();
        Live live = sessionFor(origin, label, clientOf(label), now);
        EpisodeInference.Placement p = live.episodes.goal(now, projectOr(live, project), text);
        touch(live, project, now, p.episode());
        persist(live);
        publish(live, p.changes());
        return p.episode();
    }

    // ---- reads ---------------------------------------------------------------------------------

    /** Live and recent sessions, most recently active first, each with its current episode. */
    public List<Map<String, Object>> sessions(int limit) {
        sweep();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        synchronized (this) {
            for (Live live : byId.values()) rows.put(live.record.id(), row(live.record, live.episodes.episodes()));
        }
        if (journal != null) {
            for (Map<String, Object> doc : journal.list()) {
                if (!(doc.get("session") instanceof Map<?, ?> s)) continue;
                SessionRecord r = SessionRecord.of(s);
                if (rows.containsKey(r.id())) continue;
                rows.put(r.id(), row(r, episodesOf(doc)));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> Episode.num(m, "lastActiveAt"))
                .reversed());
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    /** One page of a session's timeline. */
    public record Page(
            Map<String, Object> session,
            List<Map<String, Object>> episodes,
            List<Map<String, Object>> actions,
            long next) {}

    /**
     * Session {@code id} with every episode and up to {@code limit} actions older than {@code
     * before} ({@code 0}: the newest), oldest first. {@link Page#next} is the {@code before} that
     * reads the page older than this one, or {@code 0} when this is the first.
     */
    public Optional<Page> session(String id, long before, int limit) {
        sweep();
        SessionJournal stored = journal;
        SessionRecord record = null;
        List<Episode> episodes = List.of();
        List<ActionRecord> actions = null;
        synchronized (this) {
            Live live = byId.get(id);
            if (live != null) {
                record = live.record;
                episodes = live.episodes.episodes();
                if (stored == null) actions = List.copyOf(live.memory);
            }
        }
        if (record == null) {
            if (stored == null) return Optional.empty();
            Optional<Map<String, Object>> doc = stored.read(id);
            if (doc.isEmpty() || !(doc.get().get("session") instanceof Map<?, ?> s)) return Optional.empty();
            record = SessionRecord.of(s);
            episodes = episodesOf(doc.get());
        }
        if (actions == null) {
            List<ActionRecord> read = new ArrayList<>();
            if (stored != null) for (Map<String, Object> m : stored.actions(id)) read.add(ActionRecord.of(m));
            actions = read;
        }
        int end = actions.size();
        if (before > 0) {
            end = 0;
            while (end < actions.size() && actions.get(end).seq() < before) end++;
        }
        int start = Math.max(0, end - Math.max(1, limit));
        List<Map<String, Object>> page = new ArrayList<>();
        for (ActionRecord a : actions.subList(start, end)) page.add(a.toMap());
        long next = start > 0 ? actions.get(start).seq() : 0;
        List<Map<String, Object>> eps = new ArrayList<>();
        for (Episode e : episodes) eps.add(withProjectId(e).toMap());
        return Optional.of(new Page(sessionMap(record), eps, page, next));
    }

    // ---- internals -----------------------------------------------------------------------------

    private Live sessionFor(String origin, @Nullable String label, @Nullable String client, long at) {
        String key = key(origin, label);
        Live live = byKey.get(key);
        if (live != null) {
            if (!SessionRecord.LIVE.equals(live.record.state())) {
                live.record = live.record.withState(SessionRecord.LIVE);
                publishSession(live, SessionRecord.LIVE);
            }
            return live;
        }
        String id = mintId(origin, label, at);
        SessionRecord record =
                new SessionRecord(id, origin, client, label, null, null, at, at, 0, SessionRecord.LIVE, null);
        live = new Live(record, new EpisodeInference(id, idleGapMillis));
        byKey.put(key, live);
        byId.put(id, live);
        persist(live);
        publishSession(live, "started");
        return live;
    }

    /** End {@code live}, forget its connection here and on disk, and add the connection's id to {@code ended}. */
    private void end(Live live, long now, List<String> ended) {
        List<EpisodeInference.Change> changes = live.episodes.end();
        SessionRecord r = live.record;
        live.record = new SessionRecord(
                r.id(),
                r.origin(),
                r.client(),
                r.label(),
                r.project(),
                r.projectId(),
                r.startedAt(),
                r.lastActiveAt(),
                Math.max(now, r.lastActiveAt()),
                SessionRecord.ENDED,
                r.episode());
        persist(live);
        publish(live, changes);
        publishSession(live, SessionRecord.ENDED);
        byKey.remove(key(r.origin(), r.label()), live);
        byId.remove(r.id());
        String connection = live.connection == null ? null : MiniJson.str(live.connection, "id");
        if (connection == null) return;
        byConnection.remove(connection, live);
        live.connection = null;
        ended.add(connection);
        if (journal == null || !writing()) return;
        try {
            journal.deleteConnection(r.id());
        } catch (IOException | RuntimeException e) {
            Log.debug("session connection not removed: " + r.id(), e);
        }
    }

    private void touch(Live live, @Nullable String dir, long at, @Nullable String episode) {
        SessionRecord r = live.record;
        String project = dir == null || dir.isBlank() ? r.project() : dir;
        long last = Math.max(r.lastActiveAt(), at);
        live.record = new SessionRecord(
                r.id(),
                r.origin(),
                r.client(),
                r.label(),
                project,
                project == null ? null : projectId(project),
                r.startedAt(),
                last,
                r.endedAt(),
                r.state(),
                episode != null ? episode : r.episode());
    }

    private SessionRecord withProject(SessionRecord r, String project, long at) {
        return new SessionRecord(
                r.id(),
                r.origin(),
                r.client(),
                r.label(),
                project,
                projectId(project),
                r.startedAt(),
                at,
                r.endedAt(),
                r.state(),
                r.episode());
    }

    /** {@code dir}, or the session's own project when the fact named none. */
    private static String projectOr(Live live, @Nullable String dir) {
        if (dir != null && !dir.isBlank()) return dir;
        String own = live.record.project();
        return own == null ? "" : own;
    }

    private ActionRecord append(Live live, ActionRecord action) {
        ActionRecord logged = action.redacted().withSeq(++live.seq);
        if (journal == null) {
            live.memory.add(logged);
            if (live.memory.size() > MEMORY_ACTIONS) live.memory.removeFirst();
            return logged;
        }
        if (!writing()) return logged;
        try {
            journal.append(live.record.id(), logged.toMap());
        } catch (IOException | RuntimeException e) {
            Log.debug("session action not written: " + live.record.id(), e);
        }
        return logged;
    }

    private void persist(Live live) {
        if (journal == null || !writing()) return;
        List<Map<String, Object>> eps = new ArrayList<>();
        for (Episode e : live.episodes.episodes()) eps.add(withProjectId(e).toMap());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", 1);
        doc.put("session", live.record.toMap());
        doc.put("episodes", eps);
        doc.put("inference", live.episodes.state());
        try {
            journal.write(live.record.id(), doc);
        } catch (IOException | RuntimeException e) {
            Log.debug("session not written: " + live.record.id(), e);
        }
    }

    /** True while this engine writes the records; false for good once {@link #primary} fails or it is stopped. */
    private boolean writing() {
        if (retired) return false;
        if (primary.getAsBoolean()) return true;
        retired = true;
        return false;
    }

    /**
     * Each episode's changes in one step, folded: an episode started by this step is announced once
     * as it stands after it, never started and then retitled. Then the session's, when an episode
     * started: a started episode is the current one.
     */
    private void publish(Live live, List<EpisodeInference.Change> changes) {
        Map<String, EpisodeInference.Change> folded = new LinkedHashMap<>();
        for (EpisodeInference.Change c : changes) {
            folded.merge(
                    c.episode().id(),
                    c,
                    (a, b) -> new EpisodeInference.Change(
                            b.episode(),
                            a.started() || b.started(),
                            a.titleChanged() || b.titleChanged(),
                            a.outcomeChanged() || b.outcomeChanged()));
        }
        boolean started = false;
        for (EpisodeInference.Change c : folded.values()) {
            if (c.started()) {
                publishEpisode(c.episode(), "started");
                started = true;
                continue;
            }
            if (c.titleChanged()) publishEpisode(c.episode(), "title");
            if (c.outcomeChanged()) publishEpisode(c.episode(), "outcome");
        }
        if (started) publishSession(live, "episode");
    }

    private void publishEpisode(Episode e, String change) {
        Map<String, Object> payload = envelope(EventCatalog.SSE_EPISODE);
        payload.put("change", change);
        payload.put("episode", withProjectId(e).toMap());
        events.publish(EventCatalog.SSE_EPISODE, payload);
    }

    private void publishSession(Live live, String change) {
        Map<String, Object> payload = envelope(EventCatalog.SSE_SESSION);
        payload.put("change", change);
        payload.put("session", sessionMap(live.record));
        events.publish(EventCatalog.SSE_SESSION, payload);
    }

    private static Map<String, Object> envelope(String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", 1);
        payload.put("type", type);
        return payload;
    }

    private Map<String, Object> sessionMap(SessionRecord r) {
        return r.withState(r.stateAt(clock.getAsLong(), idleGapMillis)).toMap();
    }

    private Map<String, Object> row(SessionRecord r, List<Episode> episodes) {
        Map<String, Object> m = sessionMap(r);
        for (Episode e : episodes) {
            if (e.id().equals(r.episode())) m.put("current", withProjectId(e).toMap());
        }
        return m;
    }

    private Episode withProjectId(Episode e) {
        return e.projectId() != null || e.project().isBlank() ? e : e.withProjectId(projectId(e.project()));
    }

    private synchronized @Nullable String projectId(String dir) {
        if (projectIds.containsKey(dir)) return projectIds.get(dir);
        String id;
        try {
            id = ProjectIds.idOf(dir);
        } catch (RuntimeException e) {
            id = null;
        }
        projectIds.put(dir, id);
        return id;
    }

    private static List<Episode> episodesOf(Map<String, Object> doc) {
        List<Episode> out = new ArrayList<>();
        for (Object e : list(doc.get("episodes"))) if (e instanceof Map<?, ?> m) out.add(Episode.of(m));
        return out;
    }

    private static List<?> list(@Nullable Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String key(String origin, @Nullable String label) {
        return origin + "\u0000" + (label == null ? "" : label);
    }

    /** {@code <origin>-<connection id or "shell">-<at, base 36>}, unique among this log's sessions. */
    private String mintId(String origin, @Nullable String label, long at) {
        String tail = "shell";
        if (label != null && !label.isBlank()) {
            String last = label.strip().substring(label.strip().lastIndexOf(' ') + 1);
            String clean = last.replaceAll("[^A-Za-z0-9]", "");
            tail = clean.isEmpty() ? "x" : clean.substring(0, Math.min(12, clean.length()));
        }
        String head = origin.replaceAll("[^A-Za-z0-9]", "");
        String base = (head.isEmpty() ? "s" : head) + "-" + tail + "-" + Long.toString(Math.max(0, at), 36);
        String id = base;
        for (int n = 2;
                byId.containsKey(id) || (journal != null && journal.read(id).isPresent());
                n++) {
            id = base + "-" + n;
        }
        return id;
    }

    /** The client a label names: an MCP or BSP label is {@code <client> <id>}. */
    static @Nullable String clientOf(@Nullable String label) {
        if (label == null || label.isBlank()) return null;
        String s = label.strip();
        int space = s.lastIndexOf(' ');
        return space > 0 ? s.substring(0, space) : null;
    }
}
