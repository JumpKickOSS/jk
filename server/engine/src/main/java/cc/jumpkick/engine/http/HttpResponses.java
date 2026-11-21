// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Write a JSON, text or binary response with the project's header conventions and HEAD handling.
 * Each carries {@code Cache-Control: no-store}, so no cache keeps an answer that can carry build
 * output; the server sets {@code X-Content-Type-Options: nosniff} on every response as it enters.
 * Every body is written under a {@link WriteDeadline}, so a client that stops reading lets go of
 * the handler and its permit.
 */
final class HttpResponses {

    private HttpResponses() {}

    static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        write(exchange, status, body.getBytes(StandardCharsets.UTF_8), WriteDeadline.PER_WRITE);
    }

    /**
     * For a {@code HEAD}, send a JSON read's headers with 200 and return true, so a read that is costly
     * to compute answers before it computes; false for any other method. Call it once the request is
     * known to be well-formed, so a malformed one still answers its 400.
     */
    static boolean answeredHead(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("HEAD")) return false;
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, -1);
        return true;
    }

    static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        write(exchange, status, body.getBytes(StandardCharsets.UTF_8), WriteDeadline.PER_WRITE);
    }

    /** Binary response with an explicit content type (image preview raw endpoint). */
    static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        sendBytes(exchange, status, contentType, body, WriteDeadline.PER_WRITE);
    }

    /** As above, each slice of the body bounded by {@code limit}. */
    static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body, Duration limit)
            throws IOException {
        exchange.getResponseHeaders()
                .set("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        write(exchange, status, body, limit);
    }

    /**
     * The response body under a {@link WriteDeadline}: a client that stops reading is cut off, one
     * that reads slowly is not. Closing it flushes the last bytes under the deadline too.
     */
    static OutputStream body(HttpExchange exchange) {
        return new WriteDeadline(exchange.getResponseBody(), WriteDeadline.PER_WRITE);
    }

    private static void write(HttpExchange exchange, int status, byte[] body, Duration limit) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = new WriteDeadline(exchange.getResponseBody(), limit)) {
            out.write(body);
        }
    }
}
