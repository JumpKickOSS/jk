// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.verbs.DoctorReport;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.winhost.HostRow;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code GET /api/host}: the host notes the Status view shows, one quiet line each, such as {@code
 * slower: Defender scans …} on a Windows machine that declined its setup. {@code {"notes":[{id,
 * detail, fix}]}}; none off Windows.
 */
final class HttpHostApi {

    private final Supplier<List<HostRow>> rows;

    HttpHostApi() {
        this(() -> DoctorReport.windowsRows(null));
    }

    HttpHostApi(Supplier<List<HostRow>> rows) {
        this.rows = rows;
    }

    void handle(HttpExchange exchange) throws IOException {
        HttpResponses.sendJson(exchange, 200, json());
    }

    /**
     * {@code GET /api/doctor?dir=…}: the health report MCP {@code doctor} returns, {@code {ok,
     * rows:[{id, label, status, detail, fix?}]}}, the client's own rows marked {@code unavailable}.
     * {@code dir} names the project whose lock is checked.
     */
    static void handleDoctor(HttpExchange exchange) throws IOException {
        String dir = HttpQuery.queryParam(exchange.getRequestURI().getRawQuery(), "dir");
        if (HttpResponses.answeredHead(exchange)) return;
        HttpResponses.sendJson(
                exchange, 200, MiniJson.write(DoctorReport.structured(DoctorReport.rows(dir, JkVersion.VERSION))));
    }

    /** The warning rows as notes. */
    String json() {
        List<Object> notes = new ArrayList<>();
        for (HostRow row : rows.get()) {
            if (row.status() == HostRow.Status.OK) continue;
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("id", row.id());
            note.put("detail", row.detail());
            if (row.fix() != null) note.put("fix", row.fix());
            notes.add(note);
        }
        return MiniJson.write(Map.of("notes", notes));
    }
}
