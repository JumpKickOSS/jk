// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.winhost.HostRow;
import cc.jumpkick.winhost.WindowsSetup;
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
        this(() -> Os.isWindows() ? WindowsSetup.check(null).rows() : List.of());
    }

    HttpHostApi(Supplier<List<HostRow>> rows) {
        this.rows = rows;
    }

    void handle(HttpExchange exchange) throws IOException {
        HttpResponses.sendJson(exchange, 200, json());
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
