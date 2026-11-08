// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Where an answer came from. Every surface may give any answer. */
public enum DecisionSurface {
    /** The agent's host rendered the form (MCP form elicitation). */
    HOST_FORM,
    /** The agent's host, declining or dismissing the URL elicitation that links to the decision. */
    HOST_URL,
    /** The dashboard: the decision page, from the banner, a notification or a link. */
    DASHBOARD,
    /** {@code jk decide}. */
    CLI;

    /** {@code host-form}, {@code host-url}, {@code dashboard} or {@code cli}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<DecisionSurface> parse(@Nullable String wire) {
        if (wire == null) return Optional.empty();
        for (DecisionSurface s : values()) {
            if (s.wire().equals(wire.strip())) return Optional.of(s);
        }
        return Optional.empty();
    }
}
