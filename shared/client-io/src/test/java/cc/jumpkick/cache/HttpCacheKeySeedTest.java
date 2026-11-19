// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.testing.FakeCacheServer;
import cc.jumpkick.http.Http;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /v0/key-seed}: a cache hands its authenticated clients one seed, a service
 * that issues none answers 404 and reads as none, and any other answer is a failure whose message
 * names no byte of a seed.
 */
class HttpCacheKeySeedTest {

    /** A made-up seed: 32 bytes no real cache issued. */
    private static final byte[] SEED = "made-up-seed-for-tests-0123456789".getBytes(StandardCharsets.UTF_8);

    private static HttpCacheBackend client(URI url, String token) {
        return new HttpCacheBackend(
                url, Http.failFast().withRequestTimeout(Duration.ofSeconds(5)), () -> Optional.of("Bearer " + token));
    }

    @Test
    void the_writer_and_the_reader_are_handed_the_same_seed_with_its_id() throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start().issueSeed("proj-1:1", SEED)) {
            CacheBackend.IssuedSeed writer = Objects.requireNonNull(
                    client(server.url(), FakeCacheServer.WRITER).keySeed());
            CacheBackend.IssuedSeed reader =
                    client(server.url(), FakeCacheServer.READER).keySeed();

            assertThat(writer).isEqualTo(reader);
            assertThat(writer.id()).isEqualTo("proj-1:1");
            assertThat(Arrays.equals(writer.seed(), SEED)).isTrue();
            assertThat(writer.toString()).as("printed, it names the id alone").isEqualTo("IssuedSeed[proj-1:1]");
            assertThat(server.calls()).contains("GET /v0/key-seed writer", "GET /v0/key-seed reader");
        }
    }

    @Test
    void a_service_that_issues_none_answers_404_which_reads_as_none() throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start()) {
            assertThat(client(server.url(), FakeCacheServer.READER).keySeed()).isNull();
        }
    }

    /** A service that withholds the seed from an identity that may only read answers it 404: none, not a failure. */
    @Test
    void a_reader_the_service_withholds_the_seed_from_reads_none() throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start().issueSeedToWritersOnly("org-1:1", SEED)) {
            assertThat(client(server.url(), FakeCacheServer.WRITER).keySeed()).isNotNull();
            assertThat(client(server.url(), FakeCacheServer.READER).keySeed()).isNull();
        }
    }

    @Test
    void a_refusal_or_an_outage_is_a_failure_not_a_missing_seed() throws Exception {
        try (FakeCacheServer server = FakeCacheServer.start().issueSeed("proj-1:1", SEED)) {
            assertThatThrownBy(() -> client(server.url(), "no-such-token").keySeed())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("401");
            server.failSeed(503);
            assertThatThrownBy(
                            () -> client(server.url(), FakeCacheServer.READER).keySeed())
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void a_seed_shorter_than_32_bytes_is_refused_without_quoting_it() throws Exception {
        byte[] shortSeed = "only-sixteen-byt".getBytes(StandardCharsets.UTF_8);
        try (FakeCacheServer server = FakeCacheServer.start().issueSeed("proj-1:1", shortSeed)) {
            assertThatThrownBy(
                            () -> client(server.url(), FakeCacheServer.READER).keySeed())
                    .isInstanceOf(IOException.class)
                    .hasMessageNotContaining("only-sixteen")
                    .hasMessageNotContaining(Base64.getEncoder().encodeToString(shortSeed));
        }
    }

    @Test
    void the_seed_scope_is_the_services_url_so_two_services_never_share_a_key() {
        HttpCacheBackend one = client(URI.create("https://Cache.Example.com/team-a/"), "t");
        HttpCacheBackend two = client(URI.create("https://cache.example.com:8443/team-a"), "t");

        assertThat(one.seedScope()).isEqualTo("https://cache.example.com/team-a");
        assertThat(two.seedScope()).isEqualTo("https://cache.example.com:8443/team-a");
    }
}
