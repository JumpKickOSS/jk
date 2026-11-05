// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Method+path router for {@code /api/*}: an exact path, or a prefix whose route takes one more path
 * segment ({@code /api/sessions/<id>}). Wrong method → 405, unknown → 404.
 */
final class ApiRouter {

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** {@code "GET /api/status"} → handler. Insertion-ordered only for readable test dumps. */
    private final Map<String, Handler> routes = new LinkedHashMap<>();

    /** {@code "GET /api/sessions/"} → handler for {@code /api/sessions/<segment>}. */
    private final Map<String, Handler> prefixes = new LinkedHashMap<>();

    void register(String method, String path, Handler handler) {
        routes.put(method + " " + path, handler);
    }

    /** Route {@code prefix + <one segment>}; {@code prefix} ends with {@code /}. */
    void registerPrefix(String method, String prefix, Handler handler) {
        if (!prefix.endsWith("/")) throw new IllegalArgumentException("a route prefix ends with /: " + prefix);
        prefixes.put(method + " " + prefix, handler);
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Handler handler = routes.get(exchange.getRequestMethod() + " " + path);
        if (handler == null) handler = prefixes.get(exchange.getRequestMethod() + " " + prefixOf(path));
        if (handler != null) {
            handler.handle(exchange);
            return;
        }
        String allow = allowedMethods(path);
        if (!allow.isEmpty()) {
            exchange.getResponseHeaders().set("Allow", allow);
            HttpResponses.sendText(exchange, 405, "method not allowed\n");
            return;
        }
        HttpResponses.sendText(exchange, 404, "no such endpoint\n");
    }

    /** {@code path} up to and including its last {@code /} when one segment follows it, else {@code ""}. */
    private static String prefixOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 && slash < path.length() - 1 ? path.substring(0, slash + 1) : "";
    }

    private String allowedMethods(String path) {
        String suffix = " " + path;
        String prefix = " " + prefixOf(path);
        return Stream.concat(
                        routes.keySet().stream().filter(key -> key.endsWith(suffix)),
                        prefixes.keySet().stream().filter(key -> prefix.length() > 1 && key.endsWith(prefix)))
                .map(key -> key.substring(0, key.indexOf(' ')))
                .collect(Collectors.joining(", "));
    }
}
