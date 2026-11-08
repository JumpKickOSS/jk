// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.config.UiAsk;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The decision queue with what its surfaces need around it: the dashboard link to a decision, the
 * {@code [ui] ask} setting and the alerts it drives, each withdrawn when its decision settles.
 */
public final class Decisions {

    /** The dashboard link to a decision; null while the dashboard is not serving. */
    @FunctionalInterface
    public interface Links {
        @Nullable
        String link(String id);
    }

    private final DecisionQueue queue;
    private final Links links;
    private volatile DecisionAlerts alerts = new DecisionAlerts();
    private volatile Supplier<UiAsk> ask = UiAsk::resolve;

    public Decisions(DecisionQueue queue, Links links) {
        this.queue = Objects.requireNonNull(queue);
        this.links = Objects.requireNonNull(links);
        queue.onChange(d -> {
            if (!d.open()) this.alerts.withdraw(d.id());
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
