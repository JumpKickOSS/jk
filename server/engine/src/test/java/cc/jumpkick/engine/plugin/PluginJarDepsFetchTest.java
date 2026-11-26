// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.wire.PluginJarNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A thin worker fetched from the official repo provisions its runtime classpath from the
 * published Maven POM — jar + pom only; no {@code .deps} closure file — once both hash to what the
 * signed release of their version lists.
 *
 * <p>Every coordinate the fixtures mint sits under {@code cc.jumpkick.fixture}, a group the
 * official repository claims exclusively ({@link RepositorySpec#JUMPKICK}). The closure walk
 * therefore asks the loopback stub standing in for that repository and no other: a synthetic
 * coordinate under a group nobody claims would be asked of Maven Central first, and the test would
 * then depend on Central answering 404 rather than on what the stub serves.
 */
class PluginJarDepsFetchTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    /** Properties this test sets, with the value each had before; restored after each test. */
    private final Map<String, @Nullable String> saved = new HashMap<>();

    /**
     * A home of its own whose config trusts {@link SignedRelease}'s key, and a releases root on
     * the stub; the run-tests step may pin a real publisher jar, which is cleared.
     */
    @BeforeEach
    void start(@TempDir Path home) throws IOException {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        SignedRelease.trust(home, "");
        set("jk.env.JK_HOME", home.toString());
        set("jk.env.JK_RELEASES_URL", SignedRelease.releasesUrl(http));
        set(MavenSettings.SETTINGS_PROPERTY, home.resolve("absent-settings.xml").toString());
        set(PluginJar.OFFICIAL_REPO_URL_PROPERTY, http.baseUrl());
        set(PluginJar.PUBLISHER.jarProperty(), null);
    }

    @AfterEach
    void stop() {
        saved.forEach((key, prior) -> {
            if (prior == null) System.clearProperty(key);
            else System.setProperty(key, prior);
        });
    }

    private void set(String key, @Nullable String value) {
        saved.putIfAbsent(key, System.getProperty(key));
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    /** The official repository's store under this test's cache: keyed by the stub's origin, not by the word jumpkick. */
    private static Path official(Path tmp) {
        return Objects.requireNonNull(RepoArtifactStore.forRepository(
                        tmp.resolve("cache"), RepositorySpec.JUMPKICK_NAME, PluginJar.officialRepoBase())
                .root());
    }

    @Test
    void official_fetch_walks_the_pom_and_pulls_maven_deps(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        String pomRel = rel.substring(0, rel.length() - 4) + ".pom";
        String ver = JkVersion.VERSION;
        serve("/" + rel, "thin-worker-jar");
        serve("/" + pomRel, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick.fixture</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(ver));
        serve("/cc/jumpkick/fixture/lib/1.0/lib-1.0.jar", "dep-bytes");
        serve("/cc/jumpkick/fixture/lib/1.0/lib-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick.fixture</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        SignedRelease.publishServed(http, ver);
        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));

        assertThat(Files.readString(jar)).isEqualTo("thin-worker-jar");
        assertThat(Files.exists(Path.of(jar + ".deps"))).isFalse();
        assertThat(Files.exists(Path.of(jar + ".classpath"))).isFalse();
        Path pom = jar.resolveSibling(jar.getFileName().toString().replace(".jar", ".pom"));
        assertThat(pom).exists();
        List<Path> cp = PomRuntimeClasspath.resolve(jar);
        Path dep = official(tmp).resolve("cc/jumpkick/fixture/lib/1.0/lib-1.0.jar");
        assertThat(cp).contains(dep.toAbsolutePath().normalize());
        assertThat(Files.readString(dep)).isEqualTo("dep-bytes");
    }

    /**
     * A worker's jar holds its own classes only; the first-party libraries its POM names decide what
     * it runs as surely, so each is held to the signed digests like the worker.
     */
    @Test
    void a_first_party_library_whose_bytes_the_release_did_not_sign_is_refused(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        String pomRel = rel.substring(0, rel.length() - 4) + ".pom";
        serve("/" + rel, "thin-worker-jar");
        serve("/" + pomRel, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick.fixture</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(JkVersion.VERSION));
        serve("/cc/jumpkick/fixture/lib/1.0/lib-1.0.jar", "genuine-lib");
        serve("/cc/jumpkick/fixture/lib/1.0/lib-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick.fixture</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);
        SignedRelease.publishServed(http, JkVersion.VERSION);
        serve("/cc/jumpkick/fixture/lib/1.0/lib-1.0.jar", "tampered-lib");

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining("lib-1.0.jar")
                .hasMessageContaining(Hashing.sha256Hex("tampered-lib".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The release vouches for the genuine bytes; the repository (or whatever stands in for it)
     * serves others, with a sidecar that agrees with them. The jar is refused, the message names it
     * and both digests, and nothing reaches the store.
     */
    @Test
    void a_worker_whose_bytes_are_not_the_ones_the_release_signed_is_refused(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        servePublisherPom();
        serve("/" + rel, "genuine-worker");
        SignedRelease.publishServed(http, JkVersion.VERSION);
        serve("/" + rel, "tampered-worker");
        serve("/" + rel + ".sha256", Hashing.sha256Hex("tampered-worker".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining(name)
                .hasMessageContaining(Hashing.sha256Hex("tampered-worker".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining(Hashing.sha256Hex("genuine-worker".getBytes(StandardCharsets.UTF_8)));
        assertNoJarStored(tmp);
    }

    /** A POM is the worker's classpath: one the release did not sign is refused like a jar. */
    @Test
    void a_worker_pom_the_release_did_not_sign_is_refused(@TempDir Path tmp) {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "genuine-worker");
        servePublisherPom();
        SignedRelease.publishServed(http, JkVersion.VERSION);
        serve("/" + rel.substring(0, rel.length() - 4) + ".pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick.fixture</groupId>
                      <artifactId>payload</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(JkVersion.VERSION));

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining("jk-publisher-" + JkVersion.VERSION + ".pom");
        assertThat(http.requested()).noneMatch(p -> p.contains("payload"));
    }

    /** A release that lists other files but not this worker vouches for nothing it serves. */
    @Test
    void a_worker_the_signed_release_does_not_list_is_refused(@TempDir Path tmp) throws IOException {
        String rel = PluginJar.PUBLISHER.relativePath();
        SignedRelease.publish(http, JkVersion.VERSION, Map.of("cc/jumpkick/jk-other/1/jk-other-1.jar", new byte[] {1}));
        serve("/" + rel, "unlisted-worker");
        servePublisherPom();

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining("lists no " + rel.substring(rel.lastIndexOf('/') + 1));
        assertNoJarStored(tmp);
    }

    /** A digest list signed by a key the home does not trust is no release at all. */
    @Test
    void a_release_signed_by_an_untrusted_key_vouches_for_nothing(@TempDir Path tmp, @TempDir Path otherHome)
            throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "genuine-worker");
        servePublisherPom();
        SignedRelease.publishServed(http, JkVersion.VERSION);
        Files.writeString(otherHome.resolve("config.toml"), "");
        set("jk.env.JK_HOME", otherHome.toString());

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining("signature");
        assertNoJarStored(tmp);
    }

    /** No release vouches for anything; a sidecar beside the jar that agrees with it is not a vouch. */
    @Test
    void a_sidecar_from_the_repository_is_not_a_signed_digest(@TempDir Path tmp) throws IOException {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "bytes-nobody-signed");
        serve("/" + rel + ".sha256", Hashing.sha256Hex("bytes-nobody-signed".getBytes(StandardCharsets.UTF_8)));
        servePublisherPom();

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining("no signed jk " + JkVersion.VERSION + " release");
        assertNoJarStored(tmp);
    }

    private void servePublisherPom() {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel.substring(0, rel.length() - 4) + ".pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(JkVersion.VERSION));
    }

    private static void assertNoJarStored(Path tmp) throws IOException {
        Path store = official(tmp);
        if (!Files.exists(store)) return;
        try (var walk = Files.walk(store)) {
            assertThat(walk.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().endsWith(".jar")))
                    .isEmpty();
        }
    }

    @Test
    void locate_stored_never_fetches(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "thin-worker-jar");
        serve("/" + rel.substring(0, rel.length() - 4) + ".pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(JkVersion.VERSION));
        SignedRelease.publishServed(http, JkVersion.VERSION);
        Cas cas = new Cas(tmp.resolve("cache"));

        // Cold store: the stub would serve, but the store-only probe must not ask it.
        assertThat(PluginJar.PUBLISHER.locateStored(cas)).isNull();

        Path fetched = PluginJar.PUBLISHER.locate(cas);
        assertThat(PluginJar.PUBLISHER.locateStored(cas)).isEqualTo(fetched);
    }

    @Test
    void official_fetch_interpolates_parent_property_versions(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        String pomRel = rel.substring(0, rel.length() - 4) + ".pom";
        String ver = JkVersion.VERSION;
        serve("/" + rel, "thin-worker-jar");
        serve("/" + pomRel, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick.fixture</groupId>
                      <artifactId>databind</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(ver));
        serve("/cc/jumpkick/fixture/parent/1.0/parent-1.0.pom", """
                <project>
                  <groupId>cc.jumpkick.fixture</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <jackson.version.annotations>2.21</jackson.version.annotations>
                  </properties>
                </project>
                """);
        serve("/cc/jumpkick/fixture/databind/1.0/databind-1.0.jar", "databind-bytes");
        serve("/cc/jumpkick/fixture/databind/1.0/databind-1.0.pom", """
                <project>
                  <parent>
                    <groupId>cc.jumpkick.fixture</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>databind</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick.fixture</groupId>
                      <artifactId>annotations</artifactId>
                      <version>${jackson.version.annotations}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        serve("/cc/jumpkick/fixture/annotations/2.21/annotations-2.21.jar", "annotations-bytes");
        serve("/cc/jumpkick/fixture/annotations/2.21/annotations-2.21.pom", """
                <project>
                  <groupId>cc.jumpkick.fixture</groupId>
                  <artifactId>annotations</artifactId>
                  <version>2.21</version>
                </project>
                """);

        SignedRelease.publishServed(http, ver);
        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));
        List<Path> cp = PomRuntimeClasspath.resolve(jar);
        Path annotations = official(tmp).resolve("cc/jumpkick/fixture/annotations/2.21/annotations-2.21.jar");
        assertThat(cp).contains(annotations.toAbsolutePath().normalize());
        assertThat(Files.readString(annotations)).isEqualTo("annotations-bytes");
    }

    @Test
    void official_fetch_fills_bom_managed_versions(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        String pomRel = rel.substring(0, rel.length() - 4) + ".pom";
        String ver = JkVersion.VERSION;
        serve("/" + rel, "thin-worker-jar");
        serve("/" + pomRel, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>cc.jumpkick.fixture</groupId>
                        <artifactId>bom</artifactId>
                        <version>1.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick.fixture</groupId>
                      <artifactId>lib</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(ver));
        serve("/cc/jumpkick/fixture/bom/1.0/bom-1.0.pom", """
                <project>
                  <groupId>cc.jumpkick.fixture</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>cc.jumpkick.fixture</groupId>
                        <artifactId>lib</artifactId>
                        <version>9.9.9</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        serve("/cc/jumpkick/fixture/lib/9.9.9/lib-9.9.9.jar", "lib-bytes");
        serve("/cc/jumpkick/fixture/lib/9.9.9/lib-9.9.9.pom", """
                <project>
                  <groupId>cc.jumpkick.fixture</groupId>
                  <artifactId>lib</artifactId>
                  <version>9.9.9</version>
                </project>
                """);

        SignedRelease.publishServed(http, ver);
        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));
        List<Path> cp = PomRuntimeClasspath.resolve(jar);
        Path lib = official(tmp).resolve("cc/jumpkick/fixture/lib/9.9.9/lib-9.9.9.jar");
        assertThat(cp).contains(lib.toAbsolutePath().normalize());
        assertThat(Files.readString(lib)).isEqualTo("lib-bytes");
    }

    @Test
    void official_fetch_without_a_pom_fails(@TempDir Path tmp) {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "fat-worker-jar");

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining("POM");
    }

    /**
     * The release, its engine and its client are checked against the signed manifest; a worker the
     * engine fetches on its first build must be held to a digest the signed engine carries, not to
     * whatever the repository (or the mirror standing in for it) serves beside it.
     */
    @Test
    void an_official_worker_the_signed_engine_does_not_name_is_refused(@TempDir Path tmp) {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "bytes-nobody-signed");
        serve("/" + rel.substring(0, rel.length() - 4) + ".pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(JkVersion.VERSION));

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class);
    }

    private void serve(String path, String body) {
        http.served().put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
