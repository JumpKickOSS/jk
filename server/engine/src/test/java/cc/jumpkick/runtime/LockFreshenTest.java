// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.resolve.ResolveProcessCacheControl;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeping pins is the default: a re-lock — invisible or the bare {@code jk lock} plan — holds the
 * existing lock's versions, and only {@code jk lock -F} ({@link LockMode.Latest}) picks latest.
 * Also covers the legacy upgrade: an unstamped lock gains {@code manifests-sha256} without any
 * version moving.
 */
class LockFreshenTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void start() throws IOException {
        // Isolate the ambient product store: JkStores resolves via JkDirs, and without
        // this override the test writes 24h-TTL maven-metadata into — and evicts files from —
        // the developer's real store. jk.env.* properties beat the environment in JkDirs.
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        restartServer();
        // Injected test-framework defaults must resolve like in the resolver test harness.
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    /**
     * New port → new metadata-cache key. The store (and its maven-metadata TTL cache) is ambient
     * per {@link cc.jumpkick.cache.JkStores}, so serving new versions from the same URL would be
     * masked for the whole TTL and the assertions would go vacuous.
     */
    private void restartServer() throws IOException {
        http.stop();
        http.start();
    }

    @Test
    void freshen_preserves_pins_while_a_forced_lock_floats(@TempDir Path tmp) throws Exception {
        project(tmp);
        serveLib("1.0");

        LockFlow.Result first = LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), false, http.base());
        assertThat(first.status()).isZero();
        assertThat(libVersion(requireNonNull(first.lockfile()))).isEqualTo("1.0");

        serveLib("1.0", "1.1");
        restartServer();
        // Re-publish after bind, then drop any warm ambient index for this URL — JkStores puts
        // maven-metadata in the product store (cacheN roots are ignored), so port reuse can
        // leave a 24h TTL body that only lists 1.0. Keep-pins locks do not force-revalidate
        // (jk update / -F only); the float assertion needs the live index.
        serveLib("1.0", "1.1");
        dropLibIndexCache();
        touchManifest(tmp); // whitespace-only edit → digest-stale, so the freshen actually resolves

        LockFlow.Result freshened = LockFlow.run(tmp, tmp.resolve("cache2"), List.of(), false, http.base());
        assertThat(freshened.status()).isZero();
        assertThat(libVersion(requireNonNull(freshened.lockfile()))).isEqualTo("1.0");

        restartServer();
        serveLib("1.0", "1.1");
        dropLibIndexCache();
        Lockfile forced = runLockPlan(tmp, tmp.resolve("cache3"), new LockMode.Latest(false));
        assertThat(libVersion(forced))
                .as("jk lock -F must float to latest within the declared range")
                .isEqualTo("1.1");
    }

    @Test
    void freshen_on_a_fresh_lock_skips_resolution(@TempDir Path tmp) throws Exception {
        project(tmp);
        serveLib("1.0");

        assertThat(LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), false, http.base())
                        .status())
                .isZero();

        // Repo is now unreachable: only the single-flight skip can succeed.
        http.stop();
        LockFlow.Result skipped =
                LockFlow.run(tmp, tmp.resolve("cache2"), List.of(), false, URI.create("http://127.0.0.1:9/"));
        assertThat(skipped.status()).isZero();
        assertThat(libVersion(requireNonNull(skipped.lockfile()))).isEqualTo("1.0");
    }

    @Test
    void unstamped_lock_gains_stamp_without_version_change(@TempDir Path tmp) throws Exception {
        project(tmp);
        serveLib("1.0");
        Path lockFile = tmp.resolve("jk-lock.toml");

        assertThat(LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), false, http.base())
                        .status())
                .isZero();

        // Simulate a legacy lock written before manifest stamping existed.
        String withoutStamp = Files.readString(lockFile)
                .lines()
                .filter(l -> !l.contains("manifests-sha256"))
                .collect(Collectors.joining("\n", "", "\n"));
        Files.writeString(lockFile, withoutStamp);
        assertThat(LockFreshness.isStale(tmp, lockFile)).isTrue();

        serveLib("1.0", "1.1");
        restartServer();

        LockFlow.Result freshened = LockFlow.run(tmp, tmp.resolve("cache2"), List.of(), false, http.base());
        assertThat(freshened.status()).isZero();
        assertThat(libVersion(requireNonNull(freshened.lockfile()))).isEqualTo("1.0");
        assertThat(LockFreshness.isStale(tmp, lockFile)).isFalse();
    }

    @Test
    void the_bare_lock_plan_preserves_pins(@TempDir Path tmp) throws Exception {
        project(tmp);
        serveLib("1.0");

        assertThat(LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), false, http.base())
                        .status())
                .isZero();
        serveLib("1.0", "1.1");
        restartServer();
        dropLibIndexCache();
        touchManifest(tmp);

        var effective = JkBuildParser.parse(tmp.resolve("jk.toml"));
        var plan = LockPlans.lockBuildPlan(
                tmp, effective, tmp.resolve("cache2"), http.base(), List.of(), true, false, ResolveObserver.NOOP, null);
        var result = plan.run();
        assertThat(result.success()).isTrue();
        Lockfile lock = plan.get(LockPlans.LOCKFILE).orElseThrow();
        assertThat(libVersion(lock))
                .as("no -F: the newly published 1.1 must not be taken")
                .isEqualTo("1.0");
    }

    @Test
    void workspace_freshen_resolves_with_default_features_like_jk_lock(@TempDir Path tmp) throws Exception {
        // Lock content must not depend on which path freshened: a freshen that suppressed default
        // features would silently drop feature-gated deps from the lock.
        serveLib("1.0");
        upstream.leaf("com.foo", "extra", "1.0");
        Files.writeString(tmp.resolve("jk.toml"), """
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

        LockFlow.Result first = LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), false, http.base());
        assertThat(first.status()).isZero();
        assertThat(hasArtifact(requireNonNull(first.lockfile()), "com.foo:extra"))
                .isTrue();

        touchManifest(tmp);
        restartServer();
        // Same argument shape as BuildService.ensureWorkspaceLockFresh (features=[],
        // noDefaultFeatures=false): the feature-gated dep must survive.
        LockFlow.Result freshened = LockFlow.run(tmp, tmp.resolve("cache2"), List.of(), false, http.base());
        assertThat(freshened.status()).isZero();
        assertThat(hasArtifact(requireNonNull(freshened.lockfile()), "com.foo:extra"))
                .isTrue();
        assertThat(libVersion(requireNonNull(freshened.lockfile()))).isEqualTo("1.0");
    }

    @Test
    void first_lock_of_a_kotlin_project_pins_the_compiler(@TempDir Path tmp) throws Exception {
        // LockFlow (first-run/workspace freshen path) writes the kotlin pin like
        // lockBuildPlan does.
        serveLib("1.0");
        upstream.leaf("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.0");
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                jdk = 25
                java = 25
                kotlin = "2.1.0"

                [dependencies]
                lib = { group = "com.foo", name = "lib", version = "^1.0" }
                """);

        LockFlow.Result first = LockFlow.run(tmp, tmp.resolve("cache1"), List.of(), false, http.base());
        assertThat(first.status()).isZero();
        assertThat(requireNonNull(first.lockfile()).kotlin()).isEqualTo("2.1.0");
    }

    /** Run the lock plan for {@code tmp} under {@code mode} and return the lockfile it wrote. */
    private Lockfile runLockPlan(Path tmp, Path cache, LockMode mode) throws Exception {
        var plan = LockPlans.plan(
                tmp,
                JkBuildParser.parse(tmp.resolve("jk.toml")),
                cache,
                http.base(),
                List.of(),
                true,
                mode,
                ResolveObserver.NOOP,
                null);
        assertThat(plan.run().success()).isTrue();
        return plan.get(LockPlans.LOCKFILE).orElseThrow();
    }

    private static boolean hasArtifact(Lockfile lock, String ga) {
        return lock.artifacts().stream().anyMatch(a -> a.packageKey().startsWith(ga + ":"));
    }

    // ---- fixture ------------------------------------------------------------

    private static void project(Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
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

    private void serveLib(String... versions) {
        StringBuilder body = new StringBuilder();
        body.append("<metadata><groupId>com.foo</groupId><artifactId>lib</artifactId><versioning><versions>");
        for (String v : versions) body.append("<version>").append(v).append("</version>");
        body.append("</versions></versioning></metadata>");
        http.served().put("/com/foo/lib/maven-metadata.xml", body.toString().getBytes(StandardCharsets.UTF_8));
        for (String v : versions) {
            String pom = """
                    <project>
                      <groupId>com.foo</groupId>
                      <artifactId>lib</artifactId>
                      <version>%s</version>
                    </project>
                    """.formatted(v);
            http.served().put("/com/foo/lib/" + v + "/lib-" + v + ".pom", pom.getBytes(StandardCharsets.UTF_8));
            http.served().put("/com/foo/lib/" + v + "/lib-" + v + ".jar", emptyJar());
        }
    }

    /**
     * Evict process + on-disk indexes for {@code com.foo:lib} at the current {@link #http.base()}.
     * Metadata is keyed by URL and lives 24h; a loopback port recycled across
     * {@link #restartServer()} calls can otherwise hide newly http.served() versions from plain
     * {@code lock}. Operates on the {@code @TempDir}-isolated store, never the
     * developer's.
     */
    private void dropLibIndexCache() throws IOException {
        ResolveProcessCacheControl.clearAll();
        String root = http.base().toString();
        if (!root.endsWith("/")) root = root + "/";
        URI metaUri = URI.create(root).resolve("com/foo/lib/maven-metadata.xml");
        Path metaDir = JkStores.store().resolve("metadata");
        Path body = metaDir.resolve(Hashing.sha256Hex(metaUri.toString()));
        Files.deleteIfExists(body);
        Files.deleteIfExists(body.resolveSibling(body.getFileName() + ".h"));
    }

    /**
     * A real (empty) jar for every POM this fixture serves. A resolved-but-unfetchable
     * artifact is a hard lock failure; serving the bytes exercises that check.
     */
    private static byte[] emptyJar() {
        try {
            var bytes = new ByteArrayOutputStream();
            new JarOutputStream(bytes, new Manifest()).close();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
