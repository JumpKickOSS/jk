// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.wire.PluginJarNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A worker jar missing from the store is fetched where a resolve of the {@code jumpkick}
 * repository is: through the {@code mirror-of} table of the user's {@code config.toml}, with the
 * mirror's credential, and never from the official repository's own URL.
 */
class PluginJarMirrorTest {

    /** The corporate mirror. */
    @RegisterExtension
    final LoopbackHttp mirror = new LoopbackHttp();

    /** The official repository: asked nothing while the mirror stands in for it. */
    @RegisterExtension
    final LoopbackHttp official = new LoopbackHttp();

    @TempDir
    Path tmp;

    private final Map<String, @Nullable String> saved = new HashMap<>();
    private Path config = Path.of("config.toml");

    @BeforeEach
    void isolate() throws Exception {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        Path home = Files.createDirectories(tmp.resolve("home"));
        config = home.resolve("config.toml");
        set("jk.env.JK_HOME", home.toString());
        set(MavenSettings.SETTINGS_PROPERTY, tmp.resolve("absent-settings.xml").toString());
        set(PluginJar.OFFICIAL_REPO_URL_PROPERTY, official.baseUrl());
        set(PluginJar.PUBLISHER.jarProperty(), null);
    }

    @AfterEach
    void restore() {
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

    @Test
    void a_missing_worker_is_fetched_from_the_mirror_with_its_credential() throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve(mirror, "/" + rel, "mirrored-worker");
        serve(mirror, "/" + rel.substring(0, rel.length() - 4) + ".pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(JkVersion.VERSION));
        Files.writeString(config, """
                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]
                token = "corp-token"
                """.formatted(mirror.baseUrl()));

        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("store")));

        assertThat(Files.readString(jar)).isEqualTo("mirrored-worker");
        assertThat(mirror.headersFor("/" + rel)).hasValueSatisfying(headers -> assertThat(headers.get("Authorization"))
                .containsExactly("Bearer corp-token"));
        assertThat(official.requested())
                .as("the official repository is never asked")
                .isEmpty();
    }

    @Test
    void a_mirror_that_does_not_answer_fails_the_fetch_and_nothing_falls_back() throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve(official, "/" + rel, "official-worker");
        Files.writeString(config, """
                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]
                """.formatted(mirror.baseUrl()));
        mirror.stop();

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("store"))))
                .isInstanceOf(PluginJarNotFoundException.class);
        assertThat(official.requested())
                .as("nothing falls back to the official URL")
                .isEmpty();
    }

    private static void serve(LoopbackHttp http, String path, String body) {
        http.served().put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
