// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.engine.journal.SessionJournal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sessions as a {@link SessionJournal} stores them, read without the {@link SessionLog}'s monitor:
 * their records newest first, a session's kept episodes and actions, and one page of its timeline.
 */
final class StoredSessions {

    private StoredSessions() {}

    /** One page of a timeline: its actions oldest first, and the {@code before} that reads the older page, or 0. */
    record Slice(List<Map<String, Object>> actions, long next) {}

    /** Every stored session's record, most recently active first. */
    static List<SessionRecord> newestFirst(SessionJournal journal) {
        List<SessionRecord> stored = new ArrayList<>();
        for (Map<String, Object> doc : journal.list()) {
            if (doc.get("session") instanceof Map<?, ?> s) stored.add(SessionRecord.of(s));
        }
        stored.sort(Comparator.comparingLong(SessionRecord::lastActiveAt).reversed());
        return stored;
    }

    /** Stored session {@code id}'s record, when the journal has one. */
    static Optional<SessionRecord> record(SessionJournal journal, String id) {
        Optional<Map<String, Object>> doc = journal.read(id);
        if (doc.isEmpty() || !(doc.get().get("session") instanceof Map<?, ?> s)) return Optional.empty();
        return Optional.of(SessionRecord.of(s));
    }

    /** Stored session {@code id}'s kept episodes, oldest first. */
    static List<Episode> episodes(SessionJournal journal, String id) {
        List<Episode> out = new ArrayList<>();
        for (EpisodeInference.Stored e : EpisodeLines.latest(journal.episodeLines(id))) out.add(e.episode());
        out.sort(Comparator.comparingInt(Episode::number));
        return out.size() > EpisodeInference.KEEP
                ? List.copyOf(out.subList(out.size() - EpisodeInference.KEEP, out.size()))
                : out;
    }

    /** Stored session {@code id}'s actions, oldest first. */
    static List<ActionRecord> actions(SessionJournal journal, String id) {
        List<ActionRecord> read = new ArrayList<>();
        for (Map<String, Object> m : journal.actions(id)) read.add(ActionRecord.of(m));
        return read;
    }

    /**
     * The actions of every stored session active at or after {@code since}, oldest first within a
     * session, each with its session's client and no project id.
     */
    static List<SessionLog.Acted> actionsSince(SessionJournal journal, long since) {
        List<SessionLog.Acted> out = new ArrayList<>();
        for (SessionRecord r : newestFirst(journal)) {
            if (r.lastActiveAt() < since) break;
            for (ActionRecord a : actions(journal, r.id())) out.add(new SessionLog.Acted(a, null, r.client()));
        }
        return out;
    }

    /**
     * Up to {@code limit} of {@code actions} older than {@code before} ({@code 0}: the newest),
     * oldest first.
     */
    static Slice page(List<ActionRecord> actions, long before, int limit) {
        int end = actions.size();
        if (before > 0) {
            end = 0;
            while (end < actions.size() && actions.get(end).seq() < before) end++;
        }
        int start = Math.max(0, end - Math.max(1, limit));
        List<Map<String, Object>> page = new ArrayList<>();
        for (ActionRecord a : actions.subList(start, end)) page.add(a.toMap());
        return new Slice(page, start > 0 ? actions.get(start).seq() : 0);
    }
}
