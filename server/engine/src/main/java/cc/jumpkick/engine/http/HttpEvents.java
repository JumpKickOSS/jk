// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.JsonOut;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SSE fan-out for {@code GET /api/events} and MCP progress streams ({@code GET /mcp} with {@code
 * Accept: text/event-stream}). Each subscriber has a bounded queue so a slow client never
 * backpressures a build. When full, <strong>low-priority</strong> frames ({@code output},
 * {@code plan-progress}, {@code label}, chrome) are shed before <strong>critical</strong> ones
 * ({@code workspace-progress}, {@code eta}, structural task/module/request events) — a dump of
 * compiler output must not freeze the dashboard bar while the CLI TUI keeps ticking.
 *
 * <p>MCP subscribers may filter by {@code requestId} so multi-job engines only deliver one job's
 * events to a given SSE connection ({@code GET /mcp?requestId=N} or progress-token binding).
 */
public final class HttpEvents {

    /**
     * Per-subscriber frame buffer. Deep enough for a burst of structural events; priority shedding
     * keeps progress frames when output floods.
     */
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
     * (and its 60s store walk) alive.
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
     * engine chrome ({@code status}/{@code cache} vitals) that the MCP surface never advertised.
     */
    public void publishDashboard(String type, JsonOut payload) {
        publish(type, payload, true);
    }

    /**
     * Deliver one frame to a single subscription — connect hydrate / mid-flight run-snapshot,
     * never a broadcast. Existing subscribers already hold live facts; re-broadcasting them
     * duplicated chrome and could queue-stall a late tab.
     */
    public void deliverTo(Subscription s, String type, JsonOut payload) {
        long id = seq.incrementAndGet();
        String data = payload.toString();
        s.offer(type, s.style == FrameStyle.MCP ? mcpFrame(id, type, data) : dashboardFrame(id, type, data));
    }

    private void publish(String type, JsonOut payload, boolean dashboardOnly) {
        long id = seq.incrementAndGet();
        String data = payload.toString();
        Long requestId = extractRequestId(data);
        for (Subscription s : subscriptions) {
            if (dashboardOnly && s.style == FrameStyle.MCP) continue;
            if (!s.accepts(requestId)) continue;
            s.offer(type, s.style == FrameStyle.MCP ? mcpFrame(id, type, data) : dashboardFrame(id, type, data));
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
        Subscription s = subscribeDetached(style, requestIdFilter);
        attach(s);
        return s;
    }

    /**
     * Create a subscription that does NOT yet receive broadcasts. The dashboard connect path
     * hydrates it (vitals + one {@code run-snapshot} per in-flight job via {@link #deliverTo})
     * and only then {@link #attach}es it, under the engine's connect ordering lock — so every
     * event is either reflected in the snapshot or delivered to the queue, never lost in the
     * subscribe→snapshot window.
     */
    Subscription subscribeDetached(FrameStyle style, Long requestIdFilter) {
        return new Subscription(this, style == null ? FrameStyle.DASHBOARD : style, requestIdFilter);
    }

    /**
     * Register a (detached) subscription for broadcasts. Idempotent; a subscription closed
     * before attach stays out of the hub. Public so the engine can attach inside its connect
     * ordering lock.
     */
    public void attach(Subscription s) {
        if (s == null || s.closed) return;
        subscriptions.add(s);
        // close() may have raced between the check and the add; never leave a closed
        // subscription in the hub (hasSubscribers() would stay true forever).
        if (s.closed) subscriptions.remove(s);
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
     * Critical for live dashboard UX — never shed these for an {@code output} flood. Everything
     * else (compiler lines, fine-grained plan ticks, labels, host vitals) is best-effort.
     */
    static boolean isCritical(String type) {
        if (type == null) return false;
        return switch (type) {
            case "workspace-progress",
                    "eta",
                    "request-start",
                    "request-finish",
                    "run-snapshot",
                    "task-start",
                    "task-finish",
                    "module-start",
                    "module-finish",
                    "buildplan-finish",
                    "plan",
                    "diagnostic" -> true;
            default -> false;
        };
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
    public static final class Subscription implements AutoCloseable {
        private final HttpEvents hub;
        private final FrameStyle style;
        /** {@code null} = all events; non-null = only matching {@code requestId}. */
        private final Long requestIdFilter;

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final ArrayDeque<Queued> frames = new ArrayDeque<>(QUEUE_CAPACITY);
        private volatile boolean closed;

        private record Queued(boolean critical, String wire) {}

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
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            lock.lock();
            try {
                while (frames.isEmpty()) {
                    if (closed) return null;
                    long wait = deadline - System.nanoTime();
                    if (wait <= 0) return null;
                    notEmpty.awaitNanos(wait);
                }
                Queued q = frames.pollFirst();
                return q == null ? null : q.wire;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Drain up to {@code max} frames without blocking — used by the SSE writer to batch a
         * burst into one socket write/flush so a full queue does not force 256 syscalls.
         */
        int drainTo(List<String> out, int max) {
            if (out == null || max <= 0) return 0;
            lock.lock();
            try {
                int n = 0;
                while (n < max) {
                    Queued q = frames.pollFirst();
                    if (q == null) break;
                    out.add(q.wire);
                    n++;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Enqueue {@code wire} for event {@code type}. Never blocks the publisher. When full:
         * the OLDEST low-priority frame is evicted so the freshest sample survives;
         * an incoming low-priority frame is dropped only when the queue is all critical, and a
         * critical frame then evicts the oldest critical (classic drop-oldest).
         */
        void offer(String type, String wire) {
            if (wire == null || closed) return;
            boolean critical = isCritical(type);
            lock.lock();
            try {
                if (closed) return;
                if (frames.size() >= QUEUE_CAPACITY) {
                    // Evict the OLDEST low-priority frame in either case: dropping the incoming
                    // frame kept hours-stale output/label frames while discarding fresh ones,
                    // inverting the coalescer's "latest wins" sampling upstream.
                    if (!evictOldestNonCriticalLocked()) {
                        if (!critical) {
                            // Queue is all critical — a low-priority frame loses to structure.
                            return;
                        }
                        frames.pollFirst(); // all critical — classic drop-oldest
                    }
                }
                frames.addLast(new Queued(critical, wire));
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }

        private boolean evictOldestNonCriticalLocked() {
            for (Iterator<Queued> it = frames.iterator(); it.hasNext(); ) {
                if (!it.next().critical) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            closed = true;
            hub.subscriptions.remove(this);
            lock.lock();
            try {
                frames.clear();
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
