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
 * A classifier a POM spells with an OS-detected property ({@code ${javafx.platform}}, {@code
 * ${os.detected.classifier}}) reads the running host in the effective model, the way Maven's
 * OS-activated profiles and os-maven-plugin value it; a POM that defines the property itself wins.
 */
class EffectivePomHostClassifierTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void start() {
        EffectivePomBuilder.clearProcessCache();
    }

    @Test
    void a_classifier_spelled_with_a_host_property_takes_the_running_hosts_word(@TempDir Path tempDir)
            throws Exception {
        registerPom("org.openjfx", "javafx-graphics", "25.0.3", """
                <project>
                  <groupId>org.openjfx</groupId>
                  <artifactId>javafx-graphics</artifactId>
                  <version>25.0.3</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.openjfx</groupId>
                      <artifactId>javafx-graphics</artifactId>
                      <version>25.0.3</version>
                      <classifier>${javafx.platform}</classifier>
                    </dependency>
                    <dependency>
                      <groupId>io.grpc</groupId>
                      <artifactId>protoc-gen-grpc-java</artifactId>
                      <version>1.70.0</version>
                      <classifier>${os.detected.classifier}</classifier>
                      <type>exe</type>
                    </dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.openjfx", "javafx-graphics", "25.0.3"));

        String javafx = HostClassifiers.properties().get("javafx.platform");
        String detected = HostClassifiers.properties().get("os.detected.classifier");
        assertThat(pom.dependencies())
                .extracting(Pom.Dep::classifier)
                .as("both classifiers are this host's words, not placeholders")
                .containsExactly(javafx, detected);
        assertThat(pom.hostClassified())
                .containsEntry("org.openjfx:javafx-graphics", "${javafx.platform}")
                .containsEntry("io.grpc:protoc-gen-grpc-java", "${os.detected.classifier}");
    }

    @Test
    void a_pom_that_defines_the_property_itself_keeps_its_own_value(@TempDir Path tempDir) throws Exception {
        registerPom("org.demo", "parent", "1.0", """
                <project>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <javafx.platform>linux-monocle</javafx.platform>
                  </properties>
                </project>
                """);
        registerPom("org.demo", "app", "1.0", """
                <project>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.openjfx</groupId>
                      <artifactId>javafx-base</artifactId>
                      <version>25.0.3</version>
                      <classifier>${javafx.platform}</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);

        EffectivePom pom = newBuilder(tempDir).build(Coordinate.of("org.demo", "app", "1.0"));

        assertThat(pom.dependencies()).singleElement().satisfies(d -> assertThat(d.classifier())
                .isEqualTo("linux-monocle"));
        assertThat(pom.hostClassified())
                .as("the chain's own value is not the host's")
                .isEmpty();
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
