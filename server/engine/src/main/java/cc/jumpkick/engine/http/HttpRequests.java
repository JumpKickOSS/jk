// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Read a request body under a size cap; an oversized one is refused, never truncated. */
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

    private HttpRequests() {}

    /** The whole body as UTF-8, or {@link TooLarge} past {@link #MAX_BODY_BYTES}. */
    static String body(HttpExchange exchange) throws IOException {
        return body(exchange, MAX_BODY_BYTES);
    }

    /** The whole body as UTF-8, or {@link TooLarge} past {@code maxBytes}. */
    static String body(HttpExchange exchange, int maxBytes) throws IOException {
        byte[] raw = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (raw.length > maxBytes) throw new TooLarge(maxBytes);
        return new String(raw, StandardCharsets.UTF_8);
    }
}
