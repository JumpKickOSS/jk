// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.config.UiAsk;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The decision queue with what its surfaces need around it: the dashboard link to a decision, the
 * {@code [ui] ask} setting and the alerts it drives, each withdrawn when its decision settles. Under
 * {@code notify} the {@link Dashboards} pick one channel, so a person is told once.
 */
public final class Decisions {

    /** The decision showed in a dashboard page someone was using. */
    public static final String CHANNEL_IN_APP = "in-app";

    /** A dashboard tab showed a browser notification. */
    public static final String CHANNEL_BROWSER = "browser";

    /** The operating system's notification. */
    public static final String CHANNEL_NATIVE = "native";

    /** {@code [ui] ask = open}: the browser opened on the decision. */
    public static final String CHANNEL_OPEN = "open";

    /** {@code [ui] ask = quiet}: only the link in the tool result. */
    public static final String CHANNEL_NONE = "none";

    /**
     * The dashboard link to a decision; null while the dashboard is not serving. It carries no
     * token: it is answered to MCP, {@code jk decide} and the API, and lands in transcripts and logs.
     */
    @FunctionalInterface
    public interface Links {
        @Nullable
        String link(String id);
    }

    private final DecisionQueue queue;
    private final Links links;
    private final Dashboards dashboards;
    private volatile DecisionAlerts alerts = new DecisionAlerts();
    private volatile Supplier<UiAsk> ask = UiAsk::resolve;
    private volatile BooleanSupplier relayed = () -> false;
    private volatile Consumer<Decision> relay = d -> {};
    private volatile UnaryOperator<String> signed = UnaryOperator.identity();

    /** The channel {@link #alert} records when a front door raises the notification instead. */
    static final String CHANNEL_FRONT_DOOR = "front-door";

    public Decisions(DecisionQueue queue, Links links, Dashboards dashboards) {
        this.queue = Objects.requireNonNull(queue);
        this.links = Objects.requireNonNull(links);
        this.dashboards = Objects.requireNonNull(dashboards);
        queue.onChange(d -> {
            if (!d.open()) this.alerts.withdraw(d.id());
        });
        queue.links(links::link);
        queue.onAlert(d -> {
            String link = links.link(d.id());
            if (d.open() && link != null) alert(d, link);
        });
    }

    public DecisionQueue queue() {
        return queue;
    }

    public @Nullable String link(String id) {
        return links.link(id);
    }

    public DecisionAlerts alerts() {
        return alerts;
    }

    /**
     * Tells the person about fresh decision {@code d}, whose dashboard link is {@code link} (signed
     * here, {@link #signAlerts}), as
     * {@code [ui] ask} says; answers what happened. Under {@code notify}: nothing when a dashboard is
     * in use (it shows the decision in the page), a browser notification from a dashboard that may
     * show one, else the native notification.
     */
    public String alert(Decision d, String link) {
        return tell(d, signed.apply(link));
    }

    /**
     * Sign the link of every alert this engine raises itself with {@code sign}: the browser it
     * opens and the notification it shows are seen by the person at this machine and captured by
     * nothing, so they carry the token the answers leave out.
     */
    public Decisions signAlerts(UnaryOperator<String> sign) {
        this.signed = Objects.requireNonNull(sign);
        return this;
    }

    private String tell(Decision d, String link) {
        UiAsk mode = ask();
        if (mode == UiAsk.QUIET) return told(d, CHANNEL_NONE, alerts.raise(mode, d, link));
        if (relayed.getAsBoolean()) {
            // The front door raises it, named for this engine: a guest never notifies for itself then.
            relay.accept(d);
            return told(d, CHANNEL_FRONT_DOOR, "the front door raises it");
        }
        if (mode == UiAsk.OPEN) return told(d, CHANNEL_OPEN, alerts.raise(mode, d, link));
        return switch (dashboards.channel()) {
            case Dashboards.Channel.InApp i ->
                told(d, CHANNEL_IN_APP, "a dashboard is in use: it shows it in the page");
            case Dashboards.Channel.Browser b ->
                dashboards.notify(b.stream(), d)
                        ? told(d, CHANNEL_BROWSER, "sent a browser notification")
                        : told(d, CHANNEL_NATIVE, alerts.raise(mode, d, link));
            case Dashboards.Channel.Native n -> told(d, CHANNEL_NATIVE, alerts.raise(mode, d, link));
        };
    }

    /** Records {@code channel} as how {@code d} reached the person, and answers {@code what}. */
    private String told(Decision d, String channel, String what) {
        queue.notified(d.id(), channel);
        return what;
    }

    /** The current {@code [ui] ask}, read afresh each time so an edit applies without a restart. */
    public UiAsk ask() {
        return ask.get();
    }

    /**
     * While {@code relayed} holds, a decision's notification goes to {@code publish} (the {@code
     * alert} event a front door subscribes to) instead of this engine's desktop.
     */
    public Decisions relay(BooleanSupplier relayed, Consumer<Decision> publish) {
        this.relayed = Objects.requireNonNull(relayed);
        this.relay = Objects.requireNonNull(publish);
        return this;
    }

    /**
     * On the front door: tells the person about another engine's decision {@code d} as this engine's
     * {@code [ui] ask} says, the dashboard link {@code link} under that engine's prefix. Nothing is
     * recorded in this engine's queue: the decision is the other engine's.
     */
    public String relayAlert(Decision d, String link) {
        UiAsk mode = ask();
        return alerts.raise(
                mode == UiAsk.OPEN ? UiAsk.OPEN : mode == UiAsk.QUIET ? UiAsk.QUIET : UiAsk.NOTIFY, d, link);
    }

    /** Raise alerts through {@code alerts}; tests only. */
    public Decisions alerts(DecisionAlerts alerts) {
        this.alerts = Objects.requireNonNull(alerts);
        return this;
    }

    /** Read {@code [ui] ask} from {@code ask}; tests only. */
    public Decisions ask(Supplier<UiAsk> ask) {
        this.ask = Objects.requireNonNull(ask);
        return this;
    }
}
