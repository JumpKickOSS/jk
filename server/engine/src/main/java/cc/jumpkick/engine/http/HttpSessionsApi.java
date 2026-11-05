// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.SessionLog;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** {@code GET /api/sessions} and {@code GET /api/sessions/<id>}: the session log, read. */
final class HttpSessionsApi {

    static final String PREFIX = "/api/sessions/";

    static final int SESSIONS_DEFAULT = 50;
    static final int SESSIONS_MAX = 200;
    static final int ACTIONS_DEFAULT = 100;
    static final int ACTIONS_MAX = 1000;

    private final SessionLog sessions;

    HttpSessionsApi(SessionLog sessions) {
        this.sessions = sessions;
    }

    /** {@code GET /api/sessions?limit=N}: live and recent sessions, each with its current episode. */
    void handleSessions(HttpExchange exchange) throws IOException {
        int limit = bounded(exchange, "limit", SESSIONS_DEFAULT, SESSIONS_MAX);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", sessions.sessions(limit));
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(body).toString());
    }

    /**
     * {@code GET /api/sessions/<id>?before=<seq>&limit=N}: one session, every episode, and a page of
     * its actions oldest first; {@code next} is the {@code before} of the page older than this one.
     */
    void handleSession(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestURI().getPath().substring(PREFIX.length());
        if (!SessionJournal.validId(id)) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "invalid session id").toString());
            return;
        }
        long before = Math.max(0, longParam(exchange, "before"));
        int limit = bounded(exchange, "limit", ACTIONS_DEFAULT, ACTIONS_MAX);
        Optional<SessionLog.Page> page = sessions.session(id, before, limit);
        if (page.isEmpty()) {
            HttpResponses.sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such session: " + id).toString());
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session", page.get().session());
        body.put("episodes", page.get().episodes());
        body.put("actions", page.get().actions());
        body.put("next", page.get().next() > 0 ? page.get().next() : null);
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(body).toString());
    }

    private static int bounded(HttpExchange exchange, String name, int fallback, int max) {
        long n = longParam(exchange, name);
        if (n <= 0) return fallback;
        return (int) Math.min(n, max);
    }

    private static long longParam(HttpExchange exchange, String name) {
        String raw = HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), name);
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
