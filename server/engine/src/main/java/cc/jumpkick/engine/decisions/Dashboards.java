// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard tabs holding an event stream, and what each last said about itself: whether it is
 * visible and whether the browser lets it notify. From that it picks the one channel a decision
 * reaches the person by, so nobody is notified twice: in the page when a tab is visible, a browser
 * notification when a hidden tab may show one, the native notification otherwise.
 *
 * <p>The engine pings each stream every {@link #PING_EVERY} and the tab answers with its state. A
 * report older than {@link #STALE_AFTER} counts for nothing: a tab the browser froze, or a machine
 * that slept with its connection half open, cannot show anything, so the native notification fires
 * instead. A closed stream leaves at once.
 */
public final class Dashboards {

    /** How often the engine asks each stream for its state. */
    public static final Duration PING_EVERY = Duration.ofSeconds(15);

    /** How old a report may be and still count: three pings, so one slow answer is not a fallback. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(45);

    /** {@code Notification.permission} as the tab reports it; {@code unsupported} where there is none. */
    public enum Permission {
        DEFAULT,
        GRANTED,
        DENIED,
        UNSUPPORTED;

        /** {@code default}, {@code granted}, {@code denied}; anything else is {@link #UNSUPPORTED}. */
        public static Permission parse(@Nullable String wire) {
            return switch (wire == null ? "" : wire.strip().toLowerCase(Locale.ROOT)) {
                case "default" -> DEFAULT;
                case "granted" -> GRANTED;
                case "denied" -> DENIED;
                default -> UNSUPPORTED;
            };
        }
    }

    /** The channel a decision takes. */
    public sealed interface Channel {
        /** A tab is visible: its bell shows the decision, and nothing notifies. */
        record InApp() implements Channel {}

        /** No tab is visible and tab {@code stream} may notify: it shows the browser notification. */
        record Browser(String stream) implements Channel {}

        /** No fresh tab can show it: the native notification. */
        record Native() implements Channel {}
    }

    /** One tab: where its notifications go, and its last report (none yet when {@code seen} is 0). */
    private record Tab(Consumer<Decision> notifier, boolean visible, Permission permission, long seen) {}

    private final Map<String, Tab> tabs = new ConcurrentHashMap<>();
    private final LongSupplier nanos;

    public Dashboards() {
        this(Clock.SYSTEM::nanos);
    }

    /** Against {@code nanos} as the clock; tests step it. */
    public Dashboards(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /** Stream {@code stream} opened; {@code notifier} hands a decision to that tab to show. */
    public void open(String stream, Consumer<Decision> notifier) {
        tabs.put(stream, new Tab(notifier, false, Permission.UNSUPPORTED, 0));
    }

    /** Stream {@code stream} ended. */
    public void close(String stream) {
        tabs.remove(stream);
    }

    /** What tab {@code stream} says about itself now; false when no such stream is open. */
    public boolean report(String stream, boolean visible, Permission permission) {
        long now = Math.max(1, nanos.getAsLong());
        return tabs.computeIfPresent(stream, (k, t) -> new Tab(t.notifier(), visible, permission, now)) != null;
    }

    /** The channel a decision raised now takes. */
    public Channel channel() {
        long now = nanos.getAsLong();
        @Nullable String freshest = null;
        long freshestSeen = Long.MIN_VALUE;
        for (Map.Entry<String, Tab> e : tabs.entrySet()) {
            Tab t = e.getValue();
            if (t.seen() == 0 || now - t.seen() > STALE_AFTER.toNanos()) continue;
            if (t.visible()) return new Channel.InApp();
            if (t.permission() == Permission.GRANTED && t.seen() > freshestSeen) {
                freshest = e.getKey();
                freshestSeen = t.seen();
            }
        }
        return freshest == null ? new Channel.Native() : new Channel.Browser(freshest);
    }

    /** Hands {@code d} to tab {@code stream} to notify; false when that stream has gone. */
    public boolean notify(String stream, Decision d) {
        Tab t = tabs.get(stream);
        if (t == null) return false;
        t.notifier().accept(d);
        return true;
    }
}
