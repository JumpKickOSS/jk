// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * the two legs of a build want opposite repositories, and the reasons are different.
 *
 * <p>Version <em>enumeration</em> decides which versions exist, so it asks Central and only falls back once
 * refused — a lagging mirror would otherwise resolve to stale versions, which is the failure that got the
 * {@code /repos/central/data/} path rejected outright. Artifact <em>bytes</em> are pinned by sha256 before
 * they are requested, so provenance cannot affect the outcome and the mirror's far larger concurrency
 * budget is free to use.
 */
class MirrorPreferenceTest {

    private static final URI CENTRAL_JAR =
            URI.create("https://repo.maven.apache.org/maven2/org/foo/bar/1.0/bar-1.0.jar");
    private static final URI CENTRAL_META =
            URI.create("https://repo.maven.apache.org/maven2/org/foo/bar/maven-metadata.xml");

    private static CentralMirror mirror(Path dir) {
        return new CentralMirror(dir, Duration.ofHours(4), true);
    }

    @Test
    void a_download_prefers_the_mirror_even_when_central_is_healthy(@TempDir Path tmp) {
        CentralMirror m = mirror(tmp);
        assertThat(m.active()).as("no 429 has happened").isFalse();

        assertThat(m.routeForDownload(CENTRAL_JAR))
                .hasToString(
                        "https://maven-central.storage-download.googleapis.com/maven2/org/foo/bar/1.0/bar-1.0.jar");
    }

    @Test
    void enumeration_stays_on_central_until_it_refuses(@TempDir Path tmp) {
        CentralMirror m = mirror(tmp);

        // route is the rate-limit reaction, not a preference.
        assertThat(m.route(CENTRAL_META)).isEqualTo(CENTRAL_META);

        m.noteRateLimited();

        assertThat(m.route(CENTRAL_META).toString()).contains("storage-download.googleapis.com");
    }

    @Test
    void the_download_preference_still_only_touches_central(@TempDir Path tmp) {
        CentralMirror m = mirror(tmp);
        for (String other : new String[] {
            "https://dl.google.com/dl/android/maven2/androidx/core/core-1.0.jar",
            "https://nexus.internal.example/repo/org/foo/bar-1.0.jar",
            "https://maven-central.storage-download.googleapis.com/maven2/org/foo/bar-1.0.jar"
        }) {
            assertThat(m.routeForDownload(URI.create(other))).hasToString(other);
        }
    }

    @Test
    void switching_the_mirror_off_disables_the_download_preference_too(@TempDir Path tmp) {
        CentralMirror off = new CentralMirror(tmp, Duration.ofHours(4), false);

        assertThat(off.routeForDownload(CENTRAL_JAR)).isEqualTo(CENTRAL_JAR);
    }

    @Test
    void the_download_preference_keeps_the_maven2_prefix() {
        // The /repos/central/data/ path also answers 200 but serves a 2019 snapshot. Landing there for
        // pinned bytes would fail the checksum rather than corrupt anything — but it would fail every
        // download, so the prefix is worth pinning here too.
        CentralMirror m = new CentralMirror(Path.of("/tmp/unused-" + System.nanoTime()), Duration.ofHours(4), true);

        assertThat(m.routeForDownload(CENTRAL_JAR).toString())
                .contains("/maven2/")
                .doesNotContain("/repos/central/data/");
    }

    @Test
    void the_mirror_gets_a_larger_concurrency_budget_than_central() {
        // Routing downloads at the mirror is only worth it if the cap follows; capping object storage at
        // Sonatype's 6 would leave most of the benefit unused.
        HostRateLimiter limiter = new HostRateLimiter(HostRateLimiter.DEFAULT_PERMITS);
        String mirrorHost = URI.create(CentralMirror.MIRROR_BASE).getHost();

        assertThat(limiter.permitsFor(mirrorHost)).isEqualTo(HostRateLimiter.MIRROR_PERMITS);
        assertThat(limiter.permitsFor(CentralMirror.CENTRAL_HOST)).isEqualTo(HostRateLimiter.DEFAULT_PERMITS);
        assertThat(HostRateLimiter.MIRROR_PERMITS).isGreaterThan(HostRateLimiter.DEFAULT_PERMITS);
    }

    @Test
    void a_deliberately_tighter_limiter_is_never_widened_by_the_override() {
        // A caller that asked for 2 means 2, mirror or not.
        HostRateLimiter tight = new HostRateLimiter(2);

        assertThat(tight.permitsFor(URI.create(CentralMirror.MIRROR_BASE).getHost()))
                .isEqualTo(2);
    }
}
