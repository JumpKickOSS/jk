// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.config.UiAsk;
import java.util.Objects;
import java.util.function.Supplier;
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

    /** The dashboard link to a decision; null while the dashboard is not serving. */
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
     * Tells the person about fresh decision {@code d}, whose dashboard link is {@code link}, as
     * {@code [ui] ask} says; answers what happened. Under {@code notify}: nothing when a dashboard is
     * in use (it shows the decision in the page), a browser notification from a dashboard that may
     * show one, else the native notification.
     */
    public String alert(Decision d, String link) {
        UiAsk mode = ask();
        if (mode == UiAsk.QUIET) return told(d, CHANNEL_NONE, alerts.raise(mode, d, link));
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
