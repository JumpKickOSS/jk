// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/changes}: the change journal, newest first, one page at a time. {@code
 * ?limit=N} (default {@value #DEFAULT_LIMIT}, at most {@link ChangeJournal#MAX_PAGE}) and {@code
 * ?before=<id>}, the {@code next} of the page before. Answers {@code {"entries":[…], "next":
 * id|null}}; each entry is a {@link Change} as the journal holds it: {@code id, at, what, action,
 * summary, diff?, actor{kind, label, …}}, its secrets masked when it was written.
 */
final class HttpChangesApi {

    static final int DEFAULT_LIMIT = 50;

    private final Supplier<ChangeJournal> journal;

    HttpChangesApi() {
        this(ChangeJournal::current);
    }

    HttpChangesApi(Supplier<ChangeJournal> journal) {
        this.journal = journal;
    }

    void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        HttpResponses.sendJson(
                exchange, 200, json(HttpQuery.queryParam(query, "before"), HttpQuery.queryParam(query, "limit")));
    }

    /** One page as the response body. */
    String json(@Nullable String before, @Nullable String limit) {
        ChangeJournal.Page page = journal.get().page(blankToNull(before), parseLimit(limit));
        List<Map<String, Object>> entries =
                page.entries().stream().map(Change::toMap).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", entries);
        body.put("next", page.next());
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

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
