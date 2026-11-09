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
 * visible, whether its window has focus, how long since the person last used it, and whether the
 * browser lets it notify. From that it picks the one channel a decision reaches the person by, so
 * nobody is notified twice: in the page when a tab is <em>active</em>, a browser notification when
 * another tab may show one, the native notification otherwise.
 *
 * <p>Active is more than visible: the tab is visible, its window has focus, and the person moved
 * the mouse or typed in it within {@link #IDLE_AFTER}. A dashboard showing on a second monitor
 * while the person works in their editor, or left open on a laptop nobody is at, is visible but
 * not active, and the operating system's notification still reaches them.
 *
 * <p>The engine pings each stream every {@link #PING_EVERY} and the tab answers with its state; it
 * also reports whenever its focus changes or it turns idle or active. A report older than {@link
 * #STALE_AFTER} counts for nothing: a tab the browser froze, or a machine that slept with its
 * connection half open, cannot show anything, so the native notification fires instead. A closed
 * stream leaves at once.
 */
public final class Dashboards {

    /** How often the engine asks each stream for its state. */
    public static final Duration PING_EVERY = Duration.ofSeconds(15);

    /** How old a report may be and still count: three pings, so one slow answer is not a fallback. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(45);

    /** How long after the person's last mouse or key input a focused, visible tab stays active. */
    public static final Duration IDLE_AFTER = Duration.ofMinutes(2);

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

    /**
     * What a tab says about itself: whether it is {@code visible}, whether its window is {@code
     * focused}, how many milliseconds ago the person last used it ({@code lastInputAgoMs}, negative
     * when never), and whether it may notify.
     */
    public record Presence(boolean visible, boolean focused, long lastInputAgoMs, Permission permission) {

        /** A tab that is visible and may notify as {@code permission} says, but is not in use. */
        public static Presence idle(boolean visible, Permission permission) {
            return new Presence(visible, false, -1, permission);
        }

        /** A visible, focused tab the person used a moment ago. */
        public static Presence active(Permission permission) {
            return new Presence(true, true, 0, permission);
        }

        /** True when this report, {@code elapsedMs} old, still says a person is using the tab. */
        boolean activeAfter(long elapsedMs) {
            return visible
                    && focused
                    && lastInputAgoMs >= 0
                    && lastInputAgoMs + Math.max(0, elapsedMs) <= IDLE_AFTER.toMillis();
        }
    }

    /** The channel a decision takes. */
    public sealed interface Channel {
        /** Tab {@code stream} is active: it shows the decision in the page, and nothing notifies. */
        record InApp(String stream) implements Channel {}

        /** No tab is active and tab {@code stream} may notify: it shows the browser notification. */
        record Browser(String stream) implements Channel {}

        /** No fresh tab can show it: the native notification. */
        record Native() implements Channel {}
    }

    /** One tab: where its notifications go, and its last report (none yet when {@code seen} is 0). */
    private record Tab(Consumer<Decision> notifier, Presence presence, long seen) {}

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
        tabs.put(stream, new Tab(notifier, Presence.idle(false, Permission.UNSUPPORTED), 0));
    }

    /** Stream {@code stream} ended. */
    public void close(String stream) {
        tabs.remove(stream);
    }

    /** What tab {@code stream} says about itself now; false when no such stream is open. */
    public boolean report(String stream, Presence presence) {
        long now = Math.max(1, nanos.getAsLong());
        return tabs.computeIfPresent(stream, (k, t) -> new Tab(t.notifier(), presence, now)) != null;
    }

    /**
     * The channel a decision raised now takes: the active tab's page (the freshest, should two
     * claim it), else a browser notification from the freshest tab that may show one, visible or
     * not, else the native notification.
     */
    public Channel channel() {
        long now = nanos.getAsLong();
        @Nullable String active = null;
        long activeSeen = Long.MIN_VALUE;
        @Nullable String browser = null;
        long browserSeen = Long.MIN_VALUE;
        for (Map.Entry<String, Tab> e : tabs.entrySet()) {
            Tab t = e.getValue();
            long age = now - t.seen();
            if (t.seen() == 0 || age > STALE_AFTER.toNanos()) continue;
            if (t.presence().activeAfter(age / 1_000_000L) && t.seen() > activeSeen) {
                active = e.getKey();
                activeSeen = t.seen();
            }
            if (t.presence().permission() == Permission.GRANTED && t.seen() > browserSeen) {
                browser = e.getKey();
                browserSeen = t.seen();
            }
        }
        if (active != null) return new Channel.InApp(active);
        return browser == null ? new Channel.Native() : new Channel.Browser(browser);
    }

    /** Hands {@code d} to tab {@code stream} to notify; false when that stream has gone. */
    public boolean notify(String stream, Decision d) {
        Tab t = tabs.get(stream);
        if (t == null) return false;
        t.notifier().accept(d);
        return true;
    }
}
