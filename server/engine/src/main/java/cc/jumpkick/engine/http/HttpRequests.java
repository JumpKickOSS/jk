// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Read a request body under a size cap (an oversized one is refused, never truncated), and turn what
 * a client sent into values, refusing what is malformed with {@link BadRequest}.
 */
final class HttpRequests {

    /** Cap on a request body ({@code POST /api/build} carries one flat object; 64 KiB is generous). */
    static final int MAX_BODY_BYTES = 64 * 1024;

    /** The body ran past its cap; the dispatcher answers 413 with {@link #maxBytes}. */
    static final class TooLarge extends RuntimeException {
        private final int maxBytes;

        TooLarge(int maxBytes) {
            super("request body larger than " + maxBytes + " bytes");
            this.maxBytes = maxBytes;
        }

        int maxBytes() {
            return maxBytes;
        }
    }

    /**
     * What the client sent cannot be read: a body that is not JSON, a string that is no path. The
     * dispatcher answers 400 with the message. An {@link IllegalArgumentException}, so a
     * handler that already catches that to answer 400 itself still does.
     */
    static final class BadRequest extends IllegalArgumentException {
        BadRequest(String message) {
            super(message);
        }
    }

    private HttpRequests() {}

    /** The body parsed as JSON (null for {@code null}), or {@link BadRequest} when it is not JSON. */
    static @Nullable Object json(HttpExchange exchange) throws IOException {
        String body = body(exchange);
        try {
            return MiniJson.parse(body);
        } catch (IllegalArgumentException e) {
            throw new BadRequest("the body is not JSON: " + e.getMessage());
        }
    }

    /** {@code raw} as a path, or {@link BadRequest} when this filesystem has no such path. */
    static Path path(String raw) {
        try {
            return Path.of(raw);
        } catch (InvalidPathException e) {
            throw new BadRequest("not a path: " + e.getReason());
        }
    }

    /** The whole body as UTF-8, or {@link TooLarge} past {@link #MAX_BODY_BYTES}. */
    static String body(HttpExchange exchange) throws IOException {
        return body(exchange, MAX_BODY_BYTES);
    }

    /** The whole body as UTF-8, or {@link TooLarge} past {@code maxBytes}. */
    static String body(HttpExchange exchange, int maxBytes) throws IOException {
        return new String(bytes(exchange, maxBytes), StandardCharsets.UTF_8);
    }

    /** The whole body, or {@link TooLarge} past {@code maxBytes}. */
    static byte[] bytes(HttpExchange exchange, int maxBytes) throws IOException {
        byte[] raw = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (raw.length > maxBytes) throw new TooLarge(maxBytes);
        return raw;
    }
}
