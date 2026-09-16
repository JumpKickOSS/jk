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

/** A BOM import is fetched by the coordinate its property-spelled fields value to. */
class EffectivePomBomImportPropertyTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void start() {
        EffectivePomBuilder.clearProcessCache();
    }

    @Test
    void values_a_bom_import_group_spelled_as_a_nested_property(@TempDir Path tempDir) throws Exception {
        // wildfly-parent's shape: the import's groupId is ${ee.maven.groupId}, whose value is itself
        // ${project.groupId}. The coordinate the BOM is fetched by is the group both chain to, not a
        // repository path carrying the placeholder.
        registerPom("org.example", "ee-bom", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>ee-bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>managed</artifactId>
                        <version>2.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <ee.maven.groupId>${project.groupId}</ee.maven.groupId>
                    <ee.bom.artifactId>ee-bom</ee.bom.artifactId>
                  </properties>
                </project>
                """);
        registerPom("org.example", "child", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>${ee.maven.groupId}</groupId>
                        <artifactId>${ee.bom.artifactId}</artifactId>
                        <version>${project.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.example", "child", "1.0"));
        assertThat(pom.managedDependencies())
                .filteredOn(d -> d.module().equals("org.example:managed"))
                .extracting(Pom.Dep::version)
                .containsExactly("2.0");
        assertThat(http.requested()).noneMatch(path -> path.contains("${"));
    }

    private EffectivePomBuilder newBuilder(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return new EffectivePomBuilder(new MavenRepo("local", http.base(), new Http(), cas));
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version
                + ".pom";
        http.served().put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
