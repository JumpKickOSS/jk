// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A thin worker fetched from the official repo provisions its runtime classpath from the
 * published Maven POM — jar + pom only; no {@code .deps} closure file.
 */
class PluginJarDepsFetchTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    /** Restored after each test — self-host / Gradle may pin a real publisher jar. */
    private String savedPublisherJarProp;

    @BeforeEach
    void start() throws IOException {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        System.setProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY, http.baseUrl());
        savedPublisherJarProp = System.getProperty(PluginJar.PUBLISHER.jarProperty());
        System.clearProperty(PluginJar.PUBLISHER.jarProperty());
    }

    @AfterEach
    void stop() {
        System.clearProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY);
        if (savedPublisherJarProp != null) {
            System.setProperty(PluginJar.PUBLISHER.jarProperty(), savedPublisherJarProp);
        } else {
            System.clearProperty(PluginJar.PUBLISHER.jarProperty());
        }
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
                      <groupId>com.foo</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(ver));
        serve("/com/foo/lib/1.0/lib-1.0.jar", "dep-bytes");
        serve("/com/foo/lib/1.0/lib-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.foo</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));

        assertThat(Files.readString(jar)).isEqualTo("thin-worker-jar");
        assertThat(Files.exists(Path.of(jar + ".deps"))).isFalse();
        assertThat(Files.exists(Path.of(jar + ".classpath"))).isFalse();
        Path pom = jar.resolveSibling(jar.getFileName().toString().replace(".jar", ".pom"));
        assertThat(pom).exists();
        List<Path> cp = PomRuntimeClasspath.resolve(jar);
        Path dep = tmp.resolve("cache/repos/jumpkick/com/foo/lib/1.0/lib-1.0.jar");
        assertThat(cp).contains(dep.toAbsolutePath().normalize());
        assertThat(Files.readString(dep)).isEqualTo("dep-bytes");
    }

    @Test
    void published_checksum_mismatch_rejects_the_jar(@TempDir Path tmp) throws Exception {
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
        serve("/" + rel + ".sha256", "0".repeat(64) + "  jk-publisher.jar\n");

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .hasMessageContaining("checksum mismatch");
        assertThat(tmp.resolve("cache/repos/jumpkick"))
                .satisfiesAnyOf(p -> assertThat(p).doesNotExist(), p -> assertThat(Files.walk(p)
                                .filter(Files::isRegularFile)
                                .filter(f -> f.getFileName().toString().endsWith(".jar")))
                        .isEmpty());
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
                      <groupId>org.example</groupId>
                      <artifactId>databind</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(ver));
        serve("/org/example/parent/1.0/parent-1.0.pom", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <jackson.version.annotations>2.21</jackson.version.annotations>
                  </properties>
                </project>
                """);
        serve("/org/example/databind/1.0/databind-1.0.jar", "databind-bytes");
        serve("/org/example/databind/1.0/databind-1.0.pom", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>databind</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>annotations</artifactId>
                      <version>${jackson.version.annotations}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        serve("/org/example/annotations/2.21/annotations-2.21.jar", "annotations-bytes");
        serve("/org/example/annotations/2.21/annotations-2.21.pom", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>annotations</artifactId>
                  <version>2.21</version>
                </project>
                """);

        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));
        List<Path> cp = PomRuntimeClasspath.resolve(jar);
        Path annotations = tmp.resolve("cache/repos/jumpkick/org/example/annotations/2.21/annotations-2.21.jar");
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
                        <groupId>org.example</groupId>
                        <artifactId>bom</artifactId>
                        <version>1.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(ver));
        serve("/org/example/bom/1.0/bom-1.0.pom", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib</artifactId>
                        <version>9.9.9</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        serve("/org/example/lib/9.9.9/lib-9.9.9.jar", "lib-bytes");
        serve("/org/example/lib/9.9.9/lib-9.9.9.pom", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>9.9.9</version>
                </project>
                """);

        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));
        List<Path> cp = PomRuntimeClasspath.resolve(jar);
        Path lib = tmp.resolve("cache/repos/jumpkick/org/example/lib/9.9.9/lib-9.9.9.jar");
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

    private void serve(String path, String body) {
        http.served().put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
