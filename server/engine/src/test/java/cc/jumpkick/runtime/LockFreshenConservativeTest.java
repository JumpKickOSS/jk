// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.resolver.ResolveObserver;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1350: an invisible freshen must never float pinned versions — conservative resolution keeps
 * the existing lock's pins and only explicit {@code jk lock} picks latest. Also covers the legacy
 * upgrade: an unstamped lock gains {@code manifests-sha256} without any version moving.
 */
class LockFreshenConservativeTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        restartServer();
        // Injected test-framework defaults must resolve like in the resolver test harness.
        serveLeaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        serveLeaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /**
     * New port → new metadata-cache key. The store (and its maven-metadata TTL cache) is ambient
     * per {@link cc.jumpkick.cache.JkStores}, so serving new versions from the same URL would be
     * masked for the whole TTL and the assertions would go vacuous.
     */
    private void restartServer() throws IOException {
        if (server != null) server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
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

    @Test
    void freshen_preserves_pins_while_explicit_lock_floats(@TempDir Path tmp) throws Exception {
        project(tmp);
        serveLib("1.0");

        LockFlow.Result first = LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), true, base, false);
        assertThat(first.status()).isZero();
        assertThat(libVersion(first.lockfile())).isEqualTo("1.0");

        serveLib("1.0", "1.1");
        restartServer();
        // Re-publish after bind, then drop any warm ambient index for this URL — JkStores puts
        // maven-metadata in the product store (cacheN roots are ignored), so port reuse can
        // leave a 24h TTL body that only lists 1.0. Plain lock no longer force-revalidates
        // (jk update / -F only); the float assertion needs the live index.
        serveLib("1.0", "1.1");
        dropLibIndexCache();
        touchManifest(tmp); // whitespace-only edit → digest-stale, so the freshen actually resolves

        LockFlow.Result freshened = LockFlow.run(tmp, tmp.resolve("cache2"), List.of(), true, base, true);
        assertThat(freshened.status()).isZero();
        assertThat(libVersion(freshened.lockfile())).isEqualTo("1.0");

        restartServer();
        serveLib("1.0", "1.1");
        dropLibIndexCache();
        LockFlow.Result explicit = LockFlow.run(tmp, tmp.resolve("cache3"), List.of(), true, base, false);
        assertThat(explicit.status()).isZero();
        assertThat(libVersion(explicit.lockfile()))
                .as("explicit jk lock must float to latest within the declared range")
                .isEqualTo("1.1");
    }

    @Test
    void conservative_freshen_on_a_fresh_lock_skips_resolution(@TempDir Path tmp) throws Exception {
        project(tmp);
        serveLib("1.0");

        assertThat(LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), true, base, false)
                        .status())
                .isZero();

        // Repo is now unreachable: only the single-flight skip can succeed.
        server.stop(0);
        LockFlow.Result skipped =
                LockFlow.run(tmp, tmp.resolve("cache2"), List.of(), true, URI.create("http://127.0.0.1:9/"), true);
        assertThat(skipped.status()).isZero();
        assertThat(libVersion(skipped.lockfile())).isEqualTo("1.0");
    }

    @Test
    void unstamped_lock_gains_stamp_without_version_change(@TempDir Path tmp) throws Exception {
        project(tmp);
        serveLib("1.0");
        Path lockFile = tmp.resolve("jk-lock.toml");

        assertThat(LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), true, base, false)
                        .status())
                .isZero();

        // Simulate a legacy lock written before manifest stamping existed.
        String withoutStamp = Files.readString(lockFile)
                .lines()
                .filter(l -> !l.contains("manifests-sha256"))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        Files.writeString(lockFile, withoutStamp);
        assertThat(LockFreshness.isStale(tmp, lockFile)).isTrue();

        serveLib("1.0", "1.1");
        restartServer();

        LockFlow.Result freshened = LockFlow.run(tmp, tmp.resolve("cache2"), List.of(), true, base, true);
        assertThat(freshened.status()).isZero();
        assertThat(libVersion(freshened.lockfile())).isEqualTo("1.0");
        assertThat(LockFreshness.isStale(tmp, lockFile)).isFalse();
    }

    @Test
    void conservative_lock_pipeline_preserves_pins(@TempDir Path tmp) throws Exception {
        project(tmp);
        serveLib("1.0");

        assertThat(LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), true, base, false)
                        .status())
                .isZero();
        serveLib("1.0", "1.1");
        restartServer();
        touchManifest(tmp);

        var effective = cc.jumpkick.config.JkBuildParser.parse(tmp.resolve("jk.toml"));
        var plan = LockPlans.lockBuildPlan(
                tmp, effective, tmp.resolve("cache2"), base, List.of(), true, false, true, ResolveObserver.NOOP, null);
        var result = plan.run();
        assertThat(result.success()).isTrue();
        Lockfile lock = plan.get(LockPlans.LOCKFILE).orElseThrow();
        assertThat(libVersion(lock)).isEqualTo("1.0");
    }

    @Test
    void workspace_freshen_resolves_with_default_features_like_jk_lock(@TempDir Path tmp) throws Exception {
        // JK-1358: lock content must not depend on which path freshened. The pre-build guard
        // used noDefaultFeatures=true and silently dropped feature-gated deps from the lock.
        serveLib("1.0");
        serveLeaf("com.foo", "extra", "1.0");
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "^1.0" }
                extra = { group = "com.foo", name = "extra", version = "1.0", optional = true }

                [features]
                default = ["extra-feat"]
                extra-feat = { deps = ["extra"] }
                """);

        LockFlow.Result explicit = LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), false, base, false);
        assertThat(explicit.status()).isZero();
        assertThat(hasArtifact(explicit.lockfile(), "com.foo:extra")).isTrue();

        touchManifest(tmp);
        restartServer();
        // Same argument shape as BuildService.ensureWorkspaceLockFresh (features=[],
        // noDefaultFeatures=false, conservative=true): the feature-gated dep must survive.
        LockFlow.Result freshened = LockFlow.run(tmp, tmp.resolve("cache2"), List.of(), false, base, true);
        assertThat(freshened.status()).isZero();
        assertThat(hasArtifact(freshened.lockfile(), "com.foo:extra")).isTrue();
        assertThat(libVersion(freshened.lockfile())).isEqualTo("1.0");
    }

    @Test
    void first_lock_of_a_kotlin_project_pins_the_compiler(@TempDir Path tmp) throws Exception {
        // JK-1371: LockFlow (first-run/workspace freshen path) writes the kotlin pin like
        // lockBuildPlan does.
        serveLib("1.0");
        serveLeaf("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.0");
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                jdk = 25
                java = 25
                kotlin = "2.1.0"

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "^1.0" }
                """);

        LockFlow.Result first = LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), false, base, false);
        assertThat(first.status()).isZero();
        assertThat(first.lockfile().kotlin()).isEqualTo("2.1.0");
    }

    private static boolean hasArtifact(Lockfile lock, String ga) {
        return lock.artifacts().stream().anyMatch(a -> a.packageKey().startsWith(ga + ":"));
    }

    // ---- fixture ------------------------------------------------------------

    private static void project(Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "^1.0" }
                """);
    }

    private static void touchManifest(Path tmp) throws IOException {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, Files.readString(toml) + "\n# touched\n");
    }

    private static String libVersion(Lockfile lock) {
        return lock.artifacts().stream()
                .filter(a -> a.packageKey().startsWith("com.foo:lib:"))
                .map(Lockfile.Artifact::version)
                .findFirst()
                .orElseThrow();
    }

    private void serveLeaf(String group, String artifact, String version) {
        String prefix = "/" + group.replace('.', '/') + "/" + artifact;
        String metadata = "<metadata><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><versioning><versions><version>" + version
                + "</version></versions></versioning></metadata>";
        served.put(prefix + "/maven-metadata.xml", metadata.getBytes(StandardCharsets.UTF_8));
        String pom = """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
        served.put(
                prefix + "/" + version + "/" + artifact + "-" + version + ".pom", pom.getBytes(StandardCharsets.UTF_8));
        served.put(prefix + "/" + version + "/" + artifact + "-" + version + ".jar", emptyJar());
    }

    private void serveLib(String... versions) {
        StringBuilder body = new StringBuilder();
        body.append("<metadata><groupId>com.foo</groupId><artifactId>lib</artifactId><versioning><versions>");
        for (String v : versions) body.append("<version>").append(v).append("</version>");
        body.append("</versions></versioning></metadata>");
        served.put("/com/foo/lib/maven-metadata.xml", body.toString().getBytes(StandardCharsets.UTF_8));
        for (String v : versions) {
            String pom = """
                    <project>
                      <groupId>com.foo</groupId>
                      <artifactId>lib</artifactId>
                      <version>%s</version>
                    </project>
                    """.formatted(v);
            served.put("/com/foo/lib/" + v + "/lib-" + v + ".pom", pom.getBytes(StandardCharsets.UTF_8));
            served.put("/com/foo/lib/" + v + "/lib-" + v + ".jar", emptyJar());
        }
    }

    /**
     * Evict process + on-disk indexes for {@code com.foo:lib} at the current {@link #base}.
     * Ambient {@link cc.jumpkick.cache.JkStores} metadata is keyed by URL and lives 24h; a
     * recycled loopback port can otherwise hide newly served versions from plain {@code lock}.
     */
    private void dropLibIndexCache() throws IOException {
        cc.jumpkick.resolve.ResolveProcessCacheControl.clearAll();
        String root = base.toString();
        if (!root.endsWith("/")) root = root + "/";
        URI metaUri = URI.create(root).resolve("com/foo/lib/maven-metadata.xml");
        Path metaDir = cc.jumpkick.cache.JkStores.store().resolve("metadata");
        Path body = metaDir.resolve(cc.jumpkick.util.Hashing.sha256Hex(metaUri.toString()));
        Files.deleteIfExists(body);
        Files.deleteIfExists(body.resolveSibling(body.getFileName() + ".h"));
    }

    /**
     * A real (empty) jar for every POM this fixture serves. JK-1649 made a resolved-but-unfetchable
     * artifact a hard lock failure, and this fixture predates it — POM-only repos used to be
     * enough. Serving the bytes exercises that check instead of dodging it.
     */
    private static byte[] emptyJar() {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            new java.util.jar.JarOutputStream(bytes, new java.util.jar.Manifest()).close();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
