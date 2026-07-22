// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE fan-out for {@code GET /api/events} and MCP progress streams ({@code GET /mcp} with {@code
 * Accept: text/event-stream}). Each subscriber has a bounded drop-oldest queue so a slow client
 * never backpressures a build. Skip building event JSON when {@link #hasSubscribers} is false.
 */
public final class HttpEvents {

    /** Per-subscriber frame buffer. A dashboard reads far faster than an engine emits; 256 is deep. */
    static final int QUEUE_CAPACITY = 256;

    /** Wire framing for a subscription. */
    public enum FrameStyle {
        /** Classic dashboard SSE: {@code event: <type>} + raw JSON data. */
        DASHBOARD,
        /**
         * MCP Streamable-HTTP style: {@code event: message} + JSON-RPC {@code
         * notifications/jk/event} params carrying the same facts as dashboard {@code data}.
         */
        MCP
    }

    private final AtomicLong seq = new AtomicLong();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();

    public boolean hasSubscribers() {
        return !subscriptions.isEmpty();
    }

    /** Render {@code payload} and fan out to every subscriber in its preferred style. */
    public void publish(String type, JsonOut payload) {
        long id = seq.incrementAndGet();
        String data = payload.toString();
        // Ensure schema + type on payload for MCP/JSONL convergence when the publisher omitted them.
        // Publishers already put requestId/kind/etc.; type is the SSE event name.
        for (Subscription s : subscriptions) {
            s.offerDroppingOldest(s.style == FrameStyle.MCP ? mcpFrame(id, type, data) : dashboardFrame(id, type, data));
        }
    }

    /** Dashboard SSE subscription (default for {@code GET /api/events}). */
    Subscription subscribe() {
        return subscribe(FrameStyle.DASHBOARD);
    }

    /** Subscription with the given frame style (MCP progress uses {@link FrameStyle#MCP}). */
    Subscription subscribe(FrameStyle style) {
        Subscription s = new Subscription(this, style == null ? FrameStyle.DASHBOARD : style);
        subscriptions.add(s);
        return s;
    }

    static String dashboardFrame(long id, String type, String data) {
        return "id: " + id + "\nevent: " + type + "\ndata: " + data + "\n\n";
    }

    /**
     * One MCP notification as an SSE {@code message} event. Params are the engine event JSON object
     * plus {@code event} (the dashboard type name) for clients that prefer a top-level name.
     */
    static String mcpFrame(long id, String type, String data) {
        // data is already a JSON object from JsonOut; inject event + wrap as JSON-RPC notification.
        String params;
        if (data != null && data.startsWith("{") && data.endsWith("}")) {
            // Insert "event":"<type>" after the opening brace (and schema if present stays).
            params = "{\"event\":" + jsonString(type) + "," + data.substring(1);
        } else {
            params = "{\"event\":" + jsonString(type) + ",\"data\":" + data + "}";
        }
        String rpc = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/jk/event\",\"params\":" + params + "}";
        return "id: " + id + "\nevent: message\ndata: " + rpc + "\n\n";
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** One client's view of the stream. Closing unregisters; frames after close are dropped. */
    static final class Subscription implements AutoCloseable {
        private final HttpEvents hub;
        private final FrameStyle style;
        private final BlockingQueue<String> frames = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        private Subscription(HttpEvents hub, FrameStyle style) {
            this.hub = hub;
            this.style = style;
        }

        /** The next frame, or {@code null} after {@code timeoutMillis} of quiet (heartbeat time). */
        String next(long timeoutMillis) throws InterruptedException {
            return frames.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private void offerDroppingOldest(String frame) {
            while (!frames.offer(frame)) {
                frames.poll(); // full: shed the oldest frame, never the publisher's time
            }
        }

        @Override
        public void close() {
            hub.subscriptions.remove(this);
        }
    }
}
