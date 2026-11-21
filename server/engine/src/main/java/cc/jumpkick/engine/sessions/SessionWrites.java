// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * How {@link SessionLog} keeps an open session: its head, its episode lines and its actions in the
 * {@link SessionJournal}, or its actions in memory when there is no journal. Episode and action
 * lines are appended; once they grow past what a session keeps, both are compacted. Every write
 * waits on the log's gate, so a displaced engine writes nothing, and a write that fails is logged,
 * never thrown: the session lives on in memory. Called under the log's monitor.
 */
final class SessionWrites {

    /**
     * Episode lines a session's {@code episodes.jsonl} may reach before it is compacted to one line
     * per kept episode: every change to an episode appends one.
     */
    static final int COMPACT_EPISODE_LINES = 4 * EpisodeInference.KEEP;

    private final @Nullable SessionJournal journal;
    private final ProjectIdMemo projectIds;
    private final BooleanSupplier writing;

    /** {@code writing} is the log's gate: true while this engine writes the records. */
    SessionWrites(@Nullable SessionJournal journal, ProjectIdMemo projectIds, BooleanSupplier writing) {
        this.journal = journal;
        this.projectIds = projectIds;
        this.writing = writing;
    }

    /** Give {@code action} {@code live}'s next sequence and keep it; answers it as logged. */
    ActionRecord append(SessionLog.Live live, ActionRecord action) {
        ActionRecord logged = action.redacted().withSeq(++live.seq);
        if (journal == null) {
            live.memory.add(logged);
            if (live.memory.size() > SessionLog.KEPT_ACTIONS) live.memory.removeFirst();
            return logged;
        }
        if (!writing.getAsBoolean()) return logged;
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
    void persist(SessionLog.Live live) {
        if (journal == null || !writing.getAsBoolean()) return;
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
     * those episodes, at most {@link SessionLog#KEPT_ACTIONS} of the newest; then its head. {@code
     * actions} is its action log when the caller has already read it.
     */
    void compact(SessionLog.Live live, @Nullable List<Map<String, Object>> actions) {
        if (journal == null || !writing.getAsBoolean()) return;
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
            int from = Math.max(0, keep.size() - SessionLog.KEPT_ACTIONS);
            journal.rewriteActions(id, keep.subList(from, keep.size()));
            journal.write(id, head(live));
        } catch (IOException | RuntimeException e) {
            Log.debug("session not compacted: " + id, e);
        }
    }

    /** Store the MCP connection behind {@code live}, so an engine that adopts it answers the connection too. */
    void connection(SessionLog.Live live) {
        Map<String, Object> connection = live.connection;
        if (journal == null || connection == null || !writing.getAsBoolean()) return;
        try {
            journal.writeConnection(live.record.id(), connection);
        } catch (IOException | RuntimeException e) {
            Log.debug("session connection not written: " + live.record.id(), e);
        }
    }

    /** Forget the stored MCP connection of session {@code id}: the session ended. */
    void forgetConnection(String id) {
        if (journal == null || !writing.getAsBoolean()) return;
        try {
            journal.deleteConnection(id);
        } catch (IOException | RuntimeException e) {
            Log.debug("session connection not removed: " + id, e);
        }
    }

    /** {@code live}'s {@code session.json}: its record and the inference state its next fact needs. */
    private static Map<String, Object> head(SessionLog.Live live) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", 1);
        doc.put("session", live.record.toMap());
        doc.put("inference", live.episodes.state());
        // Beside the record, never in it: the record is what the dashboard shows.
        if (live.leader != null) doc.put("leader", live.leader);
        return doc;
    }

    /**
     * One episode line, the episode with its project's id when remembered, and its tallies. The
     * entry point placing a fact asked for its project's id before taking the monitor; a line
     * without one has it added as it is read.
     */
    private Map<String, Object> line(EpisodeInference.Stored e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("episode", projectIds.namedIfKnown(e.episode()).toMap());
        m.put("state", e.state());
        return m;
    }
}
