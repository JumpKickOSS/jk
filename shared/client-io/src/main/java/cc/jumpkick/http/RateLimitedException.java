// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * A host is refusing requests until {@link #until}.
 *
 * <p>Distinct from a generic {@code HTTP 429 fetching <url>} because the two call for opposite responses.
 * A plain failure invites the user to re-run, and re-running is precisely what keeps a quota window open.
 * This names the condition, the host, when it lifts, and says so.
 */
public final class RateLimitedException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String host;
    private final Instant until;

    public RateLimitedException(String host, Instant until) {
        super(message(host, until));
        this.host = host;
        this.until = until;
    }

    private static String message(String host, Instant until) {
        long secs = Math.max(0, Duration.between(Instant.now(), until).toSeconds());
        String wait = secs >= 120 ? (secs / 60) + " minutes" : secs + "s";
        return host + " is rate-limiting this machine — waiting " + wait + " (until " + until + ").\n"
                + "  Re-running now makes it worse: refused requests still count against the quota.\n"
                + "  jk will resume automatically once the window lifts.";
    }

    public String host() {
        return host;
    }

    public Instant until() {
        return until;
    }
}
