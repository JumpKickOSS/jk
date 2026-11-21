// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Read a request body under a size cap (an oversized one is refused, never truncated) and a deadline,
 * and turn what a client sent into values, refusing what is malformed with {@link BadRequest}.
 */
final class HttpRequests {

    /** Cap on a request body ({@code POST /api/build} carries one flat object; 64 KiB is generous). */
    static final int MAX_BODY_BYTES = 64 * 1024;

    /**
     * How long a client has to send its whole body. The handler reads it holding an admission
     * permit, so a client that sends its headers and then stalls must not keep it.
     */
    static final Duration BODY_DEADLINE = Duration.ofSeconds(30);

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

    /** The whole body, or {@link TooLarge} past {@code maxBytes}, within {@link #BODY_DEADLINE}. */
    static byte[] bytes(HttpExchange exchange, int maxBytes) throws IOException {
        return bytes(exchange, maxBytes, BODY_DEADLINE);
    }

    /**
     * The whole body, or {@link TooLarge} past {@code maxBytes}. Past {@code limit} a watchdog
     * interrupts the reading thread, which closes the connection under it, and the read throws.
     */
    static byte[] bytes(HttpExchange exchange, int maxBytes, Duration limit) throws IOException {
        Reading reading = new Reading(Thread.currentThread());
        ScheduledFuture<?> deadline =
                WriteDeadline.WATCHDOG.schedule(reading::expire, limit.toNanos(), TimeUnit.NANOSECONDS);
        byte[] raw;
        try {
            raw = exchange.getRequestBody().readNBytes(maxBytes + 1);
        } catch (IOException e) {
            throw reading.end() ? tooSlow(limit, e) : e;
        } finally {
            deadline.cancel(false);
            reading.end();
        }
        if (raw.length > maxBytes) throw new TooLarge(maxBytes);
        return raw;
    }

    private static IOException tooSlow(Duration limit, IOException cause) {
        return new IOException(
                "the client did not send its body within " + WriteDeadline.spoken(limit) + "; closed the request",
                cause);
    }

    /** One body read and its deadline: the interrupt lands only while the read is under way. */
    private static final class Reading {
        private final Thread reader;
        private boolean reading = true;
        private boolean expired;

        Reading(Thread reader) {
            this.reader = reader;
        }

        synchronized void expire() {
            if (!reading) return;
            expired = true;
            reader.interrupt();
        }

        /** Leaves the read; true when the deadline passed during it, its interrupt then cleared. */
        boolean end() {
            boolean wasExpired;
            synchronized (this) {
                reading = false;
                wasExpired = expired;
            }
            if (wasExpired) Thread.interrupted();
            return wasExpired;
        }
    }
}
