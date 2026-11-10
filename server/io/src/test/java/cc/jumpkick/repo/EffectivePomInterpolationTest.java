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

/** An inherited model is interpolated in the child's context: the child's properties over the chain's. */
class EffectivePomInterpolationTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void start() {
        EffectivePomBuilder.clearProcessCache();
    }

    @Test
    void a_childs_property_override_reaches_the_version_its_parent_manages(@TempDir Path tempDir) throws Exception {
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><lib.version>1.0</lib.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>x</artifactId><version>${lib.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>child</artifactId>
                  <properties><lib.version>2.0</lib.version></properties>
                  <dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>x</artifactId></dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.dependencies())
                .as("Maven assembles the inheritance first, then interpolates in the child's context")
                .singleElement()
                .extracting(Pom.Dep::version)
                .isEqualTo("2.0");
    }

    @Test
    void a_childs_property_override_reaches_the_bom_its_parent_imports(@TempDir Path tempDir) throws Exception {
        for (String v : new String[] {"1.0", "2.0"}) {
            registerPom("org.lib", "lib-bom", v, """
                    <project>
                      <groupId>org.lib</groupId><artifactId>lib-bom</artifactId><version>%s</version>
                      <packaging>pom</packaging>
                      <dependencyManagement><dependencies>
                        <dependency><groupId>org.lib</groupId><artifactId>x</artifactId><version>%s</version></dependency>
                      </dependencies></dependencyManagement>
                    </project>
                    """.formatted(v, v));
        }
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><lib.version>1.0</lib.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.lib</groupId><artifactId>lib-bom</artifactId><version>${lib.version}</version>
                      <type>pom</type><scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>child</artifactId>
                  <properties><lib.version>2.0</lib.version></properties>
                  <dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>x</artifactId></dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.dependencies())
                .singleElement()
                .extracting(Pom.Dep::version)
                .isEqualTo("2.0");
    }

    private EffectivePomBuilder newBuilder(Path tempDir) {
        return new EffectivePomBuilder(
                new MavenRepo("local", http.base(), new Http(), new Cas(tempDir.resolve("cache"))));
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version
                + ".pom";
        http.served().put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
