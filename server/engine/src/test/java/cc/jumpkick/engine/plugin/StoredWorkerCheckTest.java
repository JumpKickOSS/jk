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
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.wire.PluginJarNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A first-party worker already in the official repository's or Central's store runs only once its
 * jar and POM hash to what the signed release of its version lists; the shelf's own copy is the
 * install's and is not asked.
 */
class StoredWorkerCheckTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final Map<String, @Nullable String> saved = new HashMap<>();

    private static final String REL = PluginJar.PUBLISHER.relativePath();
    private static final String POM_REL = REL.substring(0, REL.length() - 4) + ".pom";
    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>cc.jumpkick</groupId>
              <artifactId>jk-publisher</artifactId>
              <version>%s</version>
            </project>
            """.formatted(JkVersion.VERSION);

    @BeforeEach
    void start(@TempDir Path home) throws IOException {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        StoredWorkers.forget();
        SignedRelease.trust(home, "");
        set("jk.env.JK_HOME", home.toString());
        set("jk.env.JK_RELEASES_URL", SignedRelease.releasesUrl(http));
        set(MavenSettings.SETTINGS_PROPERTY, home.resolve("absent-settings.xml").toString());
        set(PluginJar.OFFICIAL_REPO_URL_PROPERTY, http.baseUrl());
        set(PluginJar.PUBLISHER.jarProperty(), null);
        set(M2Dirs.LOCAL_PROPERTY, home.resolve("m2").toString());
        SignedRelease.publish(
                http,
                JkVersion.VERSION,
                Map.of(REL, bytes("genuine-worker"), POM_REL, POM.getBytes(StandardCharsets.UTF_8)));
    }

    @AfterEach
    void stop() {
        StoredWorkers.forget();
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

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Path store(Path cache, String name, URI origin) {
        return Objects.requireNonNull(
                RepoArtifactStore.forRepository(cache, name, origin).root());
    }

    private static Path plant(Path storeRoot, String jar) throws IOException {
        Path j = storeRoot.resolve(REL);
        Files.createDirectories(j.getParent());
        Files.writeString(j, jar);
        Files.writeString(storeRoot.resolve(POM_REL), POM);
        return j;
    }

    @Test
    void a_worker_planted_in_the_official_store_with_other_bytes_is_refused(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        plant(store(cache, RepositorySpec.JUMPKICK_NAME, PluginJar.officialRepoBase()), "planted-worker");

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(cache)))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining(Hashing.sha256Hex(bytes("planted-worker")))
                .hasMessageContaining(Hashing.sha256Hex(bytes("genuine-worker")));
        assertThat(PluginJar.PUBLISHER.locateStored(new Cas(cache))).isNull();
    }

    /** Central's store reads the Maven local repository, where any build may have installed the coordinate. */
    @Test
    void a_worker_another_build_put_in_centrals_store_is_refused(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        RepoArtifactStore central =
                RepoArtifactStore.forRepository(cache, RepositorySpec.CENTRAL, RepositorySpec.MAVEN_CENTRAL.url());
        Path staged = Files.createDirectories(tmp.resolve("staged"));
        Path jar = Files.writeString(staged.resolve("w.jar"), "planted-worker");
        Path pom = Files.writeString(staged.resolve("w.pom"), POM);
        central.materialize(REL, jar, Hashing.sha256Hex(jar));
        central.materialize(POM_REL, pom, Hashing.sha256Hex(pom));
        assertThat(central.locate(REL)).as("the store holds it").isPresent();

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(cache)))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining(Hashing.sha256Hex(bytes("planted-worker")));
    }

    @Test
    void a_pom_the_release_did_not_sign_refuses_the_genuine_jar_beside_it(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path jar = plant(store(cache, RepositorySpec.JUMPKICK_NAME, PluginJar.officialRepoBase()), "genuine-worker");
        Files.writeString(
                jar.resolveSibling(Path.of(POM_REL).getFileName()), POM.replace("</project>", "<x/></project>"));

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(cache)))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining(Path.of(POM_REL).getFileName().toString());
    }

    /**
     * A genuine worker stored before the check existed is vouched for once the release's digests
     * are fetched; they are kept, so a later engine checks without the network.
     */
    @Test
    void a_genuine_stored_worker_runs_and_its_release_digests_are_kept_for_offline_checks(@TempDir Path tmp)
            throws Exception {
        Path cache = tmp.resolve("cache");
        Cas cas = new Cas(cache);
        Path jar = plant(store(cache, RepositorySpec.JUMPKICK_NAME, PluginJar.officialRepoBase()), "genuine-worker");
        assertThat(PluginJar.PUBLISHER.locateStored(cas))
                .as("no digests kept yet, and the store-only probe asks no one")
                .isNull();

        assertThat(PluginJar.PUBLISHER.locate(cas)).isEqualTo(jar);
        assertThat(StoredWorkers.keptFile(cas, JkVersion.VERSION)).exists();

        StoredWorkers.forget();
        http.served().clear();
        int asked = http.requested().size();
        assertThat(PluginJar.PUBLISHER.locateStored(cas)).isEqualTo(jar);
        assertThat(PluginJar.PUBLISHER.locate(cas)).isEqualTo(jar);
        assertThat(http.requested()).hasSize(asked);
    }

    @Test
    void the_shelf_copy_is_the_installs_and_is_not_held_to_the_release(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path shelf = cache.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL);
        Path jar = plant(shelf, "checkout-built-worker");

        assertThat(PluginJar.PUBLISHER.locate(new Cas(cache))).isEqualTo(jar);
        assertThat(http.requested()).isEmpty();
    }
}
