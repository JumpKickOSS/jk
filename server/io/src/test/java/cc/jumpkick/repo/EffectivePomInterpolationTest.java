// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
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

    @Test
    void a_dependency_whose_coordinates_name_a_parent_property_takes_what_the_parent_manages(@TempDir Path tempDir)
            throws Exception {
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><lib.group>org.lib</lib.group><lib.name>x</lib.name></properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.lib</groupId><artifactId>x</artifactId><version>3.0</version><scope>test</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency><groupId>${lib.group}</groupId><artifactId>${lib.name}</artifactId></dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.dependencies()).singleElement().satisfies(d -> {
            assertThat(d.module()).isEqualTo("org.lib:x");
            assertThat(d.version()).isEqualTo("3.0");
            assertThat(d.scope()).isEqualTo("test");
        });
    }

    @Test
    void exclusions_value_the_chains_properties(@TempDir Path tempDir) throws Exception {
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><excluded.group>org.junk</excluded.group></properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.lib</groupId><artifactId>x</artifactId><version>1.0</version>
                      <exclusions><exclusion><groupId>${excluded.group}</groupId><artifactId>${project.artifactId}-junk</artifactId></exclusion></exclusions>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>x</artifactId></dependency>
                    <dependency>
                      <groupId>org.lib</groupId><artifactId>y</artifactId><version>1.0</version>
                      <exclusions><exclusion><groupId>${excluded.group}</groupId><artifactId>nope</artifactId></exclusion></exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.dependencies())
                .flatExtracting(Pom.Dep::exclusions)
                .extracting(e -> e.groupId() + ":" + e.artifactId())
                .containsExactly("org.junk:child-junk", "org.junk:nope");
    }

    @Test
    void a_relocation_and_a_repository_value_the_parents_properties(@TempDir Path tempDir) throws Exception {
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><moved.group>org.moved</moved.group><repo.host>https://repo.example.org</repo.host></properties>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>child</artifactId>
                  <repositories>
                    <repository><url>${repo.host}/maven</url></repository>
                    <repository><id>never</id><url>${not.defined}/maven</url></repository>
                  </repositories>
                  <distributionManagement><relocation><groupId>${moved.group}</groupId></relocation></distributionManagement>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(Objects.requireNonNull(pom.relocation()).groupId()).isEqualTo("org.moved");
        assertThat(pom.repositories())
                .as("a URL that still names no property is dropped")
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.url()).isEqualTo("https://repo.example.org/maven");
                    assertThat(r.id()).isEqualTo("https://repo.example.org/maven");
                });
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
