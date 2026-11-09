// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.activity.Activity;
import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/activity}: the machine's history, newest first, one page at a time. {@code
 * ?limit=N} (default {@value #DEFAULT_LIMIT}, at most {@link Activity#MAX_PAGE}), {@code
 * ?before=<cursor>} (the {@code next} of the page before), and the filters {@code kind}, {@code
 * project}, {@code session} and {@code surface}. Answers {@code {"entries":[…], "next":
 * cursor|null}}, plus {@code "facets": {projects, sessions, surfaces, kinds}} on the first page, the
 * values the filters offer.
 */
final class HttpActivityApi {

    static final int DEFAULT_LIMIT = 50;

    private final Activity activity;

    HttpActivityApi(Activity activity) {
        this.activity = activity;
    }

    void handle(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getRawQuery();
        HttpResponses.sendJson(
                exchange,
                200,
                json(
                        new Activity.Filter(
                                HttpQuery.queryParam(q, "kind"),
                                HttpQuery.queryParam(q, "project"),
                                HttpQuery.queryParam(q, "session"),
                                HttpQuery.queryParam(q, "surface")),
                        HttpQuery.queryParam(q, "before"),
                        HttpQuery.queryParam(q, "limit")));
    }

    /** One page as the response body. */
    String json(Activity.Filter filter, @Nullable String before, @Nullable String limit) {
        Activity.Page page = activity.page(filter, before, parseLimit(limit));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", page.entries().stream().map(Activity.Entry::toMap).toList());
        body.put("next", page.next());
        if (before == null || before.isBlank()) {
            Activity.Facets f = activity.facets();
            Map<String, Object> facets = new LinkedHashMap<>();
            facets.put("kinds", Activity.KINDS);
            facets.put("projects", f.projects());
            facets.put("sessions", f.sessions());
            facets.put("surfaces", f.surfaces());
            body.put("facets", facets);
        }
        return MiniJson.write(body);
    }

    private static int parseLimit(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LIMIT;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}
