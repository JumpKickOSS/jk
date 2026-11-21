// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.EventCatalog;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The engine's sessions: who is talking to it, the episodes {@link EpisodeInference} groups their
 * work into, and every run and action they took. Each change is written to the {@link
 * SessionJournal} and published as an SSE {@code session}, {@code episode} or {@code action} event.
 *
 * <p>A session is keyed by its origin and its label: an MCP connection ({@code mcp}, {@code
 * claude-code 3f9a}) is opened at {@code initialize}; a BSP client, the dashboard or the shell is
 * opened by its first run. Every session ends the same way: at {@code DELETE /mcp}, or once it has
 * had no activity for the session expiry, or once the process a CLI session names as its {@link
 * #leader} (its terminal's shell, its agent) has exited ({@link #sweep}). Its MCP connection is forgotten with it,
 * so a client that comes back after that is a stranger. The idle gap marks a session idle, settles
 * its open episode and splits its episodes; a session with a run in flight in this engine ({@link
 * #running}) is live however long the run takes. The code graph's own compiles and fixture runs
 * belong to no session.
 *
 * <p>Sessions outlive the engine. A new engine {@link #adopt adopts} every session a previous one
 * left open, with its episodes as they stood and the MCP connection behind it ({@link
 * #saveConnection}), so an agent that returns keeps its session. A session with no connection
 * behind it (the shell, the dashboard, a BSP client) has nothing that can come back to it: unless a
 * live predecessor is still handing its runs over, its open episode is settled as it is adopted.
 * A displaced engine writes none of
 * the records: a successor adopts only once the engine it displaced has {@link #stopWriting stopped
 * writing} ({@link #adoptAfter}), and a displaced engine hands the runs it finishes to its successor
 * instead ({@link #writeWhile}).
 */
public final class SessionLog {

    /** Where the log's events go: the SSE hub's dashboard stream. */
    @FunctionalInterface
    public interface Events {
        void publish(String type, Map<String, Object> payload);
    }

    /**
     * Whether the process a session's leader names ({@code <pid>@<start millis>}) still runs: the
     * terminal's shell, the agent. A start that does not match is another process on a reused pid.
     */
    @FunctionalInterface
    public interface Leaders {
        boolean alive(String leader);
    }

    /** Whether a session has a run in flight in this engine: the job table's answer, not the log's. */
    @FunctionalInterface
    public interface Running {
        boolean has(String origin, @Nullable String label);
    }

    /** Triggers that run on the engine's own account and never belong to a session. */
    private static final Set<String> SESSIONLESS = Set.of("graph", "optimize", "calibrate", "synthetic");

    /** Actions a session keeps: in memory with no journal, and in its {@code actions.jsonl} once compacted. */
    static final int KEPT_ACTIONS = 1000;

    /**
     * Episode lines a session's {@code episodes.jsonl} may reach before it is compacted to one line
     * per kept episode: every change to an episode appends one.
     */
    static final int COMPACT_EPISODE_LINES = 4 * EpisodeInference.KEEP;

    private final @Nullable SessionJournal journal;
    private final LongSupplier clock;
    private final long idleGapMillis;
    private final long expiryMillis;
    private volatile Events events = (type, payload) -> {};
    private volatile BooleanSupplier writes = () -> true;
    private volatile Consumer<FinishedRun> successor = run -> {};
    private volatile BooleanSupplier predecessorStopped = () -> false;
    private volatile Consumer<String> connectionEnded = id -> {};
    private volatile Running running = (origin, label) -> false;
    private volatile Leaders leaders = LeaderProcess::alive;

    private final Map<String, Live> byKey = new LinkedHashMap<>();
    private final Map<String, Live> byId = new LinkedHashMap<>();
    private final Map<String, Live> byConnection = new HashMap<>();
    /**
     * Asked off this log's monitor, by each entry point for the projects its fact names; under the
     * monitor only {@linkplain ProjectIdMemo#known remembered} ids are read, so no fact waits behind
     * a git fork.
     */
    private final ProjectIdMemo projectIds;

    /** Whether {@link #adopt} has run. */
    private boolean adopted;

    /**
     * What {@link #predecessorStopped} answered, or {@code null} while it has not: it is asked on a
     * thread of its own, so the socket wait never holds this log's lock.
     */
    private @Nullable Boolean handingOver;

    /** Whether {@link #predecessorStopped} has been asked. */
    private boolean asked;

    /** Set once {@link #writes} fails or {@link #stopWriting} runs: the log writes nothing from then on. */
    private boolean retired;

    /** One open session: its record, its episodes, the sequence its next action takes, its MCP connection. */
    private static final class Live {
        SessionRecord record;
        final EpisodeInference episodes;
        long seq;
        final List<ActionRecord> memory = new ArrayList<>();

        @Nullable
        Map<String, Object> connection;

        /** The process whose exit ends the session ({@code <pid>@<start millis>}), when a CLI named one. */
        @Nullable
        String leader;

        /** Each kept episode's line as last appended, so only a changed episode is appended again. */
        final Map<String, String> written = new HashMap<>();

        /** Lines in its {@code episodes.jsonl}, superseded ones included. */
        int episodeLines;

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
        this(journal, clock, idleGapMillis, expiryMillis, new ProjectIdMemo());
    }

    /** As the public constructor, asking {@code projectIds} for each project's id. */
    SessionLog(
            @Nullable SessionJournal journal,
            LongSupplier clock,
            long idleGapMillis,
            long expiryMillis,
            ProjectIdMemo projectIds) {
        if (expiryMillis <= 0) throw new IllegalArgumentException("session expiry must be positive: " + expiryMillis);
        this.journal = journal;
        this.clock = clock;
        this.idleGapMillis = idleGapMillis;
        this.expiryMillis = expiryMillis;
        this.projectIds = projectIds;
    }

    /** Publish every change to {@code events}. */
    public void events(Events events) {
        this.events = events != null ? events : (type, payload) -> {};
    }

    /**
     * Ask {@code running} whether a session has a run in flight, so a session stays live, and its
     * episode open, for as long as one of its runs takes. The engine answers from its in-flight
     * builds, which a restarted engine starts empty.
     */
    public void running(Running running) {
        this.running = running != null ? running : (origin, label) -> false;
    }

    /** Ask {@code leaders} whether a session's leader process still runs; the machine's process table by default. */
    public void leaders(Leaders leaders) {
        this.leaders = leaders != null ? leaders : LeaderProcess::alive;
    }

    /**
     * Write records only while {@code writes} holds: the engine is not displaced. Once it fails the
     * log writes nothing more and hands each run that finishes here to {@code successor}, the engine
     * that now writes them.
     */
    public void writeWhile(BooleanSupplier writes, Consumer<FinishedRun> successor) {
        this.writes = writes;
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
     * has stopped writing session records, or has been given up on, and answers true when that
     * engine confirmed it: it is still alive, may still be finishing runs, and hands them over.
     */
    public void adoptAfter(BooleanSupplier predecessorStopped) {
        this.predecessorStopped = predecessorStopped != null ? predecessorStopped : () -> false;
    }

    /**
     * Write nothing more, for good: a successor is about to adopt these sessions. Returns once any
     * write in flight has landed, so the successor's adoption reads it.
     */
    public synchronized void stopWriting() {
        retired = true;
    }

    /** One write to the journal, made under the log's write gate. */
    @FunctionalInterface
    public interface JournalWrite {
        void to(SessionJournal journal) throws IOException;
    }

    /**
     * Run {@code write} while this engine writes the records, once it has adopted, and answer true;
     * answer false, writing nothing, once it no longer does. A write that fails throws, so the caller
     * never takes an unwritten record for a durable one. A log with no journal writes nothing and
     * answers as the gate stands. Records kept beside the sessions (decisions) cross a hand-off
     * under the same one-writer rule this way.
     */
    public synchronized boolean whileWriting(String what, JournalWrite write) throws IOException {
        adopt();
        if (!writing()) return false;
        if (journal == null) return true;
        try {
            write.to(journal);
        } catch (RuntimeException e) {
            throw new IOException("not written: " + what, e);
        }
        return true;
    }

    /** The journal to read records from once this log has adopted; null when nothing is stored. */
    public synchronized @Nullable SessionJournal adoptedJournal() {
        adopt();
        return journal;
    }

    public long idleGapMillis() {
        return idleGapMillis;
    }

    /** How long a session with no activity lasts before it ends ({@code [mcp] session-expiry}). */
    public long expiryMillis() {
        return expiryMillis;
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
        if (journal == null) {
            adopted = true;
            return 0;
        }
        // A live predecessor may still be finishing a run of a session with no connection; the idle
        // sweep settles those once they go quiet. With none, nothing can come back to them.
        boolean handingOver = awaitPredecessor();
        if (adopted) return 0;
        adopted = true;
        int n = 0;
        for (Map<String, Object> doc : journal.list()) {
            if (!(doc.get("session") instanceof Map<?, ?> s)) continue;
            SessionRecord r = SessionRecord.of(s);
            if (r.id().isBlank() || SessionRecord.ENDED.equals(r.state()) || byId.containsKey(r.id())) continue;
            Map<?, ?> state = doc.get("inference") instanceof Map<?, ?> m ? m : null;
            List<Map<String, Object>> lines = journal.episodeLines(r.id());
            List<EpisodeInference.Stored> stored = EpisodeLines.latest(lines);
            Live live = new Live(
                    r,
                    EpisodeInference.resume(r.id(), idleGapMillis, stored, state, EpisodeLines.numberOf(r.episode())));
            live.episodeLines = lines.size();
            live.leader = MiniJson.str(doc, "leader");
            for (EpisodeInference.Stored e : live.episodes.stored())
                live.written.put(e.episode().id(), EpisodeLines.text(e));
            // The record names the newest episode it keeps, or none: an episode compacted away is
            // not a current one, and a page asked for it would find nothing.
            Episode newest = live.episodes.current();
            String keptCurrent = newest == null ? null : newest.id();
            boolean renamed = !Objects.equals(r.episode(), keptCurrent);
            if (renamed) live.record = live.record.withEpisode(keptCurrent);
            List<Map<String, Object>> actions = journal.actions(r.id());
            live.seq = actions.isEmpty() ? 0 : Episode.num(actions.getLast(), "seq");
            if (live.episodeLines > EpisodeInference.KEEP || actions.size() > KEPT_ACTIONS) compact(live, actions);
            else if (renamed) persist(live);
            byId.put(r.id(), live);
            Live other = byKey.get(key(r.origin(), r.label()));
            if (other == null || other.record.lastActiveAt() < r.lastActiveAt())
                byKey.put(key(r.origin(), r.label()), live);
            Map<String, Object> connection = journal.connection(r.id()).orElse(null);
            String cid = connection == null ? null : MiniJson.str(connection, "id");
            if (connection != null && cid != null) {
                live.connection = connection;
                byConnection.put(cid, live);
            } else if (!handingOver) {
                List<EpisodeInference.Change> settled = live.episodes.settle();
                if (!settled.isEmpty()) {
                    persist(live);
                    publish(live, settled);
                }
            }
            n++;
        }
        return n;
    }

    /**
     * Under the lock: what {@link #predecessorStopped} answers, asked once on a virtual thread and
     * waited for with the lock let go, so a {@link #stopWriting} or a status read is not held behind
     * the predecessor's socket. Every entry point waits here until it is known.
     */
    private boolean awaitPredecessor() {
        if (!asked) {
            asked = true;
            SessionContext.startVirtual("jk-sessions-predecessor", () -> {
                boolean confirmed = false;
                try {
                    confirmed = predecessorStopped.getAsBoolean();
                } finally {
                    synchronized (this) {
                        handingOver = confirmed;
                        notifyAll();
                    }
                }
            });
        }
        boolean interrupted = false;
        Boolean answer;
        while ((answer = handingOver) == null) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return answer;
    }

    /**
     * Open a session for a connection that just introduced itself ({@code initialize}). Answers the
     * session's id; one already open under the same origin and label is answered as it is.
     */
    public String open(String origin, @Nullable String client, @Nullable String label, @Nullable String project) {
        resolveIds(origin, label, project);
        synchronized (this) {
            adopt();
            Live live = sessionFor(origin, label, client, clock.getAsLong());
            if (project != null && !project.isBlank() && live.record.project() == null) {
                live.record = withProject(live.record, project, live.record.lastActiveAt());
                persist(live);
            }
            return live.record.id();
        }
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
     * Name the process whose exit ends the open session of {@code origin} and {@code label}: a CLI
     * request's {@code sessionLeader}, its terminal's shell or its agent. {@link #sweep} ends the
     * session once that process is gone. Nothing while no such session is open.
     */
    public synchronized void leader(String origin, @Nullable String label, String leader) {
        if (!ownsTrigger(origin) || leader.isBlank()) return;
        adopt();
        Live live = byKey.get(key(origin, label));
        if (live == null || leader.equals(live.leader)) return;
        live.leader = leader;
        persist(live);
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
                if (now - lastActivity(live) >= expiryMillis || leaderGone(live)) {
                    end(live, now, ended);
                    continue;
                }
                String state = stateOf(live, now);
                List<EpisodeInference.Change> settled =
                        SessionRecord.IDLE.equals(state) ? live.episodes.settle() : List.of();
                boolean moved = !state.equals(live.record.state());
                if (!moved && settled.isEmpty()) continue;
                if (moved) live.record = live.record.withState(state);
                persist(live);
                publish(live, settled);
                if (moved) publishSession(live, state);
            }
        }
        ended.forEach(connectionEnded);
    }

    /** Whether {@code live}'s leader process has exited, with none of its runs still in flight here. */
    private boolean leaderGone(Live live) {
        String leader = live.leader;
        if (leader == null) return false;
        SessionRecord r = live.record;
        return !running.has(r.origin(), r.label()) && !leaders.alive(leader);
    }

    /** {@code live}'s state at {@code now}: live while one of its runs is in flight, however long ago it acted. */
    private String stateOf(Live live, long now) {
        SessionRecord r = live.record;
        if (r.endedAt() <= 0 && running.has(r.origin(), r.label())) return SessionRecord.LIVE;
        return r.stateAt(now, idleGapMillis);
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
    public @Nullable String placeRun(String trigger, @Nullable String label, String kind, String dir, long at) {
        if (!ownsTrigger(trigger) || dir == null || dir.isBlank()) return null;
        resolveIds(trigger, label, dir);
        synchronized (this) {
            adopt();
            Live live = sessionFor(trigger, label, clientOf(label), at);
            EpisodeInference.Placement p = live.episodes.place(at, projectOr(live, dir));
            touch(live, dir, at, p.episode());
            persist(live);
            publish(live, p.changes());
            return p.episode();
        }
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
    public void runFinished(FinishedRun r) {
        String episode = r.episode();
        int dot = episode.lastIndexOf('.');
        String session = dot > 0 ? episode.substring(0, dot) : null;
        String own = null;
        synchronized (this) {
            if (writing()) {
                adopt();
                Live live = session == null ? null : byId.get(session);
                own = live == null ? null : live.record.project();
            }
        }
        projectIds.ask(r.dir(), own);
        synchronized (this) {
            if (!writing()) {
                successor.accept(r);
                return;
            }
            adopt();
            Live live = session == null ? null : byId.get(session);
            if (live == null) return;
            List<EpisodeInference.Change> changes =
                    r.cancelled() ? List.of() : live.episodes.fold(episode, RunFacts.fact(r));
            touch(live, r.dir(), r.finishedAt(), live.record.episode());
            ActionRecord action = RunFacts.action(r);
            append(live, action);
            persist(live);
            publish(live, changes);
        }
    }

    /**
     * Log a non-run action of the session behind {@code origin} and {@code label}, placing it in an
     * episode. {@code fact} carries what inference reads; {@code kind}, {@code summary} and {@code
     * headline} are what the timeline shows.
     */
    public void action(
            String origin,
            @Nullable String label,
            @Nullable String client,
            Fact fact,
            String kind,
            String summary,
            @Nullable String headline) {
        if (!ownsTrigger(origin)) return;
        resolveIds(origin, label, fact.project());
        synchronized (this) {
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
    }

    /**
     * The agent named its goal: the session's current episode when it already pursues {@code text},
     * else a new episode titled by it. The goal is clipped to {@link #GOAL_CHARS} and masked as an
     * action's summary is before anything stores, serves or streams it. Answers the episode id.
     */
    public @Nullable String goal(String origin, @Nullable String label, String project, String text) {
        if (!ownsTrigger(origin)) return null;
        resolveIds(origin, label, project);
        synchronized (this) {
            adopt();
            long now = clock.getAsLong();
            Live live = sessionFor(origin, label, clientOf(label), now);
            String where = projectOr(live, project);
            EpisodeInference.Placement p = live.episodes.goal(now, where, maskedGoal(where, text));
            touch(live, project, now, p.episode());
            persist(live);
            publish(live, p.changes());
            return p.episode();
        }
    }

    /**
     * Off the monitor: ask for the ids of {@code dir} and of the project the open session of {@code
     * origin} and {@code label} already names, so the fact placed under the monitor finds both
     * remembered.
     */
    private void resolveIds(String origin, @Nullable String label, @Nullable String dir) {
        String own;
        synchronized (this) {
            adopt();
            Live live = byKey.get(key(origin, label));
            own = live == null ? null : live.record.project();
        }
        projectIds.ask(dir, own);
    }

    /** Characters of a goal an episode title keeps: a goal is meant to be under eight words. */
    static final int GOAL_CHARS = 120;

    /** {@code text} on one line, clipped to {@link #GOAL_CHARS}, with the project's secrets masked. */
    static String maskedGoal(String project, @Nullable String text) {
        if (text == null || text.isBlank()) return "";
        String dir = project.isBlank() ? null : project;
        String masked = EventRedaction.redactText(dir, text.strip());
        String line = String.join(" ", masked.split("\\s+"));
        if (line.length() <= GOAL_CHARS) return line;
        return EventRedaction.redactClipped(
                dir, line.substring(0, GOAL_CHARS - 1) + ActionRecord.CLIP, ActionRecord.CLIP);
    }

    /** The open session of an origin and label, and its current episode, when one is open. */
    public record Place(String session, @Nullable String episode) {}

    /** Where {@code origin} and {@code label} stand: their open session and its current episode. */
    public synchronized Optional<Place> where(String origin, @Nullable String label) {
        adopt();
        Live live = byKey.get(key(origin, label));
        if (live == null) return Optional.empty();
        Episode current = live.episodes.current();
        return Optional.of(new Place(live.record.id(), current == null ? null : current.id()));
    }

    // ---- reads ---------------------------------------------------------------------------------

    /** Live and recent sessions, most recently active first, each with its current episode. */
    public List<Map<String, Object>> sessions(int limit) {
        return sessions(limit, null);
    }

    /**
     * As {@link #sessions(int)}; with a {@code projectId}, only the sessions that worked on that
     * project (an episode on it, or the session's own project), each also listing its
     * {@code episodes} on it, newest first.
     */
    public List<Map<String, Object>> sessions(int limit, @Nullable String projectId) {
        sweep();
        if (projectId != null) {
            Set<String> dirs = new HashSet<>();
            synchronized (this) {
                for (Live l : byId.values()) {
                    dirs.add(Objects.toString(l.record.project(), ""));
                    for (Episode e : l.episodes.episodes()) dirs.add(e.project());
                }
            }
            dirs.forEach(projectIds::ask);
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        Set<String> live = new HashSet<>();
        synchronized (this) {
            for (Live l : byId.values()) {
                live.add(l.record.id());
                List<Episode> episodes = l.episodes.episodes();
                Map<String, Object> row =
                        onProject(row(l, episodes), l.record, episodes, projectId, projectIds::namedIfKnown);
                if (row != null) rows.put(l.record.id(), row);
            }
        }
        if (journal != null) {
            // Newest first, so only the sessions that can still make the page have their episodes read.
            int kept = 0;
            for (SessionRecord r : StoredSessions.newestFirst(journal)) {
                if (kept >= limit) break;
                if (live.contains(r.id()) || rows.containsKey(r.id())) continue;
                List<Episode> episodes = StoredSessions.episodes(journal, r.id());
                Map<String, Object> row = onProject(row(r, null, episodes), r, episodes, projectId, projectIds::named);
                if (row == null) continue;
                rows.put(r.id(), row);
                kept++;
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
        String state = null;
        List<Episode> episodes = List.of();
        List<ActionRecord> actions = null;
        synchronized (this) {
            Live live = byId.get(id);
            if (live != null) {
                record = live.record;
                state = stateOf(live, clock.getAsLong());
                episodes = live.episodes.episodes();
                if (stored == null) actions = List.copyOf(live.memory);
            }
        }
        if (record == null) {
            if (stored == null) return Optional.empty();
            record = StoredSessions.record(stored, id).orElse(null);
            if (record == null) return Optional.empty();
            episodes = StoredSessions.episodes(stored, id);
        }
        if (actions == null) actions = stored == null ? List.of() : StoredSessions.actions(stored, id);
        StoredSessions.Slice page = StoredSessions.page(actions, before, limit);
        List<Map<String, Object>> eps = new ArrayList<>();
        for (Episode e : episodes.reversed()) {
            if (eps.size() == EpisodeInference.KEEP) break;
            eps.add(projectIds.named(e).toMap());
        }
        return Optional.of(new Page(sessionMap(record, state), eps, page.actions(), page.next()));
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
                projectIds.knownFor(r, project),
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
                projectIds.knownFor(r, project),
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
            if (live.memory.size() > KEPT_ACTIONS) live.memory.removeFirst();
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

    /**
     * Store {@code live}: an episode line for each kept episode that changed since it was last
     * appended, then the head. Once the episode lines pass {@link #COMPACT_EPISODE_LINES}, both logs
     * are compacted to the kept episodes.
     */
    private void persist(Live live) {
        if (journal == null || !writing()) return;
        String id = live.record.id();
        Set<String> kept = new HashSet<>();
        try {
            for (EpisodeInference.Stored e : live.episodes.stored()) {
                String eid = e.episode().id();
                kept.add(eid);
                String text = EpisodeLines.text(e);
                if (text.equals(live.written.get(eid))) continue;
                journal.appendEpisode(id, line(e));
                live.written.put(eid, text);
                live.episodeLines++;
            }
            live.written.keySet().retainAll(kept);
            if (live.episodeLines > COMPACT_EPISODE_LINES) compact(live, null);
            else journal.write(id, head(live));
        } catch (IOException | RuntimeException e) {
            Log.debug("session not written: " + id, e);
        }
    }

    /**
     * Rewrite {@code live}'s logs to what it keeps: one line per kept episode, and the actions of
     * those episodes, at most {@link #KEPT_ACTIONS} of the newest; then its head. {@code actions} is
     * its action log when the caller has already read it.
     */
    private void compact(Live live, @Nullable List<Map<String, Object>> actions) {
        if (journal == null || !writing()) return;
        String id = live.record.id();
        try {
            List<Map<String, Object>> lines = new ArrayList<>();
            Set<String> kept = new HashSet<>();
            for (EpisodeInference.Stored e : live.episodes.stored()) {
                lines.add(line(e));
                kept.add(e.episode().id());
            }
            journal.rewriteEpisodes(id, lines);
            live.episodeLines = lines.size();
            List<Map<String, Object>> keep = new ArrayList<>();
            for (Map<String, Object> a : actions != null ? actions : journal.actions(id)) {
                if (kept.contains(MiniJson.str(a, "episode"))) keep.add(a);
            }
            journal.rewriteActions(id, keep.subList(Math.max(0, keep.size() - KEPT_ACTIONS), keep.size()));
            journal.write(id, head(live));
        } catch (IOException | RuntimeException e) {
            Log.debug("session not compacted: " + id, e);
        }
    }

    /** {@code live}'s {@code session.json}: its record and the inference state its next fact needs. */
    private static Map<String, Object> head(Live live) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", 1);
        doc.put("session", live.record.toMap());
        doc.put("inference", live.episodes.state());
        // Beside the record, never in it: the record is what the dashboard shows.
        if (live.leader != null) doc.put("leader", live.leader);
        return doc;
    }

    /**
     * Under the monitor: one episode line, the episode with its project's id when remembered, and its
     * tallies. The entry point placing a fact asked for its project's id before taking the monitor;
     * a line without one has it added as it is read.
     */
    private Map<String, Object> line(EpisodeInference.Stored e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("episode", projectIds.namedIfKnown(e.episode()).toMap());
        m.put("state", e.state());
        return m;
    }

    /** True while this engine writes the records; false for good once {@link #writes} fails or it is stopped. */
    private boolean writing() {
        if (retired) return false;
        if (writes.getAsBoolean()) return true;
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
        payload.put("episode", projectIds.named(e).toMap());
        events.publish(EventCatalog.SSE_EPISODE, payload);
    }

    private void publishSession(Live live, String change) {
        Map<String, Object> payload = envelope(EventCatalog.SSE_SESSION);
        payload.put("change", change);
        payload.put("session", sessionMap(live.record, stateOf(live, clock.getAsLong())));
        events.publish(EventCatalog.SSE_SESSION, payload);
    }

    private static Map<String, Object> envelope(String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", 1);
        payload.put("type", type);
        return payload;
    }

    /**
     * {@code r} as served, in {@code state}: an open session's state as {@link #stateOf} judges it,
     * or null for a stored one this log does not hold, judged by its record alone.
     */
    private Map<String, Object> sessionMap(SessionRecord r, @Nullable String state) {
        return r.withState(state != null ? state : r.stateAt(clock.getAsLong(), idleGapMillis))
                .toMap();
    }

    private Map<String, Object> row(Live live, List<Episode> episodes) {
        return row(live.record, stateOf(live, clock.getAsLong()), episodes);
    }

    private Map<String, Object> row(SessionRecord r, @Nullable String state, List<Episode> episodes) {
        Map<String, Object> m = sessionMap(r, state);
        for (Episode e : episodes) {
            if (e.id().equals(r.episode())) m.put("current", projectIds.named(e).toMap());
        }
        return m;
    }

    /**
     * {@code row} with {@code episodes}, the session's episodes on project {@code projectId} newest
     * first, or null when it never worked on that project. A null {@code projectId} keeps every row as is.
     */
    private @Nullable Map<String, Object> onProject(
            Map<String, Object> row,
            SessionRecord r,
            List<Episode> episodes,
            @Nullable String projectId,
            UnaryOperator<Episode> ids) {
        if (projectId == null) return row;
        List<Map<String, Object>> on = new ArrayList<>();
        for (Episode e : episodes.reversed()) {
            Episode named = ids.apply(e);
            if (projectId.equals(named.projectId())) on.add(named.toMap());
        }
        if (on.isEmpty() && !projectId.equals(r.projectId())) return null;
        row.put("episodes", on);
        return row;
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
