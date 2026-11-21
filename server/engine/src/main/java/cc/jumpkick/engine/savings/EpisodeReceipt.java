// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.engine.sessions.ActionRecord;
import cc.jumpkick.engine.sessions.Episode;
import cc.jumpkick.engine.sessions.SessionLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What one episode saved: the ledger's figures over the runs placed in it and the actions it took.
 * It ends the episode on the dashboard's console and the {@code jk-results.md} of each run in it.
 *
 * @param title what the episode is doing, as its page names it
 * @param saved its figures in {@link Savings#EPISODE} scope, from its start to its last fact
 */
public record EpisodeReceipt(String episode, String title, Savings saved) {

    /** The heading of the receipt's section, the last of {@code jk-results.md}. */
    public static final String HEADING = "## Saved in this episode";

    /**
     * The receipt of each episode session {@code sessionId} keeps, by episode id; empty for a
     * session the log does not know.
     */
    public static Map<String, EpisodeReceipt> forSession(BuildJournal journal, SessionLog sessions, String sessionId) {
        return read(journal, sessions, sessionId, null, null);
    }

    /**
     * The receipt of the episode {@code run} was placed in, {@code run} counted once whether or not
     * the journal kept it; null when it was placed in none the log keeps.
     */
    public static @Nullable EpisodeReceipt forRun(BuildJournal journal, SessionLog sessions, BuildRecord run) {
        String episode = run.episode();
        if (episode == null) return null;
        int dot = episode.lastIndexOf('.');
        if (dot <= 0) return null;
        return read(journal, sessions, episode.substring(0, dot), episode, run).get(episode);
    }

    /** {@link #forRun}'s receipt as {@link #markdown()} renders it, or null when there is none. */
    public static @Nullable String markdown(BuildJournal journal, SessionLog sessions, BuildRecord run) {
        EpisodeReceipt receipt = forRun(journal, sessions, run);
        return receipt == null ? null : receipt.markdown();
    }

    /** The receipts of session {@code sessionId}'s episodes, or of {@code only} when it is not null. */
    private static Map<String, EpisodeReceipt> read(
            BuildJournal journal,
            SessionLog sessions,
            String sessionId,
            @Nullable String only,
            @Nullable BuildRecord current) {
        Optional<SessionLog.Page> page = sessions.session(sessionId, 0, Integer.MAX_VALUE);
        if (page.isEmpty()) return Map.of();
        List<Episode> episodes = new ArrayList<>();
        for (Map<String, Object> m : page.get().episodes()) {
            Episode e = Episode.of(m);
            if (only == null || only.equals(e.id())) episodes.add(e);
        }
        if (episodes.isEmpty()) return Map.of();
        Set<String> ids = new HashSet<>();
        long since = Long.MAX_VALUE;
        for (Episode e : episodes) {
            ids.add(e.id());
            since = Math.min(since, e.startedAt());
        }
        List<BuildRecord> runs = new ArrayList<>(journal.finishedSince(since, r -> ids.contains(r.episode())));
        if (current != null && runs.stream().noneMatch(r -> sameRun(r, current))) runs.add(current);
        List<ActionRecord> actions = new ArrayList<>();
        for (Map<String, Object> m : page.get().actions()) actions.add(ActionRecord.of(m));
        return fold(episodes, runs, actions);
    }

    /** The receipts of {@code episodes} over runs and actions already read, by episode id. */
    static Map<String, EpisodeReceipt> fold(
            List<Episode> episodes, List<BuildRecord> runs, List<ActionRecord> actions) {
        Map<String, EpisodeReceipt> out = new LinkedHashMap<>();
        for (Episode e : episodes) {
            Savings saved = Savings.empty(Savings.EPISODE, e.projectId(), e.project(), null, e.startedAt(), e.lastAt());
            for (BuildRecord r : runs) if (e.id().equals(r.episode())) saved = saved.plus(SavingsLedger.of(r));
            for (ActionRecord a : actions) {
                if (e.id().equals(a.episode()) && !ActionRecord.RUN.equals(a.kind())) {
                    saved = saved.plus(SavingsLedger.of(a));
                }
            }
            out.put(e.id(), new EpisodeReceipt(e.id(), e.title(), saved));
        }
        return out;
    }

    /** Whether {@code a} and {@code b} record the same run: one request, or one start in one checkout. */
    private static boolean sameRun(BuildRecord a, BuildRecord b) {
        if (a.requestId() > 0 && b.requestId() > 0) return a.requestId() == b.requestId();
        return a.startedAt() == b.startedAt() && Objects.equals(a.dir(), b.dir());
    }

    /** The receipt as the last section of {@code jk-results.md}: one line per figure it counted. */
    public String markdown() {
        StringBuilder sb = new StringBuilder(HEADING).append("\n\n");
        Savings s = saved;
        sb.append(title.isBlank() ? episode : title)
                .append(" · ")
                .append(s.runs())
                .append(s.runs() == 1 ? " run" : " runs")
                .append("\n\n");
        List<String> lines = new ArrayList<>();
        if (s.savedMillis() > 0) {
            String measured = s.estimatedSkips() > 0
                    ? ", " + Math.round(100.0 * s.coveredSkips() / s.estimatedSkips()) + "% measured"
                    : "";
            lines.add("Build and test time avoided: ~" + JkResultsMarkdown.fmtDuration(s.savedMillis()) + " (estimate"
                    + measured + ")");
        }
        if (s.stepsSkipped() > 0) {
            lines.add("Steps served from the cache: " + s.stepsSkipped()
                    + (s.suitesSkipped() > 0 ? " (" + s.suitesSkipped() + " test suites)" : ""));
        }
        if (s.testClassesNotRerun() > 0) lines.add("Test classes not re-run: " + s.testClassesNotRerun());
        if (s.graphCalls() > 0) lines.add("Code-graph questions answered: " + s.graphCalls());
        if (s.refactorings() > 0) lines.add("Refactorings applied: " + s.refactorings());
        if (s.edits() > 0) lines.add("Dependency edits made: " + s.edits());
        if (s.guardViolations() > 0) {
            lines.add("Guard violations stopped: " + s.guardViolations() + " in " + s.guardStops()
                    + (s.guardStops() == 1 ? " run" : " runs"));
        }
        if (lines.isEmpty()) sb.append("Nothing saved yet: no cache hits, graph answers or edits\n");
        for (String line : lines) sb.append("- ").append(line).append('\n');
        return sb.toString();
    }
}
