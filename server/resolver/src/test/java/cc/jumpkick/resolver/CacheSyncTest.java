// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.repo.DownloadSlots;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.SysProps;
import com.sun.net.httpserver.HttpServer;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

@SysProps.TempRoots("jk.m2.local")
class CacheSyncTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @Test
    void fetches_missing_artifact(@TempDir Path tempDir) throws Exception {
        byte[] jar = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        registerJar("com.foo", "leaf", "1.0", jar);

        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex));
        CacheSync.Report report = newSync(tempDir).sync(lock);

        assertThat(report.fetched()).isEqualTo(1);
        assertThat(report.upToDate()).isZero();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void recognizes_already_cached(@TempDir Path tempDir) throws Exception {
        byte[] jar = "already-cached".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        Path store = tempDir.resolve("cache");
        Path src = tempDir.resolve("leaf.jar");
        Files.write(src, jar);
        centralStore(store).materialize("com/foo/leaf/1.0/leaf-1.0.jar", src, hex);
        registerJar("com.foo", "leaf", "1.0", jar);

        CacheSync.Report report = new CacheSync(new Cas(store), new Http(), false)
                .sync(lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex)));

        assertThat(report.upToDate()).isEqualTo(1);
        assertThat(report.fetched()).isZero();
    }

    @Test
    void reports_checksum_mismatch(@TempDir Path tempDir) throws Exception {
        // Serve a jar whose actual sha256 won't match what the lockfile claims.
        byte[] jar = "real".getBytes(StandardCharsets.UTF_8);
        registerJar("com.foo", "leaf", "1.0", jar);
        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:deadbeef"));

        CacheSync.Report report = newSync(tempDir).sync(lock);

        assertThat(report.fetched()).isZero();
        assertThat(report.errors()).singleElement().asString().contains("checksum mismatch");
    }

    @Test
    void mismatching_m2_is_not_overwritten(@TempDir Path tempDir) throws Exception {
        byte[] jar = "genuine-bytes".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        registerJar("com.foo", "leaf", "1.0", jar);
        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex));
        assertThat(newSync(tempDir, true).sync(lock).fetched()).isEqualTo(1);

        Path m2Jar = Path.of(System.getProperty("jk.m2.local")).resolve("com/foo/leaf/1.0/leaf-1.0.jar");
        Files.write(m2Jar, "poisoned".getBytes(StandardCharsets.UTF_8));

        CacheSync.Report report = newSync(tempDir, true).sync(lock);

        assertThat(report.errors()).isEmpty();
        assertThat(Files.readAllBytes(m2Jar)).isEqualTo("poisoned".getBytes(StandardCharsets.UTF_8));
        Path storeJar = centralStore(tempDir.resolve("cache"))
                .locate("com/foo/leaf/1.0/leaf-1.0.jar")
                .orElseThrow();
        assertThat(storeJar).exists();
        assertThat(Files.readAllBytes(storeJar)).isEqualTo(jar);
    }

    @Test
    void does_not_touch_m2_when_mirroring_is_disabled(@TempDir Path tempDir) throws Exception {
        byte[] jar = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        registerJar("com.foo", "leaf", "1.0", jar);
        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex));

        assertThat(newSync(tempDir).sync(lock).fetched()).isEqualTo(1);

        Path m2Jar = Path.of(System.getProperty("jk.m2.local")).resolve("com/foo/leaf/1.0/leaf-1.0.jar");
        assertThat(m2Jar).doesNotExist();
    }

    @Test
    void skips_packages_without_checksum(@TempDir Path tempDir) throws Exception {
        Lockfile lock = lockOf(pkg("com.foo:parent-pom", "1.0", null));
        CacheSync.Report report = newSync(tempDir).sync(lock);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.errors()).isEmpty();
    }

    /**
     * An address that answered nothing is remembered for a minute and refused before it is dialled;
     * a forced sync distrusts that memory too, so a repository that came back within the minute is
     * asked again at once.
     */
    @Test
    void a_forced_sync_forgets_an_address_that_answered_nothing(@TempDir Path tempDir) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        byte[] jar = "came-back".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        String source = "central+http://127.0.0.1:" + port + "/";
        Lockfile lock = lockOf(new Lockfile.Artifact("com.foo:leaf", "1.0", source, "sha256:" + hex, null, List.of()));

        assertThat(newSync(tempDir).sync(lock).errors())
                .as("nothing listens: the ladder fails and the address is remembered")
                .hasSize(1);

        AtomicInteger asked = new AtomicInteger();
        HttpServer back;
        try {
            back = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (BindException taken) {
            // A parallel test took the freed port between the two phases; nothing left to prove.
            Assumptions.abort("port " + port + " was taken before the server came back");
            return;
        }
        back.createContext("/", exchange -> {
            asked.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.endsWith(".jar")
                    ? jar
                    : path.endsWith(".sha256") ? hex.getBytes(StandardCharsets.UTF_8) : null;
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        back.start();
        try {
            assertThat(newSync(tempDir).sync(lock).errors())
                    .as("the address is still remembered as dead: refused before it dials")
                    .hasSize(1);
            assertThat(asked).hasValue(0);

            CacheSync.Report forced = newSync(tempDir).sync(lock, CacheSync.ProgressObserver.NOOP, true);
            assertThat(forced.errors()).isEmpty();
            assertThat(forced.fetched()).isEqualTo(1);
            assertThat(asked.get())
                    .as("the forced leg dialled the address again")
                    .isPositive();
        } finally {
            back.stop(0);
        }
    }

    // --- helpers -----------------------------------------------------------

    private CacheSync newSync(Path tempDir) {
        return newSync(tempDir, false);
    }

    @Test
    void fetch_tasks_alive_at_once_never_exceed_the_download_slots(@TempDir Path tempDir) throws Exception {
        int rows = DownloadSlots.width() * 3;
        http.beforeServe(path -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        List<Lockfile.Artifact> pkgs = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            byte[] jar = ("jar-" + i).getBytes(StandardCharsets.UTF_8);
            registerJar("com.foo", "lib" + i, "1.0", jar);
            pkgs.add(pkg("com.foo:lib" + i, "1.0", "sha256:" + Hashing.sha256Hex(jar)));
        }
        AtomicInteger alive = new AtomicInteger();
        AtomicInteger peakAlive = new AtomicInteger();
        Executor counting = task -> {
            peakAlive.accumulateAndGet(alive.incrementAndGet(), Math::max);
            JkThreads.io().execute(() -> {
                try {
                    task.run();
                } finally {
                    alive.decrementAndGet();
                }
            });
        };

        CacheSync.Report report =
                newSync(tempDir).onExecutor(counting).sync(lockOf(pkgs.toArray(Lockfile.Artifact[]::new)));

        assertThat(report.errors()).isEmpty();
        assertThat(report.fetched()).isEqualTo(rows);
        assertThat(peakAlive.get())
                .as("fetch tasks alive at once: the window's permit goes back as a row completes, a tick before its"
                        + " thread unwinds, so the next row may start while the finished one exits")
                .isLessThanOrEqualTo(DownloadSlots.width() + 1)
                .isGreaterThan(1);
    }

    @Test
    void sources_are_fetched_for_every_row_and_a_library_without_them_is_skipped(@TempDir Path tempDir)
            throws Exception {
        byte[] sources = "leaf-sources".getBytes(StandardCharsets.UTF_8);
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        http.served().put("/com/foo/leaf/1.0/leaf-1.0-sources.jar", sources);
        registerJar("com.foo", "nosrc", "1.0", "nosrc".getBytes(StandardCharsets.UTF_8));
        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:aa"), pkg("com.foo:nosrc", "1.0", "sha256:bb"));

        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        var observer = new CacheSync.ProgressObserver() {
            @Override
            public void skipped(Lockfile.Artifact pkg) {
                skipped.add(pkg.name());
            }

            @Override
            public void failed(Lockfile.Artifact pkg, @Nullable String error) {
                failed.add(pkg.name() + ": " + error);
            }
        };
        CacheSync sync = newSync(tempDir);
        assertThat(sync.syncSources(lock, observer)).isEqualTo(1);
        assertThat(skipped).containsExactly("com.foo:nosrc");
        assertThat(failed).isEmpty();
        assertThat(centralStore(tempDir.resolve("cache")).locate("com/foo/leaf/1.0/leaf-1.0-sources.jar"))
                .isPresent();

        // A second pass finds the fetched jar and asks the repository for nothing new.
        long before = http.requestsFor("/com/foo/leaf/1.0/leaf-1.0-sources.jar");
        assertThat(sync.syncSources(lock, observer)).isZero();
        assertThat(http.requestsFor("/com/foo/leaf/1.0/leaf-1.0-sources.jar")).isEqualTo(before);
    }

    @Test
    void a_pinned_sources_checksum_is_held_to_its_digest(@TempDir Path tempDir) throws Exception {
        byte[] sources = "leaf-sources".getBytes(StandardCharsets.UTF_8);
        http.served().put("/com/foo/leaf/1.0/leaf-1.0-sources.jar", sources);
        List<String> failed = new ArrayList<>();
        var observer = new CacheSync.ProgressObserver() {
            @Override
            public void failed(Lockfile.Artifact pkg, @Nullable String error) {
                failed.add(String.valueOf(error));
            }
        };
        Lockfile wrong =
                lockOf(pkg("com.foo:leaf", "1.0", "sha256:aa").withSourcesChecksum("sha256:" + "0".repeat(64)));
        assertThat(newSync(tempDir).syncSources(wrong, observer)).isZero();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0)).contains("sources");

        Lockfile right = lockOf(
                pkg("com.foo:leaf", "1.0", "sha256:aa").withSourcesChecksum("sha256:" + Hashing.sha256Hex(sources)));
        assertThat(newSync(tempDir.resolve("second")).syncSources(right, CacheSync.ProgressObserver.NOOP))
                .isEqualTo(1);
    }

    private CacheSync newSync(Path tempDir, boolean mirrorToM2) {
        return new CacheSync(new Cas(tempDir.resolve("cache")), new Http(), mirrorToM2);
    }

    private void registerJar(String group, String artifact, String version, byte[] bytes) {
        String path = "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + ".jar";
        http.served().put(path, bytes);
    }

    private Lockfile lockOf(Lockfile.Artifact... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    /** The store the lock rows' source resolves to: keyed by the stub's origin, whatever the row calls it. */
    private RepoArtifactStore centralStore(Path store) {
        return RepoArtifactStore.forSource(store, "central+" + http.base() + "/");
    }

    private Lockfile.Artifact pkg(String module, String version, @Nullable String checksum) {
        return new Lockfile.Artifact(module, version, "central+" + http.base() + "/", checksum, null, List.of());
    }
}
