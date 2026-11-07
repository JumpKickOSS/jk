// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/decisions}, {@code GET /api/decisions/<id>} and {@code POST /api/decisions/<id>}:
 * the decision queue read and answered from the dashboard. A post is the dashboard's answer, and
 * the queue refuses it ({@code 403}) for a trust-changing accept unless it carries the decision's
 * host key, which only the link handed to the agent's host by URL elicitation holds.
 */
final class HttpDecisionsApi {

    static final String PREFIX = "/api/decisions/";

    private final DecisionQueue queue;

    HttpDecisionsApi(DecisionQueue queue) {
        this.queue = queue;
    }

    /** {@code GET /api/decisions}: open decisions first, then recently settled ones. */
    void handleList(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> rows =
                queue.list().stream().map(Decision::toMap).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decisions", rows);
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(body).toString());
    }

    /** {@code GET /api/decisions/<id>}. */
    void handleOne(HttpExchange exchange) throws IOException {
        String id = idOf(exchange);
        Decision d = queue.find(id).orElse(null);
        if (d == null) {
            error(exchange, 404, "no such decision: " + id);
            return;
        }
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(d.toMap()).toString());
    }

    /**
     * {@code POST /api/decisions/<id>} with {@code {"action": "accept" | "decline", "answers": {…},
     * "key": "…"}}: {@code 200} with the settled decision, {@code 409} with how it was already
     * settled, {@code 403} when this surface may not give that answer, {@code 400} for answers that
     * do not fit its form, {@code 404} for no such decision.
     */
    void handleAnswer(HttpExchange exchange) throws IOException {
        String id = idOf(exchange);
        Object parsed = MiniJson.parse(HttpRequests.body(exchange));
        if (!(parsed instanceof Map<?, ?> body)) {
            error(exchange, 400, "the body is a JSON object");
            return;
        }
        String action = MiniJson.str(body, "action");
        String key = MiniJson.str(body, "key");
        DecisionQueue.Result result;
        if ("decline".equals(action)) {
            result = queue.decline(id, DecisionSurface.DASHBOARD, key);
        } else if ("accept".equals(action)) {
            result = queue.accept(id, answers(body.get("answers")), DecisionSurface.DASHBOARD, key);
        } else {
            error(exchange, 400, "action is accept or decline");
            return;
        }
        switch (result) {
            case DecisionQueue.Result.Settled s ->
                HttpResponses.sendJson(
                        exchange, 200, JsonOut.rawObject(s.decision().toMap()).toString());
            case DecisionQueue.Result.Already a -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("error", "already " + a.decision().state().wire());
                out.put("decision", a.decision().toMap());
                HttpResponses.sendJson(exchange, 409, JsonOut.rawObject(out).toString());
            }
            case DecisionQueue.Result.Refused r -> error(exchange, 403, r.reason());
            case DecisionQueue.Result.Invalid i -> error(exchange, 400, i.problems());
            case DecisionQueue.Result.Missing m -> error(exchange, 404, "no such decision: " + id);
        }
    }

    private static Map<String, Object> answers(@Nullable Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getValue() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private static String idOf(HttpExchange exchange) {
        return exchange.getRequestURI().getPath().substring(PREFIX.length());
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        HttpResponses.sendJson(
                exchange, status, JsonOut.object().put("error", message).toString());
    }
}
