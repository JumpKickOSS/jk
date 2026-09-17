// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A direct dependency whose POM version is Maven's {@code LATEST} or {@code RELEASE} metaversion is
 * written as the {@code latest} selector with a row, and {@code jk export maven} writes it back as
 * {@code LATEST}.
 */
class PomMetaversionImportTest {

    @Test
    void latest_and_release_are_written_as_the_latest_selector_with_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>0.1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>LATEST</version>
                    </dependency>
                    <dependency>
                      <groupId>commons-io</groupId>
                      <artifactId>commons-io</artifactId>
                      <version>RELEASE</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        JkBuild build = result.jkBuild();

        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module, Dependency::version)
                .containsExactly(
                        tuple("com.google.guava:guava", VersionSelector.parse("latest")),
                        tuple("commons-io:commons-io", VersionSelector.parse("latest")));
        assertThat(TestImporters.messages(result))
                .anySatisfy(m -> assertThat(m)
                        .contains("`<version>LATEST</version>` on com.google.guava:guava floats under Maven")
                        .contains("written as the `latest` selector")
                        .contains("pins the newest version"))
                .anySatisfy(m -> assertThat(m)
                        .contains("`<version>RELEASE</version>` on commons-io:commons-io")
                        .contains("pins the newest release"))
                .noneMatch(m -> m.contains("unresolved"));

        String xml = PomExporter.export(build).xml();
        assertThat(xml).contains("<artifactId>guava</artifactId>").contains("<version>LATEST</version>");
    }
}
