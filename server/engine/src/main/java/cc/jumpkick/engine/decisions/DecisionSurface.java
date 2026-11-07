// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Where an answer came from. Only the host's two rungs and a terminal may settle a trust-changing decision. */
public enum DecisionSurface {
    /** The agent's host rendered the form (MCP form elicitation). */
    HOST_FORM,
    /** The page the agent's host opened by URL elicitation, holding the link's one-time key. */
    HOST_URL,
    /** The dashboard, with nothing but the bearer token behind it. */
    DASHBOARD,
    /** {@code jk decide} in a terminal. */
    CLI;

    /** {@code host-form}, {@code host-url}, {@code dashboard} or {@code cli}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** True when an answer from here proves a person decided, so it may settle a trust-changing decision. */
    public boolean provesAPerson() {
        return this != DASHBOARD;
    }

    public static Optional<DecisionSurface> parse(@Nullable String wire) {
        if (wire == null) return Optional.empty();
        for (DecisionSurface s : values()) {
            if (s.wire().equals(wire.strip())) return Optional.of(s);
        }
        return Optional.empty();
    }
}
