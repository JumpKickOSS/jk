// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.notices.Notices;
import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/notices} and {@code POST /api/notices/seen}: the bell's recent notices and the
 * machine's one seen marker, read and moved from the dashboard.
 */
final class HttpNoticesApi {

    static final String SEEN = "/api/notices/seen";

    private final Notices notices;

    HttpNoticesApi(Notices notices) {
        this.notices = notices;
    }

    /** {@code GET /api/notices}: {@code {seenUpTo, unseen, notices}}, newest first. */
    void handleList(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seenUpTo", notices.seenUpTo());
        body.put("unseen", notices.unseen());
        body.put("notices", notices.list().stream().map(Notices.Notice::toMap).toList());
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(body).toString());
    }

    /**
     * {@code POST /api/notices/seen} with {@code {"upTo": <ms>}}: moves the marker there (never
     * back, never past the newest notice) and answers {@code {seenUpTo, unseen}}; {@code 400} when
     * {@code upTo} is not a number.
     */
    void handleSeen(HttpExchange exchange) throws IOException {
        Object parsed = MiniJson.parse(HttpRequests.body(exchange));
        if (!(parsed instanceof Map<?, ?> body) || !(body.get("upTo") instanceof Number upTo)) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "upTo is a number").toString());
            return;
        }
        Notices.Change after = notices.seen(upTo.longValue());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seenUpTo", after.seenUpTo());
        out.put("unseen", after.unseen());
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(out).toString());
    }
}
