// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.HostClassifiers;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project POM that spells a classifier with os-maven-plugin's {@code ${os.detected.classifier}}
 * imports with the running host's word as the entry's classifier, as a Maven build with the
 * extension would resolve it.
 */
class PomHostClassifierImportTest {

    @Test
    void an_os_detected_classifier_interpolates_from_the_running_host(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>io.netty</groupId>
                      <artifactId>netty-tcnative-boringssl-static</artifactId>
                      <version>2.0.70.Final</version>
                      <classifier>${os.detected.classifier}</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);

        String detected = HostClassifiers.properties().get("os.detected.classifier");
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN))
                .as("the entry carries the host's word as its classifier")
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.library()).isEqualTo("netty-tcnative-boringssl-static-" + detected);
                    assertThat(d.classifier()).isEqualTo(detected);
                    assertThat(d.packageKey()).isEqualTo("io.netty:netty-tcnative-boringssl-static:jar:" + detected);
                });
        assertThat(TestImporters.messages(result))
                .noneMatch(m -> m.contains("${os.detected.classifier}"))
                .noneMatch(m -> m.contains("classifier"));
    }
}
