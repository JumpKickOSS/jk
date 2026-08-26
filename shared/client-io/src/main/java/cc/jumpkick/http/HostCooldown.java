// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Remembers that a host rate-limited us, and stops asking until it is worth asking again.
 *
 * <p>{@link Http} never retried a 4xx, 429 included, so a rate limit failed instantly and was forgotten
 * instantly. The expensive half was the forgetting: nothing stopped the next invocation — or the other
 * five concurrent workers under {@link HostRateLimiter}'s permits — from firing more requests at a host
 * that was actively refusing. A limit tripped by one heavy day did not clear across several hours of
 * intermittent retrying.
 *
 * <p>So the substance here is <em>not</em> retry. Retrying a 429 six permits deep, five attempts each,
 * would turn one refusal into thirty more requests. The substance is a cooldown: one 429 costs one
 * request, and every later request to that host short-circuits without touching the network.
 *
 * <h2>Why it is on disk</h2>
 *
 * A quota belongs to the host and the machine's IP, not to a process. Keeping it in memory meant {@code
 * jk engine stop} between attempts forgot it and went straight back to hammering — which is exactly what
 * happened while diagnosing the original incident. It lives beside the other fetched state so it survives
 * restarts, and it is stored as an explicit expiry rather than an mtime because {@code Retry-After} can
 * name any duration.
 *
 * <h2>Relationship to {@link CentralMirror}</h2>
 *
 * Orthogonal, and they compose. This decides <em>whether to ask a host at all</em>; the mirror decides
 * <em>where else to ask</em> when the host is Maven Central. A Central 429 opens the mirror window and the
 * request is reissued against the mirror, so the cooldown recorded for Central never blocks that recovery
 * — by the time a request is checked here it has already been routed.
 */
public final class HostCooldown {

    /**
     * Cooldown when the host does not say. Maven Central sent no {@code Retry-After} in measurement, so
     * this is the common case. Minutes, deliberately: the existing 100ms–1.6s retry ladder is tuned for a
     * transient 5xx and is the wrong order of magnitude for a quota — short enough that a build run later
     * in the session is not needlessly blocked, long enough that a tight re-run loop cannot keep the
     * window alive.
     */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(5);

    /** An absurd {@code Retry-After} is clamped rather than trusted; a header should not wedge a build. */
    public static final Duration MAX_COOLDOWN = Duration.ofHours(1);

    private static final String DIR_NAME = "host-cooldown";

    private final Path dir;
    private final Supplier<Instant> clock;

    public HostCooldown(Path stateRoot) {
        this(stateRoot, Instant::now);
    }

    HostCooldown(Path stateRoot, Supplier<Instant> clock) {
        this.dir = stateRoot.resolve(DIR_NAME);
        this.clock = clock;
    }

    /**
     * The shared instance, stored beside the other fetched state.
     *
     * <p>{@code jk.http.cooldown.dir} redirects it, following the same precedent as {@code jk.m2.local}.
     * Tests need it: the store is keyed by host, every in-process HTTP test serves from {@code 127.0.0.1},
     * and without a seam one test's simulated 429 would cool down loopback for every other test — and
     * write that record into the developer's real {@code JK_HOME} / platform product layout (the failure mode again).
     */
    public static HostCooldown standard() {
        String override = System.getProperty("jk.http.cooldown.dir");
        if (override != null && !override.isBlank()) return new HostCooldown(Path.of(override.trim()));
        return new HostCooldown(JkDirs.store());
    }

    /**
     * Loopback never cools down.
     *
     * <p>A cooldown exists to protect a shared, metered, remote quota. Loopback is none of those: it is a
     * local mirror, a proxy, or a test fixture. Blocking every local request for minutes because one local
     * server answered 429 is a worse failure than not backing off at all — and it bites immediately, since
     * a suite of in-process HTTP tests all share the host {@code 127.0.0.1}, so one simulated 429 would
     * cool down every other test.
     *
     * <p>Deliberately loopback only, not private ranges: a corporate Nexus on {@code 10.x} is a real shared
     * quota and should be honoured like any other.
     */
    static boolean exempt(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }

    /** When {@code host} may be asked again, or empty when it is not cooling down. */
    public Optional<Instant> until(String host) {
        if (exempt(host)) return Optional.empty();
        try {
            Path f = fileFor(host);
            if (!Files.isRegularFile(f)) return Optional.empty();
            Instant expiry = Instant.parse(Files.readString(f).strip());
            if (!expiry.isAfter(clock.get())) {
                Files.deleteIfExists(f); // expired: forget it rather than re-read it forever
                return Optional.empty();
            }
            return Optional.of(expiry);
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // unreadable: prefer asking the host over blocking a build
        }
    }

    /** True while {@code uri}'s host is cooling down. */
    public boolean active(URI uri) {
        return uri != null && until(uri.getHost()).isPresent();
    }

    /**
     * Record that {@code host} refused, honouring {@code retryAfter} when it sent one.
     *
     * <p>A later 429 extends the window: it means the quota is still in force. Never throws — losing the
     * record costs politeness, and failing a build over bookkeeping would be worse than the problem.
     */
    public Instant noteRateLimited(String host, Optional<Duration> retryAfter) {
        if (exempt(host)) return clock.get(); // see exempt: loopback is not a metered quota
        Duration window = retryAfter
                .filter(d -> !d.isNegative() && !d.isZero())
                .map(d -> d.compareTo(MAX_COOLDOWN) > 0 ? MAX_COOLDOWN : d)
                .orElse(DEFAULT_COOLDOWN);
        Instant expiry = clock.get().plus(window);
        try {
            Files.createDirectories(dir);
            Files.writeString(fileFor(host), expiry.toString());
        } catch (IOException | RuntimeException e) {
            // best-effort
        }
        return expiry;
    }

    /** Parse {@code Retry-After}: delta-seconds, or an HTTP date. Empty when absent or unparseable. */
    public static Optional<Duration> parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) return Optional.empty();
        String v = value.strip();
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(v)));
        } catch (NumberFormatException ignored) {
            // not delta-seconds; try the date form
        }
        try {
            Instant when =
                    ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration d = Duration.between(now, when);
            return d.isNegative() ? Optional.empty() : Optional.of(d);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Clear the record for {@code host} (used by tests and by an explicit user override). */
    public void clear(String host) {
        try {
            Files.deleteIfExists(fileFor(host));
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** One file per host, named by a hash so a host name can never escape the directory. */
    private Path fileFor(String host) {
        String key = Hashing.sha256Hex(host.toLowerCase(Locale.ROOT));
        return dir.resolve(key.substring(0, 16) + ".until");
    }
}
