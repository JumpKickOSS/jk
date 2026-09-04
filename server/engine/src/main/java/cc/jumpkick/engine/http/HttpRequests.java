// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Read a request body under the project's size cap. */
final class HttpRequests {

    /** Cap on a request body ({@code POST /api/build} carries one flat object; 64 KiB is generous). */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private HttpRequests() {}

    /** The whole body as UTF-8, truncated at {@link #MAX_BODY_BYTES}. */
    static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
    }
}
