// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** The effective model carries a POM's {@code <repositories>} and its parents', nearest first. */
class EffectivePomDeclaredRepositoriesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void start() {
        EffectivePomBuilder.clearProcessCache();
    }

    @Test
    void a_parents_repositories_reach_the_child_after_its_own(@TempDir Path tempDir) throws Exception {
        registerPom("io.demo", "parent", "1.0", """
                <project>
                  <groupId>io.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <repositories>
                    <repository>
                      <id>jitpack.io</id>
                      <url>https://jitpack.io</url>
                    </repository>
                  </repositories>
                </project>
                """);
        registerPom("io.demo", "child", "1.0", """
                <project>
                  <parent>
                    <groupId>io.demo</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                  <repositories>
                    <repository>
                      <id>own</id>
                      <url>https://own.example.org/maven/</url>
                    </repository>
                    <repository>
                      <id>jitpack-again</id>
                      <url>https://jitpack.io</url>
                    </repository>
                  </repositories>
                </project>
                """);

        Cas cas = new Cas(tempDir.resolve("cache"));
        EffectivePom pom = new EffectivePomBuilder(new MavenRepo("local", http.base(), new Http(), cas))
                .build(Coordinate.of("io.demo", "child", "1.0"));

        assertThat(pom.repositories())
                .as("the child's own first, one entry per URL, the parent's entry naming the parent")
                .containsExactly(
                        new Pom.Repository("own", "https://own.example.org/maven/", "io.demo:child:1.0"),
                        new Pom.Repository("jitpack-again", "https://jitpack.io", "io.demo:child:1.0"));
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version
                + ".pom";
        http.served().put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
