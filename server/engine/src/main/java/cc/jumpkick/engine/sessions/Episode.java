// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One goal inside a session, as stored in its {@code session.json} and served by {@code
 * /api/sessions}.
 *
 * @param id {@code <session>.<number>}; a run record's {@code episode}
 * @param number 1 for the session's first episode
 * @param project the checkout it worked in (absolute dir)
 * @param projectId that checkout's durable project id, or null when it has none
 * @param title what it is doing: the agent's goal, or a title inferred from its facts
 * @param titleSource {@code goal} when the agent named it for this episode, {@code lastGoal} when
 *     the episode took the session's last goal for want of one, {@code inferred} otherwise
 * @param outcome {@code working} while open; once closed {@code green} or {@code red} by its last
 *     run (or, with none, its last action), {@code abandoned} when nothing in it was judged
 * @param lastAt when its last fact finished
 * @param runs the runs placed in it
 * @param actions the non-run actions placed in it
 */
public record Episode(
        String id,
        String session,
        int number,
        String project,
        @Nullable String projectId,
        String title,
        String titleSource,
        String outcome,
        long startedAt,
        long lastAt,
        int runs,
        int actions) {

    public static final String WORKING = "working";
    public static final String GREEN = "green";
    public static final String RED = "red";
    public static final String ABANDONED = "abandoned";

    public static final String INFERRED = "inferred";
    public static final String GOAL = "goal";
    public static final String LAST_GOAL = "lastGoal";

    /** This episode with its project id. */
    public Episode withProjectId(@Nullable String projectId) {
        return new Episode(
                id, session, number, project, projectId, title, titleSource, outcome, startedAt, lastAt, runs, actions);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("session", session);
        m.put("number", number);
        m.put("project", project);
        if (projectId != null) m.put("projectId", projectId);
        m.put("title", title);
        m.put("titleSource", titleSource);
        m.put("outcome", outcome);
        m.put("startedAt", startedAt);
        m.put("lastAt", lastAt);
        m.put("runs", runs);
        m.put("actions", actions);
        return m;
    }

    /** An episode read back from {@link #toMap}; missing fields read as empty. */
    public static Episode of(Map<?, ?> m) {
        return new Episode(
                text(m, "id"),
                text(m, "session"),
                (int) num(m, "number"),
                text(m, "project"),
                MiniJson.str(m, "projectId"),
                text(m, "title"),
                text(m, "titleSource"),
                text(m, "outcome"),
                num(m, "startedAt"),
                num(m, "lastAt"),
                (int) num(m, "runs"),
                (int) num(m, "actions"));
    }

    static String text(Map<?, ?> m, String key) {
        String s = MiniJson.str(m, key);
        return s == null ? "" : s;
    }

    static long num(Map<?, ?> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : 0L;
    }
}
