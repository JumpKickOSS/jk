// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One session's episodes, inferred from its facts in time order. Pure: no clock, no disk, no
 * events; {@link SessionLog} drives it and persists what it answers.
 *
 * <p>A fact is {@link #place placed} when it starts and {@link #fold folded} when it finishes, so
 * a run belongs to the episode it was admitted into even when it outlives it. A new episode starts
 * when a fact arrives and:
 *
 * <ul>
 *   <li>the session has none yet;
 *   <li>its project is not the current episode's;
 *   <li>the session has been quiet longer than the idle gap; or
 *   <li>the current episode is closed: a red streak of runs ended green, or a {@link #goal} took it.
 * </ul>
 *
 * <p>The title is the agent's goal when it gave one, else built from the episode's facts in a fixed
 * order: {@code Fixing Class#method (+N more)} for the first red run's failing tests, {@code Adding
 * g:a} for a dependency change, {@code Updating N pins}, {@code Renaming T.m}, {@code Exploring the
 * code} when it only asked the code graph, and {@code Working in <module>} otherwise.
 */
public final class EpisodeInference {

    /** How long a session may go without a fact before its next one starts a new episode. */
    public static final long DEFAULT_IDLE_GAP_MILLIS = Duration.ofMinutes(10).toMillis();

    private final String session;
    private final long idleGapMillis;
    private final List<Draft> episodes = new ArrayList<>();
    private long lastAt;

    public EpisodeInference(String session, long idleGapMillis) {
        this.session = Objects.requireNonNull(session, "session");
        this.idleGapMillis = idleGapMillis;
    }

    /**
     * What one step did to an episode: its state after the step, and whether the step started it,
     * changed its title, or changed its outcome.
     */
    public record Change(Episode episode, boolean started, boolean titleChanged, boolean outcomeChanged) {}

    /** The episode a fact belongs to, and every episode change placing it caused. */
    public record Placement(String episode, List<Change> changes) {}

    /** The episodes so far, oldest first. */
    public List<Episode> episodes() {
        List<Episode> out = new ArrayList<>(episodes.size());
        for (Draft d : episodes) out.add(d.view());
        return out;
    }

    /** The episode the next fact joins unless a boundary falls, or null before the first fact. */
    public @Nullable Episode current() {
        return episodes.isEmpty() ? null : episodes.getLast().view();
    }

    /** When the session's last fact finished; 0 before its first. */
    public long lastAt() {
        return lastAt;
    }

    /** Place a fact that starts at {@code at} in {@code project}, opening a new episode when a boundary falls. */
    public Placement place(long at, String project) {
        String where = project == null ? "" : project;
        Draft cur = episodes.isEmpty() ? null : episodes.getLast();
        List<Change> changes = new ArrayList<>();
        boolean boundary =
                cur == null || cur.closed != null || !cur.project.equals(where) || at - lastAt > idleGapMillis;
        Draft placed;
        if (cur == null || boundary) {
            if (cur != null && cur.closed == null) changes.add(cur.close(Closed.SUPERSEDED));
            placed = open(at, where, null);
            changes.add(new Change(placed.view(), true, false, false));
        } else {
            placed = cur;
        }
        touch(placed, at);
        return new Placement(placed.id, changes);
    }

    /**
     * Fold a finished fact into the episode it was placed in. An episode this inference does not
     * hold changes nothing.
     */
    public List<Change> fold(String episodeId, Fact fact) {
        Draft d = find(episodeId);
        if (d == null) return List.of();
        String title = d.title();
        String outcome = d.outcome();
        d.apply(fact);
        touch(d, fact.end());
        boolean retitled = !title.equals(d.title());
        boolean settled = !outcome.equals(d.outcome());
        return retitled || settled ? List.of(new Change(d.view(), false, retitled, settled)) : List.of();
    }

    /**
     * The agent named its goal: the current episode when it already pursues {@code text}, else a new
     * one titled by it that closes the current. A blank goal changes nothing.
     */
    public Placement goal(long at, String project, String text) {
        Draft cur = episodes.isEmpty() ? null : episodes.getLast();
        if (text == null || text.isBlank()) {
            return cur == null ? place(at, project) : new Placement(cur.id, List.of());
        }
        String goal = text.strip();
        if (cur != null && cur.closed == null && goal.equals(cur.goal)) {
            touch(cur, at);
            return new Placement(cur.id, List.of());
        }
        List<Change> changes = new ArrayList<>();
        if (cur != null && cur.closed == null) changes.add(cur.close(Closed.SUPERSEDED));
        Draft d = open(at, project == null ? "" : project, goal);
        changes.add(new Change(d.view(), true, false, false));
        touch(d, at);
        return new Placement(d.id, changes);
    }

    /** The session ended: close its open episode. */
    public List<Change> end() {
        Draft cur = episodes.isEmpty() ? null : episodes.getLast();
        if (cur == null || cur.closed != null) return List.of();
        return List.of(cur.close(Closed.ENDED));
    }

    /**
     * What {@link #episodes()} does not carry and the next fact needs: when the last fact finished,
     * and each episode's running tallies, oldest first. Stored beside the episodes, and read back by
     * {@link #resume}.
     */
    public Map<String, Object> state() {
        List<Object> drafts = new ArrayList<>(episodes.size());
        for (Draft d : episodes) drafts.add(d.state());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastAt", lastAt);
        m.put("episodes", drafts);
        return m;
    }

    /**
     * The inference a previous engine left: {@code stored} as {@link #episodes()} answered it, and
     * {@code state} as {@link #state()} did. An episode with no state keeps its outcome and starts
     * its tallies over.
     */
    public static EpisodeInference resume(
            String session, long idleGapMillis, List<Episode> stored, @Nullable Map<?, ?> state) {
        EpisodeInference inference = new EpisodeInference(session, idleGapMillis);
        List<?> drafts = state != null && state.get("episodes") instanceof List<?> l ? l : List.of();
        long last = 0;
        for (int i = 0; i < stored.size(); i++) {
            Episode e = stored.get(i);
            String goal = Episode.GOAL.equals(e.titleSource()) ? e.title() : null;
            Draft d = inference.new Draft(e.id(), e.number(), e.project(), e.startedAt(), goal);
            d.lastAt = e.lastAt();
            d.runs = e.runs();
            d.actions = e.actions();
            d.restore(i < drafts.size() && drafts.get(i) instanceof Map<?, ?> m ? m : null, e.outcome());
            inference.episodes.add(d);
            last = Math.max(last, e.lastAt());
        }
        inference.lastAt = state != null && state.get("lastAt") instanceof Number n ? n.longValue() : last;
        return inference;
    }

    /** Place and fold each fact in order, as a session that did exactly these would: the fixture seam. */
    public static List<Episode> replay(String session, long idleGapMillis, List<Fact> facts) {
        EpisodeInference inference = new EpisodeInference(session, idleGapMillis);
        for (Fact f : facts) inference.fold(inference.place(f.at(), f.project()).episode(), f);
        return inference.episodes();
    }

    private Draft open(long at, String project, @Nullable String goal) {
        Draft d = new Draft(session + "." + (episodes.size() + 1), episodes.size() + 1, project, at, goal);
        episodes.add(d);
        return d;
    }

    private void touch(Draft d, long at) {
        if (at > lastAt) lastAt = at;
        if (at > d.lastAt) d.lastAt = at;
    }

    private @Nullable Draft find(String id) {
        for (int i = episodes.size() - 1; i >= 0; i--) {
            if (episodes.get(i).id.equals(id)) return episodes.get(i);
        }
        return null;
    }

    /** Why an episode closed. */
    private enum Closed {
        /** A red streak of runs ended green. */
        GREEN_STREAK,
        /** A later fact or goal started the next episode. */
        SUPERSEDED,
        /** The session ended. */
        ENDED
    }

    /** One episode as it accumulates. */
    private final class Draft {
        final String id;
        final int number;
        final String project;
        final long startedAt;
        final @Nullable String goal;
        long lastAt;

        @Nullable
        Closed closed;

        int runs;
        int actions;
        int redStreak;

        @Nullable
        Boolean lastRunOk;

        @Nullable
        Boolean lastActionOk;

        boolean onlyGraph = true;

        @Nullable
        List<String> failing;

        Fact.@Nullable Kind depsVerb;
        String depsTarget = "";
        int depsCount;
        int updatePins = -1;
        String renameTarget = "";
        String module;

        Draft(String id, int number, String project, long startedAt, @Nullable String goal) {
            this.id = id;
            this.number = number;
            this.project = project;
            this.startedAt = startedAt;
            this.lastAt = startedAt;
            this.goal = goal;
            this.module = baseName(project);
        }

        void apply(Fact f) {
            if (f.kind() == Fact.Kind.RUN) {
                runs++;
                lastRunOk = f.ok();
                onlyGraph = false;
                if (!f.module().isBlank()) module = f.module();
                if (!f.ok()) {
                    redStreak++;
                    if (failing == null && !f.failingTests().isEmpty()) {
                        failing = List.copyOf(new LinkedHashSet<>(f.failingTests()));
                    }
                } else {
                    if (redStreak > 0 && closed == null) closed = Closed.GREEN_STREAK;
                    redStreak = 0;
                }
                return;
            }
            actions++;
            lastActionOk = f.ok();
            if (f.kind() == Fact.Kind.GRAPH) return;
            onlyGraph = false;
            switch (f.kind()) {
                case ADD, REMOVE, PIN -> {
                    if (depsVerb == null && !f.target().isBlank()) {
                        depsVerb = f.kind();
                        depsTarget = f.target();
                    }
                    if (depsVerb != null) depsCount += Math.max(1, f.count());
                }
                case UPDATE -> updatePins = Math.max(0, f.count());
                case RENAME -> {
                    if (renameTarget.isEmpty()) renameTarget = f.target();
                }
                default -> {}
            }
        }

        Map<String, Object> state() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (closed != null) m.put("closed", closed.name());
            m.put("redStreak", redStreak);
            if (lastRunOk != null) m.put("lastRunOk", lastRunOk);
            if (lastActionOk != null) m.put("lastActionOk", lastActionOk);
            m.put("onlyGraph", onlyGraph);
            if (failing != null) m.put("failing", failing);
            if (depsVerb != null) m.put("depsVerb", depsVerb.name());
            m.put("depsTarget", depsTarget);
            m.put("depsCount", depsCount);
            m.put("updatePins", updatePins);
            m.put("renameTarget", renameTarget);
            m.put("module", module);
            return m;
        }

        void restore(@Nullable Map<?, ?> m, String outcome) {
            if (m == null) {
                if (!Episode.WORKING.equals(outcome)) {
                    closed = Closed.SUPERSEDED;
                    if (Episode.GREEN.equals(outcome)) lastRunOk = true;
                    if (Episode.RED.equals(outcome)) lastRunOk = false;
                }
                onlyGraph = runs == 0;
                return;
            }
            closed = m.get("closed") instanceof String c ? enumOf(Closed.class, c) : null;
            redStreak = (int) Episode.num(m, "redStreak");
            lastRunOk = m.get("lastRunOk") instanceof Boolean b ? b : null;
            lastActionOk = m.get("lastActionOk") instanceof Boolean b ? b : null;
            onlyGraph = !Boolean.FALSE.equals(m.get("onlyGraph"));
            if (m.get("failing") instanceof List<?> l) {
                List<String> tests = new ArrayList<>();
                for (Object o : l) if (o != null) tests.add(o.toString());
                failing = List.copyOf(tests);
            }
            depsVerb = m.get("depsVerb") instanceof String v ? enumOf(Fact.Kind.class, v) : null;
            depsTarget = Episode.text(m, "depsTarget");
            depsCount = (int) Episode.num(m, "depsCount");
            updatePins = m.get("updatePins") instanceof Number n ? n.intValue() : -1;
            renameTarget = Episode.text(m, "renameTarget");
            String stored = Episode.text(m, "module");
            if (!stored.isEmpty()) module = stored;
        }

        Change close(Closed why) {
            String outcome = outcome();
            closed = why;
            return new Change(view(), false, false, !outcome.equals(outcome()));
        }

        String title() {
            if (goal != null) return goal;
            if (failing != null) return "Fixing " + failing.getFirst() + more(failing.size());
            if (depsVerb != null) return verb(depsVerb) + " " + depsTarget + more(depsCount);
            if (updatePins >= 0) return "Updating " + updatePins + (updatePins == 1 ? " pin" : " pins");
            if (!renameTarget.isEmpty()) return "Renaming " + renameTarget;
            if (onlyGraph && actions > 0) return "Exploring the code";
            return "Working in " + module;
        }

        String outcome() {
            if (closed == null) return Episode.WORKING;
            if (closed == Closed.GREEN_STREAK) return Episode.GREEN;
            Boolean judged = lastRunOk != null ? lastRunOk : lastActionOk;
            if (judged == null) return Episode.ABANDONED;
            return judged ? Episode.GREEN : Episode.RED;
        }

        Episode view() {
            return new Episode(
                    id,
                    session,
                    number,
                    project,
                    null,
                    title(),
                    goal != null ? Episode.GOAL : Episode.INFERRED,
                    outcome(),
                    startedAt,
                    lastAt,
                    runs,
                    actions);
        }
    }

    private static <E extends Enum<E>> @Nullable E enumOf(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String more(int n) {
        return n > 1 ? " (+" + (n - 1) + " more)" : "";
    }

    private static String verb(Fact.Kind kind) {
        return switch (kind) {
            case REMOVE -> "Removing";
            case PIN -> "Pinning";
            default -> "Adding";
        };
    }

    /** The last segment of a path, or the whole string when it has none. */
    static String baseName(String path) {
        if (path == null || path.isBlank()) return "the project";
        String p = path.replace('\\', '/');
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int slash = p.lastIndexOf('/');
        return slash >= 0 && slash < p.length() - 1 ? p.substring(slash + 1) : p;
    }
}
