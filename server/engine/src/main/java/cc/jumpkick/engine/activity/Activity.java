// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.activity;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.engine.notices.Notices;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The machine's history as one list, newest first: each change JumpKick made outside a project,
 * each decision it asked and how it was settled, machine-setup results, and the notices it showed.
 * There is no store of its own: every read merges the change journal, the decision queue and the
 * notices, so each kind is kept as long as its source keeps it.
 *
 * <p>Pages are cut by a cursor, the {@code at} and {@code id} of the last entry of the page before,
 * so a page asked for while new entries arrive neither repeats nor skips one.
 */
public final class Activity {

    /** The most entries one page holds. */
    public static final int MAX_PAGE = 200;

    public static final String NOTIFICATION = "notification";
    public static final String DECISION = "decision";
    public static final String CHANGE = "change";
    public static final String SETUP = "setup";
    public static final String INSTALL = "install";

    /** The kinds, in the order the page's filter lists them. */
    public static final List<String> KINDS = List.of(NOTIFICATION, DECISION, CHANGE, SETUP, INSTALL);

    /** The decision kind of the machine-setup decision. */
    static final String SETUP_DECISION = "setup";

    /**
     * One thing that happened.
     *
     * @param kind {@link #KINDS}
     * @param id unique across sources: {@code change:…}, {@code decision:…} or {@code notice:…}
     * @param at epoch millis it happened (a decision: when it was asked)
     * @param title one line saying what happened
     * @param who who did or asked it, as a person reads it
     * @param surface where it came from or was answered: {@code cli}, {@code dashboard}, {@code
     *     agent}, {@code import} or {@code engine}; null when it says nothing
     * @param channel how it reached the person ({@code in-app}, {@code browser}, {@code native},
     *     {@code open}, {@code none}); null when nothing told them
     * @param project the checkout or build file it concerns, or null
     * @param session the agent session behind it, or null
     * @param href where the page links it: a decision's dialog, or the entry itself
     * @param extra the source's own fields the page shows when the entry opens (a diff, a decision's
     *     outcome and answer time)
     */
    public record Entry(
            String kind,
            String id,
            long at,
            String title,
            String who,
            @Nullable String surface,
            @Nullable String channel,
            @Nullable String project,
            @Nullable String session,
            String href,
            Map<String, Object> extra) {

        public Entry {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(id);
            extra = Map.copyOf(extra);
        }

        /** True when it reached the person by some channel, or is itself a notice. */
        boolean notified() {
            return NOTIFICATION.equals(kind) || (channel != null && !"none".equals(channel));
        }

        /** As {@code GET /api/activity} lists it. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("id", id);
            m.put("at", at);
            m.put("title", title);
            m.put("who", who);
            if (surface != null) m.put("surface", surface);
            if (channel != null) m.put("channel", channel);
            if (project != null) m.put("project", project);
            if (session != null) m.put("session", session);
            m.put("href", href);
            m.putAll(extra);
            return m;
        }

        String cursor() {
            return at + ":" + id;
        }
    }

    /**
     * What a page keeps; null or blank keeps everything. {@code kind} {@link #NOTIFICATION} keeps
     * every entry that reached the person, decisions included.
     */
    public record Filter(
            @Nullable String kind,
            @Nullable String project,
            @Nullable String session,
            @Nullable String surface) {

        public static final Filter ALL = new Filter(null, null, null, null);

        boolean keeps(Entry e) {
            String k = blankToNull(kind);
            if (k != null && !(k.equals(e.kind()) || (NOTIFICATION.equals(k) && e.notified()))) return false;
            String p = blankToNull(project);
            String entryProject = e.project();
            if (p != null && (entryProject == null || !entryProject.startsWith(p))) return false;
            String s = blankToNull(session);
            if (s != null && !s.equals(e.session())) return false;
            String f = blankToNull(surface);
            return f == null || f.equals(e.surface());
        }

        private static @Nullable String blankToNull(@Nullable String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    /** One page, newest first; {@code next} is the cursor of the page after it, null on the last. */
    public record Page(List<Entry> entries, @Nullable String next) {}

    /** The values the filters offer, from every entry kept. */
    public record Facets(List<String> projects, List<Map<String, String>> sessions, List<String> surfaces) {}

    private final Supplier<ChangeJournal> journal;
    private final DecisionQueue queue;
    private final Notices notices;

    public Activity(Supplier<ChangeJournal> journal, DecisionQueue queue, Notices notices) {
        this.journal = Objects.requireNonNull(journal);
        this.queue = Objects.requireNonNull(queue);
        this.notices = Objects.requireNonNull(notices);
    }

    /** Up to {@code limit} entries {@code filter} keeps that are older than {@code before} (all when null). */
    public Page page(Filter filter, @Nullable String before, int limit) {
        int max = Math.clamp(limit, 1, MAX_PAGE);
        Cursor from = Cursor.parse(before);
        List<Entry> out = new ArrayList<>(max);
        boolean more = false;
        for (Entry e : all()) {
            if (from != null && !from.after(e)) continue;
            if (!filter.keeps(e)) continue;
            if (out.size() == max) {
                more = true;
                break;
            }
            out.add(e);
        }
        return new Page(List.copyOf(out), more ? out.getLast().cursor() : null);
    }

    /** Every project, session and surface an entry names, for the page's filters. */
    public Facets facets() {
        Set<String> projects = new TreeSet<>();
        Map<String, String> sessions = new LinkedHashMap<>();
        Set<String> surfaces = new TreeSet<>();
        for (Entry e : all()) {
            if (e.project() != null) projects.add(e.project());
            if (e.session() != null) sessions.putIfAbsent(e.session(), e.who());
            if (e.surface() != null) surfaces.add(e.surface());
        }
        List<Map<String, String>> s = new ArrayList<>();
        sessions.forEach((id, label) -> s.add(Map.of("id", id, "label", label)));
        return new Facets(List.copyOf(projects), List.copyOf(s), List.copyOf(surfaces));
    }

    /** Every entry, newest first. */
    List<Entry> all() {
        List<Entry> all = new ArrayList<>();
        for (Change c : journal.get().newestFirst()) all.add(of(c));
        for (Decision d : queue.list()) all.add(of(d, queue.notified(d.id()).orElse(null)));
        for (Notices.Notice n : notices.list()) {
            // A decision's notice is its decision's entry, a change's its change's: listed once.
            if (n.decision() != null || CHANGE.equals(n.kind()) || SETUP.equals(n.kind())) continue;
            all.add(of(n));
        }
        all.sort(Comparator.comparingLong(Entry::at).thenComparing(Entry::id).reversed());
        return all;
    }

    static Entry of(Change c) {
        String kind =
                switch (c.action()) {
                    case Change.APPLY -> SETUP;
                    case Change.INSTALL, Change.UNINSTALL -> INSTALL;
                    default -> CHANGE;
                };
        ChangeActor actor = c.actor();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("what", c.what());
        extra.put("action", c.action());
        if (c.diff() != null) extra.put("diff", c.diff());
        extra.put("actor", actor.toMap());
        String id = "change:" + c.id();
        String project = actor.details().get("source");
        return new Entry(
                kind,
                id,
                c.at(),
                capitalize(c.summary()),
                actor.label(),
                surfaceOf(actor.kind()),
                null,
                project,
                actor.details().get("session"),
                "#activity/" + id,
                extra);
    }

    static Entry of(Decision d, @Nullable String channel) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("decision", d.id());
        extra.put("decisionKind", d.kind());
        extra.put("outcome", d.state().wire());
        if (d.answeredAt() > 0) extra.put("answeredAt", d.answeredAt());
        if (d.answeredBy() != null) extra.put("answeredBy", d.answeredBy().wire());
        if (!d.detail().isEmpty()) extra.put("detail", d.detail());
        String who = d.label() != null && !d.label().isBlank()
                ? d.label()
                : SETUP_DECISION.equals(d.kind()) ? "JumpKick" : "An agent";
        return new Entry(
                SETUP_DECISION.equals(d.kind()) ? SETUP : DECISION,
                "decision:" + d.id(),
                d.createdAt(),
                d.question(),
                who,
                d.answeredBy() == null ? null : surfaceOf(d.answeredBy()),
                channel,
                d.project(),
                d.session(),
                d.route(),
                extra);
    }

    static Entry of(Notices.Notice n) {
        return new Entry(
                NOTIFICATION,
                "notice:" + n.id(),
                n.at(),
                n.text(),
                "JumpKick",
                "engine",
                "in-app",
                null,
                null,
                n.href(),
                Map.of("noticeKind", n.kind()));
    }

    /** Where a decision's answer came from, as the filter names surfaces. */
    static String surfaceOf(DecisionSurface s) {
        return switch (s) {
            case CLI -> "cli";
            case DASHBOARD -> "dashboard";
            case HOST_FORM, HOST_URL -> "agent";
        };
    }

    /** Who made a change, as the filter names surfaces: a shell is the CLI. */
    static String surfaceOf(String actorKind) {
        return ChangeActor.SHELL.equals(actorKind) ? "cli" : actorKind;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** A page boundary: entries strictly older than it, by {@code at} then {@code id}. */
    private record Cursor(long at, String id) {

        static @Nullable Cursor parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) return null;
            int colon = raw.indexOf(':');
            if (colon <= 0) return null;
            try {
                return new Cursor(Long.parseLong(raw.substring(0, colon)), raw.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        boolean after(Entry e) {
            return e.at() < at || (e.at() == at && e.id().compareTo(id) < 0);
        }
    }
}
