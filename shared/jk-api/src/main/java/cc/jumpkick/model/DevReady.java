// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import org.jspecify.annotations.Nullable;

/**
 * {@code [dev] ready} / {@code ready-pattern} / {@code ready-timeout}: how {@code jk dev} tells that
 * the application itself is listening, probed the way a sidecar's {@code ready} is — {@code url} an
 * HTTP(S) address polled for 2xx/3xx, {@code pattern} a regex over the app's output lines, one or
 * the other, and {@code timeoutMillis} the bound on either. Without the table the app is ready the
 * moment it is forked, which is what {@code dev-ready} meant before an app could say otherwise.
 */
public record DevReady(@Nullable String url, @Nullable String pattern, long timeoutMillis) {

    /** Default {@code ready-timeout}, the same as a sidecar's. */
    public static final long DEFAULT_TIMEOUT_MILLIS = Sidecar.DEFAULT_READY_TIMEOUT_MILLIS;

    public DevReady {
        if (url != null && url.isBlank()) url = null;
        if (pattern != null && pattern.isBlank()) pattern = null;
        if (url == null && pattern == null) {
            throw new IllegalArgumentException("[dev] ready-timeout needs a probe: ready or ready-pattern");
        }
        if (url != null && pattern != null) {
            throw new IllegalArgumentException("[dev] sets both ready and ready-pattern — the app has one probe");
        }
        if (timeoutMillis <= 0) timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    }
}
