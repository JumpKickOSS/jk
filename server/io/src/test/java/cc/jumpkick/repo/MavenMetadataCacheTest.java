// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.HostRateLimiter;
import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TTL + conditional-GET behaviour of the maven-metadata.xml cache. */
class MavenMetadataCacheTest {

    private static final String ETAG = "\"v1\"";
    private static final byte[] BODY = ("""
            <metadata><groupId>g</groupId><artifactId>a</artifactId>
            <versioning><versions><version>1.0</version></versions></versioning></metadata>
            """).getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private URI uri;
    private AtomicInteger hits;
    private volatile int forceStatus; // when >0, reply with this status (e.g. 429/404)
    private volatile int unconditional304s; // while >0, answer 304 whatever the request carries
    private volatile @Nullable String lastIfNoneMatch;

    @BeforeEach
    void start() throws IOException {
        hits = new AtomicInteger();
        forceStatus = 0;
        unconditional304s = 0;
        lastIfNoneMatch = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/g/a/maven-metadata.xml", exchange -> {
            hits.incrementAndGet();
            lastIfNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (forceStatus != 0) {
                exchange.sendResponseHeaders(forceStatus, -1);
                exchange.close();
                return;
            }
            if (unconditional304s > 0) {
                unconditional304s--;
                exchange.sendResponseHeaders(304, -1);
            } else if (ETAG.equals(lastIfNoneMatch)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("ETag", ETAG);
                exchange.sendResponseHeaders(200, BODY.length);
                exchange.getResponseBody().write(BODY);
            }
            exchange.close();
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/g/a/maven-metadata.xml");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private MavenMetadataCache cache(Path dir, Duration ttl) {
        return new MavenMetadataCache(new Http(), dir, ttl);
    }

    @Test
    void within_ttl_serves_from_disk_without_a_second_request(@TempDir Path dir) throws Exception {
        MavenMetadataCache cache = cache(dir, Duration.ofHours(1));

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
        assertThat(hits.get()).isEqualTo(1);

        // Second resolve within the TTL must not touch the network at all.
        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void past_ttl_revalidates_with_conditional_get_and_reuses_on_304(@TempDir Path dir) throws Exception {
        MavenMetadataCache cache = cache(dir, Duration.ZERO); // always stale → always revalidate

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY); // 200, stores ETag
        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY); // conditional → 304

        assertThat(hits.get()).isEqualTo(2);
        assertThat(lastIfNoneMatch).isEqualTo(ETAG); // the revalidation was conditional
    }

    @Test
    void server_refusal_falls_back_to_the_cached_copy(@TempDir Path dir) throws Exception {
        MavenMetadataCache cache = cache(dir, Duration.ZERO);

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY); // warm the cache
        forceStatus = 429; // now rate-limited

        // Rather than fail the resolve, the stale-but-usable copy is returned.
        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
    }

    @Test
    void missing_metadata_surfaces_as_not_found(@TempDir Path dir) {
        forceStatus = 404;
        MavenMetadataCache cache = cache(dir, Duration.ofHours(1));

        assertThatThrownBy(() -> cache.fetch(uri, RepoCredential.ANONYMOUS))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
    }

    @Test
    void with_force_revalidate_skips_ttl_and_hits_the_network(@TempDir Path dir) throws Exception {
        // jk update / -F use this so float-to-latest sees newly published versions that a warm
        // TTL would otherwise hide. Normal jk lock must NOT wrap in withForceRevalidate.
        MavenMetadataCache cache = cache(dir, Duration.ofHours(1));

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
        assertThat(hits.get()).isEqualTo(1);

        // Back-to-back fetch within TTL: still local-only.
        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
        assertThat(hits.get()).isEqualTo(1);

        MavenMetadataCache.withForceRevalidate(() -> {
            assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
            return null;
        });
        // Conditional GET (304) — still a network hop, not a pure TTL hit.
        assertThat(hits.get()).isEqualTo(2);
        assertThat(lastIfNoneMatch).isEqualTo(ETAG);
    }

    @Test
    void a_validator_sidecar_without_its_body_is_a_cold_fetch_that_rewrites_the_sidecar(@TempDir Path dir)
            throws Exception {
        MavenMetadataCache cache = cache(dir, Duration.ZERO);
        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY); // 200: body + validators
        Path body = dir.resolve(Hashing.sha256Hex(uri.toString()));
        Path sidecar = body.resolveSibling(body.getFileName() + ".h");
        assertThat(sidecar).content().startsWith(ETAG);

        // The body goes; the sidecar survives, as a partial prune or a hand-cleared cache leaves it.
        Files.delete(body);
        lastIfNoneMatch = "unset";

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
        assertThat(hits.get()).as("one extra GET, not an exception").isEqualTo(2);
        assertThat(lastIfNoneMatch)
                .as("no validators are sent for a body that is not there")
                .isNull();
        assertThat(body).hasBinaryContent(BODY);
        assertThat(sidecar).content().startsWith(ETAG); // the new response's validators
    }

    @Test
    void a_304_with_no_cached_body_is_refetched_without_validators(@TempDir Path dir) throws Exception {
        unconditional304s = 1; // the server says "not modified" to a request that carried no validators
        MavenMetadataCache cache = cache(dir, Duration.ofHours(1));

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
        assertThat(hits.get()).as("the 304 costs one unconditional re-GET").isEqualTo(2);
        assertThat(lastIfNoneMatch).isNull();
        Path body = dir.resolve(Hashing.sha256Hex(uri.toString()));
        assertThat(body).hasBinaryContent(BODY);
    }
    /**
     * A metadata GET runs under the per-host permit like an artifact download: four cold catalogs
     * asked of one host at once reach a limiter with one permit one at a time.
     */
    @Test
    void metadata_gets_run_under_the_hosts_permit(@TempDir Path dir) throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger widest = new AtomicInteger();
        server.createContext("/limited/", exchange -> {
            int now = inFlight.incrementAndGet();
            widest.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            exchange.sendResponseHeaders(200, BODY.length);
            exchange.getResponseBody().write(BODY);
            exchange.close();
        });
        MavenMetadataCache cache = new MavenMetadataCache(new Http(), dir, Duration.ZERO, new HostRateLimiter(1));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<byte[]>> asked = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                URI catalog = URI.create(uri.toString().replace("/g/a/", "/limited/a" + i + "/"));
                asked.add(pool.submit(() -> cache.fetch(catalog, RepoCredential.ANONYMOUS)));
            }
            for (Future<byte[]> f : asked) assertThat(f.get()).isEqualTo(BODY);
        } finally {
            pool.shutdownNow();
        }
        assertThat(widest.get()).as("requests in flight at once on the host").isEqualTo(1);
    }
}
