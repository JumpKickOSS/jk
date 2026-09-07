// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.Await;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process selection memo single-flights through futures — the network lookup runs
 * outside the map, one lookup serves concurrent callers, and a slow key never blocks an
 * unrelated one.
 */
class KmpRedirectsMemoTest {

    private HttpServer server;
    private ExecutorService serverPool;
    private URI base;
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** Paths whose response is held until {@link #release} opens. */
    private volatile String heldPathPrefix;

    private final CountDownLatch heldArrived = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        KmpRedirects.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool();
        server.setExecutor(serverPool);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            String held = heldPathPrefix;
            if (held != null && path.startsWith(held)) {
                heldArrived.countDown();
                try {
                    // Bounded so a failed test cannot wedge a server thread; @AfterEach releases it.
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = served.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        serverPool.shutdownNow();
    }

    @Test
    void concurrent_callers_share_one_lookup_and_the_memo_survives_instances(@TempDir Path tmp) throws Exception {
        registerKmpRoot("com.example.kmpdemo", "widget", "1.0.0");
        RepoGroup repos = repoGroup(tmp);

        // Hold the root POM response so the first caller is mid-lookup when the second arrives.
        heldPathPrefix = "/com/example/kmpdemo/widget/1.0.0/widget-1.0.0.pom";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Two separate instances: the shared layer is the process memo, not the local cache.
            Future<Optional<KmpRedirects.Selection>> first = pool.submit(
                    () -> new KmpRedirects(repos, "standard-jvm").selectionFor("com.example.kmpdemo:widget", "1.0.0"));
            assertThat(heldArrived.await(10, TimeUnit.SECONDS)).isTrue();
            CountDownLatch secondStarted = new CountDownLatch(1);
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<Optional<KmpRedirects.Selection>> second = pool.submit(() -> {
                secondThread.set(Thread.currentThread());
                secondStarted.countDown();
                return new KmpRedirects(repos, "standard-jvm").selectionFor("com.example.kmpdemo:widget", "1.0.0");
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // "Started" is not "parked on the in-flight future", and the gap between them was
            // covered by a bare Thread.sleep(50) — a guess about this machine, and no assertion
            // . Parking is not directly observable, but being off the CPU is: wait for the
            // second caller's thread to leave RUNNABLE before releasing the first one.
            Await.until(Duration.ofSeconds(30), () -> secondThread.get().getState() != Thread.State.RUNNABLE);
            assertThat(secondThread.get().getState())
                    .as("the second caller must be parked on the in-flight lookup, not running")
                    .isNotEqualTo(Thread.State.RUNNABLE);
            release.countDown();

            Optional<KmpRedirects.Selection> a = first.get(10, TimeUnit.SECONDS);
            Optional<KmpRedirects.Selection> b = second.get(10, TimeUnit.SECONDS);
            assertThat(a).isPresent();
            assertThat(b).isPresent();
            assertThat(b.get().target().module()).isEqualTo("widget-jvm");
            assertThat(hits.get("/com/example/kmpdemo/widget/1.0.0/widget-1.0.0.pom"))
                    .as("one lookup serves every caller")
                    .hasValue(1);
            assertThat(hits.get("/com/example/kmpdemo/widget/1.0.0/widget-1.0.0.module"))
                    .hasValue(1);

            // A third, fresh instance after completion answers from the memo — zero new requests.
            int pomHits = hits.get("/com/example/kmpdemo/widget/1.0.0/widget-1.0.0.pom")
                    .get();
            assertThat(new KmpRedirects(repos, "standard-jvm").selectionFor("com.example.kmpdemo:widget", "1.0.0"))
                    .isPresent();
            assertThat(hits.get("/com/example/kmpdemo/widget/1.0.0/widget-1.0.0.pom"))
                    .hasValue(pomHits);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void a_slow_key_does_not_block_an_unrelated_key(@TempDir Path tmp) throws Exception {
        registerKmpRoot("com.example.slow", "molasses", "1.0.0");
        registerKmpRoot("com.example.fast", "zippy", "1.0.0");
        RepoGroup repos = repoGroup(tmp);
        KmpRedirects kmp = new KmpRedirects(repos, "standard-jvm");

        heldPathPrefix = "/com/example/slow/molasses/1.0.0/molasses-1.0.0.pom";
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Optional<KmpRedirects.Selection>> slow =
                    pool.submit(() -> kmp.selectionFor("com.example.slow:molasses", "1.0.0"));
            assertThat(heldArrived.await(10, TimeUnit.SECONDS)).isTrue();

            // While the slow lookup is parked on the network, an unrelated key must complete —
            // with computeIfAbsent this could serialize on a shared bin lock.
            Optional<KmpRedirects.Selection> fast = kmp.selectionFor("com.example.fast:zippy", "1.0.0");
            assertThat(fast).isPresent();
            assertThat(slow.isDone()).as("slow lookup still in flight").isFalse();

            release.countDown();
            assertThat(slow.get(10, TimeUnit.SECONDS)).isPresent();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // --- fixtures ----------------------------------------------------------

    private RepoGroup repoGroup(Path tmp) {
        Cas cas = new Cas(tmp.resolve("cas"));
        return RepoGroup.of(new MavenRepo("test", base, new Http(), cas));
    }

    private void registerKmpRoot(String group, String artifact, String version) {
        String dir = "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/";
        String pom = """
                <?xml version="1.0"?>
                <!-- do_not_remove: published-with-gradle-metadata -->
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
        String module =
                """
                {
                  "formatVersion": "1.1",
                  "component": { "group": "%s", "module": "%s", "version": "%s" },
                  "variants": [
                    {
                      "name": "jvmRuntimeElements",
                      "attributes": {
                        "org.gradle.usage": "java-runtime",
                        "org.gradle.category": "library",
                        "org.gradle.jvm.environment": "standard-jvm"
                      },
                      "available-at": {
                        "url": "../../%s-jvm/%s/%s-jvm-%s.module",
                        "group": "%s",
                        "module": "%s-jvm",
                        "version": "%s"
                      }
                    }
                  ]
                }
                """.formatted(group, artifact, version, artifact, version, artifact, version, group, artifact, version);
        served.put(dir + artifact + "-" + version + ".pom", pom.getBytes(StandardCharsets.UTF_8));
        served.put(dir + artifact + "-" + version + ".module", module.getBytes(StandardCharsets.UTF_8));
    }
}
