// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One thing a session did, in the compact form a timeline shows: one line of {@code actions.jsonl}
 * and the payload of the SSE {@code action} event. Its summary and headline are masked like a run
 * record's free text before either is written.
 *
 * @param seq its place in the session, from 1
 * @param at when it started, epoch millis
 * @param kind {@code run}, or the MCP tool: {@code query}, {@code callers}, {@code read}, {@code
 *     find}, {@code rename}, {@code deps}, {@code update}, {@code workspace}
 * @param summary one line saying what it was: {@code callers PricingRules.weekendUplift}
 * @param headline the first line of its answer, or null when it had none
 * @param outcome {@code ok} or {@code error} for an action; {@code green}, {@code red} or {@code
 *     cancelled} for a run
 * @param episode the episode it belongs to
 * @param project the checkout it acted on, or null when it named none
 * @param buildNumber a run's build number; 0 for an action or an unnumbered run
 */
public record ActionRecord(
        long seq,
        long at,
        String kind,
        String summary,
        @Nullable String headline,
        String outcome,
        long millis,
        String episode,
        @Nullable String project,
        long buildNumber) {

    public static final String RUN = "run";
    public static final String OK = "ok";
    public static final String ERROR = "error";

    /** What ends a summary or headline clipped to fit a timeline line. */
    public static final String CLIP = "…";

    public ActionRecord withSeq(long seq) {
        return new ActionRecord(seq, at, kind, summary, headline, outcome, millis, episode, project, buildNumber);
    }

    /** This action with its summary and headline masked as {@link EventRedaction} masks a run's free text. */
    public ActionRecord redacted() {
        String s = EventRedaction.redactClipped(project, summary, CLIP);
        String h = headline == null ? null : EventRedaction.redactClipped(project, headline, CLIP);
        return s.equals(summary) && Objects.equals(h, headline)
                ? this
                : new ActionRecord(seq, at, kind, s, h, outcome, millis, episode, project, buildNumber);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("at", at);
        m.put("kind", kind);
        m.put("summary", summary);
        if (headline != null) m.put("headline", headline);
        m.put("outcome", outcome);
        m.put("millis", millis);
        m.put("episode", episode);
        if (project != null) m.put("project", project);
        if (buildNumber > 0) m.put("buildNumber", buildNumber);
        return m;
    }

    public static ActionRecord of(Map<?, ?> m) {
        return new ActionRecord(
                Episode.num(m, "seq"),
                Episode.num(m, "at"),
                Episode.text(m, "kind"),
                Episode.text(m, "summary"),
                MiniJson.str(m, "headline"),
                Episode.text(m, "outcome"),
                Episode.num(m, "millis"),
                Episode.text(m, "episode"),
                MiniJson.str(m, "project"),
                Episode.num(m, "buildNumber"));
    }
}
