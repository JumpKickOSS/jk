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

/**
 * Maven interpolates an inherited model in the child's context, so a parent's {@code
 * dependencyManagement} entry spelled {@code ${project.parent.version}} values to the version of
 * the parent the child names. The azure-cosmosdb parent manages every sibling that way, and the
 * dependency reaches the repository as a coordinate, never as a path carrying the placeholder.
 */
class EffectivePomParentVersionTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void start() {
        EffectivePomBuilder.clearProcessCache();
    }

    @Test
    void a_parent_managed_version_spelled_project_parent_version_values_to_the_parent(@TempDir Path tempDir)
            throws Exception {
        registerParentManaging("${project.parent.version}");
        registerChild();

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("com.example", "sdk", "2.4.5"));

        assertThat(pom.dependencies()).singleElement().satisfies(dep -> {
            assertThat(dep.artifactId()).isEqualTo("sdk-commons");
            assertThat(dep.version()).isEqualTo("2.4.5");
        });
    }

    @Test
    void the_parent_dot_and_pom_dot_spellings_value_the_same_way(@TempDir Path tempDir) throws Exception {
        registerParentManaging("${parent.version}");
        registerPom("com.example", "sdk-pom-parent", "2.4.5", parentBody("sdk-pom-parent", "${pom.version}"));
        registerChild();
        registerPom("com.example", "sdk-pom", "2.4.5", childBody("sdk-pom", "sdk-pom-parent"));

        EffectivePomBuilder builder = newBuilder(tempDir);
        assertThat(builder.build(Coordinate.of("com.example", "sdk", "2.4.5")).dependencies())
                .singleElement()
                .extracting(Pom.Dep::version)
                .isEqualTo("2.4.5");
        assertThat(builder.build(Coordinate.of("com.example", "sdk-pom", "2.4.5"))
                        .dependencies())
                .singleElement()
                .extracting(Pom.Dep::version)
                .isEqualTo("2.4.5");
    }

    private void registerParentManaging(String versionSpelling) {
        registerPom("com.example", "sdk-parent", "2.4.5", parentBody("sdk-parent", versionSpelling));
    }

    private void registerChild() {
        registerPom("com.example", "sdk", "2.4.5", childBody("sdk", "sdk-parent"));
    }

    private static String parentBody(String artifactId, String versionSpelling) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>2.4.5</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>sdk-commons</artifactId>
                        <version>%s</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(artifactId, versionSpelling);
    }

    private static String childBody(String artifactId, String parentArtifactId) {
        return """
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>2.4.5</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>sdk-commons</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(parentArtifactId, artifactId);
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
