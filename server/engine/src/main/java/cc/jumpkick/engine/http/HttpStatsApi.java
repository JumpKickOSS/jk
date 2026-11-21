// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.savings.Savings;
import cc.jumpkick.engine.savings.SavingsLedger;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.host.time.Clock;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/stats?since=<epoch ms>}: the savings ledger, as {@code jk stats} prints it. The
 * body's {@code rows} are the {@link Savings} rows, overall first, then one per project.
 */
final class HttpStatsApi {

    private final BuildJournal journal;
    private final Clock clock;
    private volatile @Nullable SessionLog sessions;

    HttpStatsApi(BuildJournal journal, Clock clock) {
        this.journal = journal;
        this.clock = clock;
    }

    /** Count the actions of {@code sessions} too. */
    void sessions(SessionLog sessions) {
        this.sessions = sessions;
    }

    void handle(HttpExchange exchange) throws IOException {
        String raw = HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "since");
        long since;
        try {
            since = raw == null || raw.isBlank() ? 0 : Math.max(0, Long.parseLong(raw.strip()));
        } catch (NumberFormatException e) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", "since is not a number: " + raw)
                            .toString());
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Savings s : SavingsLedger.read(journal, sessions, since, clock.millis())) rows.add(s.toMap());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(body).toString());
    }
}
