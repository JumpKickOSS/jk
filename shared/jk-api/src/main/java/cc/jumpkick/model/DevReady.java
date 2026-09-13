// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import org.jspecify.annotations.Nullable;

/**
 * A readiness probe — {@code ready} / {@code ready-pattern} / {@code ready-timeout}, under
 * {@code [dev]} for the application itself and under a {@code [dev.sidecars]} entry for a sidecar:
 * {@code url} an HTTP(S) address polled for 2xx/3xx, {@code pattern} a regex over the process's
 * output lines, one or the other, and {@code timeoutMillis} the bound on either. One model for both
 * because {@code jk dev} runs one probe over both. A process without one is ready the moment it is
 * forked (the app) or once it has stayed alive for a second (a sidecar).
 */
public record DevReady(@Nullable String url, @Nullable String pattern, long timeoutMillis) {

    /** Default {@code ready-timeout}: a Vite or webpack cold start on a slow laptop fits in it. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 60_000;

    public DevReady {
        if (url != null && url.isBlank()) url = null;
        if (pattern != null && pattern.isBlank()) pattern = null;
        if (url == null && pattern == null) {
            throw new IllegalArgumentException("ready-timeout needs a probe: ready or ready-pattern");
        }
        if (url != null && pattern != null) {
            throw new IllegalArgumentException("sets both ready and ready-pattern — one probe per process");
        }
        if (timeoutMillis <= 0) timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    }
}
