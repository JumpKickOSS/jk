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
 *
 * <p>MCP subscribers may filter by {@code requestId} so multi-job engines only deliver one job's
 * events to a given SSE connection ({@code GET /mcp?requestId=N} or progress-token binding).
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

    /**
     * True when any {@link FrameStyle#DASHBOARD} subscription is attached. The vitals sampler
     * lifecycle keys off this — an MCP progress stream alone must not keep status/cache sampling
     * (and its 30s store walk) alive (JK-1512).
     */
    public boolean hasDashboardSubscribers() {
        for (Subscription s : subscriptions) {
            if (s.style == FrameStyle.DASHBOARD) return true;
        }
        return false;
    }

    /** Render {@code payload} and fan out to every matching subscriber in its preferred style. */
    public void publish(String type, JsonOut payload) {
        publish(type, payload, false);
    }

    /**
     * As {@link #publish} but delivered to {@link FrameStyle#DASHBOARD} subscriptions only —
     * engine chrome ({@code status}/{@code cache} vitals) that the MCP surface never advertised
     * (JK-1512).
     */
    public void publishDashboard(String type, JsonOut payload) {
        publish(type, payload, true);
    }

    /**
     * Deliver one frame to a single subscription — connect hydrate, never a broadcast. Existing
     * subscribers already hold these facts; re-broadcasting them duplicated chrome on every new
     * tab (JK-1523).
     */
    void deliverTo(Subscription s, String type, JsonOut payload) {
        long id = seq.incrementAndGet();
        String data = payload.toString();
        s.offerDroppingOldest(s.style == FrameStyle.MCP ? mcpFrame(id, type, data) : dashboardFrame(id, type, data));
    }

    private void publish(String type, JsonOut payload, boolean dashboardOnly) {
        long id = seq.incrementAndGet();
        String data = payload.toString();
        Long requestId = extractRequestId(data);
        for (Subscription s : subscriptions) {
            if (dashboardOnly && s.style == FrameStyle.MCP) continue;
            if (!s.accepts(requestId)) continue;
            s.offerDroppingOldest(
                    s.style == FrameStyle.MCP ? mcpFrame(id, type, data) : dashboardFrame(id, type, data));
        }
    }

    /** Dashboard SSE subscription (default for {@code GET /api/events}) — all events. */
    Subscription subscribe() {
        return subscribe(FrameStyle.DASHBOARD, null);
    }

    /** Subscription with the given frame style and no request filter. */
    Subscription subscribe(FrameStyle style) {
        return subscribe(style, null);
    }

    /**
     * Subscription with frame style and optional {@code requestId} filter. When {@code
     * requestIdFilter} is non-null, only events whose payload carries that {@code requestId} are
     * delivered (events without a requestId are dropped for filtered subscriptions).
     */
    Subscription subscribe(FrameStyle style, Long requestIdFilter) {
        Subscription s = new Subscription(this, style == null ? FrameStyle.DASHBOARD : style, requestIdFilter);
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

    /**
     * Best-effort parse of {@code "requestId": <number>} from a JsonOut object string. Returns
     * {@code null} when absent or unparseable.
     */
    static Long extractRequestId(String data) {
        if (data == null || data.isEmpty()) return null;
        int key = data.indexOf("\"requestId\"");
        if (key < 0) return null;
        int colon = data.indexOf(':', key + 11);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < data.length() && data.charAt(i) <= ' ') i++;
        int start = i;
        if (i < data.length() && data.charAt(i) == '-') i++;
        while (i < data.length() && data.charAt(i) >= '0' && data.charAt(i) <= '9') i++;
        if (i == start || (i == start + 1 && data.charAt(start) == '-')) return null;
        try {
            return Long.parseLong(data.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
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
        /** {@code null} = all events; non-null = only matching {@code requestId}. */
        private final Long requestIdFilter;

        private final BlockingQueue<String> frames = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        private Subscription(HttpEvents hub, FrameStyle style, Long requestIdFilter) {
            this.hub = hub;
            this.style = style;
            this.requestIdFilter = requestIdFilter;
        }

        boolean accepts(Long eventRequestId) {
            if (requestIdFilter == null) return true;
            return eventRequestId != null && requestIdFilter.equals(eventRequestId);
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
