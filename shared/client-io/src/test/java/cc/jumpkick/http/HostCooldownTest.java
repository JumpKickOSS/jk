// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * a 429 must cost one request, not one per permit per attempt.
 *
 * <p>The failure being fixed is not "jk gave up" but "jk kept asking". Retrying a rate limit six concurrent
 * permits deep, five attempts each, turns one refusal into thirty more requests at a host that already said
 * no — which plausibly holds the window open. So these assert on <em>request counts</em>, not just on
 * outcomes.
 */
class HostCooldownTest {

    private static Instant fixed(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void a_host_that_has_not_refused_is_not_cooling_down(@TempDir Path tmp) {
        assertThat(new HostCooldown(tmp).until("repo.example")).isEmpty();
    }

    @Test
    void a_refusal_starts_a_window(@TempDir Path tmp) {
        HostCooldown c = new HostCooldown(tmp);

        Instant until = c.noteRateLimited("repo.example", Optional.empty());

        assertThat(c.until("repo.example")).contains(until);
        assertThat(until).isAfter(Instant.now());
    }

    @Test
    void the_window_is_minutes_not_the_retry_ladder(@TempDir Path tmp) {
        // The existing 100ms-1.6s backoff is tuned for a transient 5xx. A quota is a different animal, and
        // coming back in a second is what keeps it open.
        HostCooldown c = new HostCooldown(tmp);

        Instant until = c.noteRateLimited("repo.example", Optional.empty());

        assertThat(Duration.between(Instant.now(), until)).isGreaterThan(Duration.ofSeconds(30));
    }

    @Test
    void retry_after_is_honoured_when_the_host_sends_one(@TempDir Path tmp) {
        HostCooldown c = new HostCooldown(tmp);

        Instant until = c.noteRateLimited("repo.example", Optional.of(Duration.ofSeconds(90)));

        Duration actual = Duration.between(Instant.now(), until);
        assertThat(actual).isBetween(Duration.ofSeconds(80), Duration.ofSeconds(95));
    }

    @Test
    void an_absurd_retry_after_is_clamped(@TempDir Path tmp) {
        // A header should never be able to wedge a build for a day.
        HostCooldown c = new HostCooldown(tmp);

        Instant until = c.noteRateLimited("repo.example", Optional.of(Duration.ofDays(30)));

        assertThat(Duration.between(Instant.now(), until)).isLessThanOrEqualTo(HostCooldown.MAX_COOLDOWN);
    }

    @Test
    void the_window_expires_on_its_own(@TempDir Path tmp) {
        Instant[] now = {fixed("2026-07-30T10:00:00Z")};
        HostCooldown c = new HostCooldown(tmp, () -> now[0]);
        c.noteRateLimited("repo.example", Optional.of(Duration.ofMinutes(5)));
        assertThat(c.until("repo.example")).isPresent();

        now[0] = fixed("2026-07-30T10:06:00Z");

        assertThat(c.until("repo.example")).isEmpty();
    }

    @Test
    void the_window_survives_a_restart(@TempDir Path tmp) {
        // A quota belongs to the host and this machine's IP, not to a process. Holding it in memory meant
        // `jk engine stop` between attempts forgot it and went straight back to hammering.
        new HostCooldown(tmp).noteRateLimited("repo.example", Optional.empty());

        assertThat(new HostCooldown(tmp).until("repo.example")).isPresent();
    }

    @Test
    void one_host_cooling_down_does_not_block_another(@TempDir Path tmp) {
        HostCooldown c = new HostCooldown(tmp);

        c.noteRateLimited("busy.example", Optional.empty());

        assertThat(c.until("busy.example")).isPresent();
        assertThat(c.until("quiet.example")).isEmpty();
    }

    @Test
    void retry_after_parses_seconds_and_dates_and_rejects_junk() {
        Instant now = fixed("2026-07-30T10:00:00Z");

        assertThat(HostCooldown.parseRetryAfter("120", now)).contains(Duration.ofSeconds(120));
        assertThat(HostCooldown.parseRetryAfter("  45 ", now)).contains(Duration.ofSeconds(45));
        assertThat(HostCooldown.parseRetryAfter("Thu, 30 Jul 2026 10:02:00 GMT", now))
                .contains(Duration.ofMinutes(2));
        // A date already past means nothing to wait for.
        assertThat(HostCooldown.parseRetryAfter("Thu, 30 Jul 2026 09:00:00 GMT", now))
                .isEmpty();
        assertThat(HostCooldown.parseRetryAfter("soon", now)).isEmpty();
        assertThat(HostCooldown.parseRetryAfter(null, now)).isEmpty();
        assertThat(HostCooldown.parseRetryAfter("", now)).isEmpty();
    }

    // ---- the behaviour that matters: request counts ------------------------------------------

    @Test
    void a_cooling_host_short_circuits_without_touching_the_network(@TempDir Path tmp) throws Exception {
        // The host does not resolve, so reaching the network at all would surface as an UnknownHostException.
        // Getting RateLimitedException instead is the proof that nothing was attempted.
        HostCooldown cooldown = new HostCooldown(tmp);
        cooldown.noteRateLimited("nonexistent.invalid", Optional.empty());
        Http http = new Http(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new Duration[] {Duration.ofMillis(1)},
                new CentralMirror(
                        tmp.resolve("m"), Duration.ofHours(4), true, "unused.example", "http://unused.example"),
                cooldown);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> http.get(URI.create("https://nonexistent.invalid/a/b.jar"), Map.of()))
                .isInstanceOf(RateLimitedException.class)
                .hasMessageContaining("rate-limiting")
                .hasMessageContaining("Re-running now makes it worse");
    }

    @Test
    void loopback_is_never_cooled_down(@TempDir Path tmp) throws Exception {
        // A cooldown protects a shared, metered, remote quota; loopback is none of those. Cooling it down
        // would block every other local request for minutes because one local server said 429 — and an
        // in-process test suite all shares this one host.
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/a/b.jar");
            HostCooldown cooldown = new HostCooldown(tmp);
            Http http = new Http(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build(),
                    new Duration[] {Duration.ofMillis(1)},
                    new CentralMirror(
                            tmp.resolve("m"), Duration.ofHours(4), true, "unused.example", "http://unused.example"),
                    cooldown);

            assertThat(http.get(uri, Map.of()).statusCode()).isEqualTo(429);
            assertThat(http.get(uri, Map.of()).statusCode()).isEqualTo(429);

            assertThat(hits.get()).as("both requests reached the local server").isEqualTo(2);
            assertThat(cooldown.until("127.0.0.1")).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void the_error_names_the_host_and_the_expiry() {
        Instant until = Instant.now().plus(Duration.ofMinutes(5));

        RateLimitedException e = new RateLimitedException("repo.maven.apache.org", until);

        assertThat(e.host()).isEqualTo("repo.maven.apache.org");
        assertThat(e.until()).isEqualTo(until);
        assertThat(e.getMessage()).contains("repo.maven.apache.org").contains(until.toString());
    }
}
