// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE fan-out for {@code GET /api/events}: each subscriber has a bounded drop-oldest queue so a
 * slow browser never backpressures a build. Skip building event JSON when {@link #hasSubscribers}
 * is false.
 */
public final class HttpEvents {

    /** Per-subscriber frame buffer. A dashboard reads far faster than an engine emits; 256 is deep. */
    static final int QUEUE_CAPACITY = 256;

    private final AtomicLong seq = new AtomicLong();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();

    public boolean hasSubscribers() {
        return !subscriptions.isEmpty();
    }

    /** Render {@code payload} as one SSE frame ({@code id}/{@code event}/{@code data}) and fan out. */
    public void publish(String type, JsonOut payload) {
        String frame = "id: " + seq.incrementAndGet() + "\nevent: " + type + "\ndata: " + payload + "\n\n";
        for (Subscription s : subscriptions) {
            s.offerDroppingOldest(frame);
        }
    }

    Subscription subscribe() {
        Subscription s = new Subscription(this);
        subscriptions.add(s);
        return s;
    }

    /** One SSE client's view of the stream. Closing unregisters; frames after close are dropped. */
    static final class Subscription implements AutoCloseable {
        private final HttpEvents hub;
        private final BlockingQueue<String> frames = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        private Subscription(HttpEvents hub) {
            this.hub = hub;
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
