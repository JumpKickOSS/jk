// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import org.jspecify.annotations.Nullable;

/**
 * Where a dashboard link carries the engine's token for a browser to adopt: {@code #t=<token>} on
 * the bare dashboard, {@code t=<token>} in a route's fragment query ({@code #decision/<id>?t=…}).
 * The SPA stores it and scrubs it from the address bar on load; a fragment never leaves the
 * browser. The one spelling the engine (its own alerts) and the CLI (a person's terminal) sign
 * with; whether a link may be signed at all is theirs to decide.
 */
public final class DashboardSigning {

    private DashboardSigning() {}

    /** {@code link} carrying {@code token}; {@code link} itself when either is absent or blank. */
    public static @Nullable String signed(@Nullable String link, @Nullable String token) {
        if (link == null || link.isBlank() || token == null || token.isBlank()) return link;
        String t = token.strip();
        int hash = link.indexOf('#');
        if (hash < 0) return link + "#t=" + t;
        if (hash == link.length() - 1) return link + "t=" + t;
        return link + (link.indexOf('?', hash) < 0 ? '?' : '&') + "t=" + t;
    }
}
