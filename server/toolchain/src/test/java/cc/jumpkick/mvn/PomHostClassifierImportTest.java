// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.repo.HostClassifiers;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project POM that spells a classifier with os-maven-plugin's {@code ${os.detected.classifier}}
 * imports with the running host's word in the effective model, as a Maven build with the extension
 * would see it.
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
        assertThat(TestImporters.messages(result))
                .as("the report names the host's word, not the placeholder")
                .anyMatch(m -> m.startsWith("`<classifier>" + detected + "</classifier>` on io.netty:netty-tcnative"))
                .noneMatch(m -> m.contains("${os.detected.classifier}"));
    }
}
