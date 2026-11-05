// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.jsonl.MiniJson;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One origin talking to the engine, as stored in its {@code session.json}.
 *
 * @param id the record's id: {@code <origin>-<connection or "shell">-<started, base 36>}
 * @param origin the surface it came in on: {@code mcp}, {@code bsp}, {@code web}, {@code cli}
 * @param client what the client called itself ({@code clientInfo.name}, a BSP display name), or
 *     null when it named nothing
 * @param label the session as a run record names it ({@code claude-code 3f9a}), or null for a
 *     surface with no session of its own (the shell, the dashboard)
 * @param host the machine the engine served it on
 * @param project the checkout its latest fact acted on, or null before its first
 * @param endedAt when its connection closed; 0 while it has not
 * @param state {@code live}, {@code idle} once it has been quiet for the idle gap, {@code ended}
 * @param episode the id of its current episode, or null before its first fact
 */
public record SessionRecord(
        String id,
        String origin,
        @Nullable String client,
        @Nullable String label,
        String host,
        @Nullable String project,
        @Nullable String projectId,
        long startedAt,
        long lastActiveAt,
        long endedAt,
        String state,
        @Nullable String episode) {

    public static final String LIVE = "live";
    public static final String IDLE = "idle";
    public static final String ENDED = "ended";

    /** The state at {@code now}: ended once closed, idle past {@code idleGapMillis} of quiet, else live. */
    public String stateAt(long now, long idleGapMillis) {
        if (endedAt > 0) return ENDED;
        return now - lastActiveAt > idleGapMillis ? IDLE : LIVE;
    }

    public SessionRecord withState(String state) {
        return new SessionRecord(
                id, origin, client, label, host, project, projectId, startedAt, lastActiveAt, endedAt, state, episode);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("origin", origin);
        if (client != null) m.put("client", client);
        if (label != null) m.put("label", label);
        m.put("host", host);
        if (project != null) m.put("project", project);
        if (projectId != null) m.put("projectId", projectId);
        m.put("startedAt", startedAt);
        m.put("lastActiveAt", lastActiveAt);
        if (endedAt > 0) m.put("endedAt", endedAt);
        m.put("state", state);
        if (episode != null) m.put("episode", episode);
        return m;
    }

    /** A session read back from {@link #toMap}. */
    public static SessionRecord of(Map<?, ?> m) {
        return new SessionRecord(
                Episode.text(m, "id"),
                Episode.text(m, "origin"),
                MiniJson.str(m, "client"),
                MiniJson.str(m, "label"),
                Episode.text(m, "host"),
                MiniJson.str(m, "project"),
                MiniJson.str(m, "projectId"),
                Episode.num(m, "startedAt"),
                Episode.num(m, "lastActiveAt"),
                Episode.num(m, "endedAt"),
                Episode.text(m, "state"),
                MiniJson.str(m, "episode"));
    }
}
