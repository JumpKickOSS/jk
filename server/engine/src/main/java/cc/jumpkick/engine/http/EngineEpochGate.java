// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.JsonOut;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * Fail-closed engine-generation gate for {@code /api/*}: every call must carry an
 * {@code X-Jk-Engine-Epoch} matching this engine, except the bootstrap reads a fresh tab or an
 * {@code EventSource} cannot decorate — {@code GET|HEAD /api/status} and {@code /api/events}. Stale
 * dashboards hard-refresh on the 409.
 */
final class EngineEpochGate {

    private final Supplier<StatusSnapshot> status;

    EngineEpochGate(Supplier<StatusSnapshot> status) {
        this.status = status;
    }

    boolean allows(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean bootstrap = ("GET".equals(method) || "HEAD".equals(method))
                && (path.equals("/api/status") || path.equals("/api/events"));
        if (bootstrap) return true;
        String presented = exchange.getRequestHeaders().getFirst("X-Jk-Engine-Epoch");
        if (presented == null || presented.isBlank()) return false;
        StatusSnapshot s = status.get();
        String expected = s != null ? s.engineEpoch() : null;
        return expected != null && expected.equals(presented.trim());
    }

    void sendConflict(HttpExchange exchange) throws IOException {
        StatusSnapshot s = status.get();
        String epoch = s != null && s.engineEpoch() != null ? s.engineEpoch() : "";
        String body = JsonOut.object()
                .put("error", "engine-epoch-mismatch")
                .put("engineEpoch", epoch)
                .put("version", s != null ? s.version() : "")
                .put("startedAt", s != null ? s.startedAtMillis() : 0L)
                .toString();
        HttpResponses.sendJson(exchange, 409, body);
    }
}
