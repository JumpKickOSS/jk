// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.host.time.Clock;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The requests {@link Http} is waiting on right now, process-wide, so a stall watch that fires
 * while a thread is parked inside a read can say which URL never answered. An entry lives from the
 * first attempt to the response or the failure, whatever the thread.
 */
public final class InFlightRequests {

    private record Entry(URI uri, long startNanos) {}

    private static final AtomicLong IDS = new AtomicLong();
    private static final Map<Long, Entry> IN_FLIGHT = new ConcurrentHashMap<>();

    private InFlightRequests() {}

    /** Register a request about to go out; the returned id ends it with {@link #end}. */
    static long begin(URI uri) {
        long id = IDS.incrementAndGet();
        IN_FLIGHT.put(id, new Entry(uri, Clock.SYSTEM.nanos()));
        return id;
    }

    static void end(long id) {
        IN_FLIGHT.remove(id);
    }

    /**
     * {@code waiting on <url> (<n> s)} for every request in flight, oldest first, joined by
     * {@code ", "}; empty when nothing is. Credentials in a URL are elided as in every message.
     */
    public static String waitingOn() {
        List<Entry> entries = new ArrayList<>(IN_FLIGHT.values());
        if (entries.isEmpty()) return "";
        entries.sort((a, b) -> Long.compare(a.startNanos(), b.startNanos()));
        long now = Clock.SYSTEM.nanos();
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (!out.isEmpty()) out.append(", ");
            out.append("waiting on ")
                    .append(SafeUri.forMessage(entry.uri()))
                    .append(" (")
                    .append(TimeUnit.NANOSECONDS.toSeconds(now - entry.startNanos()))
                    .append(" s)");
        }
        return out.toString();
    }
}
