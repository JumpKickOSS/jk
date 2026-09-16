// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.HostClassifiers;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code <build><extensions>}: os-maven-plugin is satisfied by the host-valued {@code os.detected.*}
 * properties and leaves no row, whether declared as an extension or as a {@code detect} goal; any
 * other extension is a Tier-3 row naming its coordinate and what it does.
 */
class PomBuildExtensionsImportTest {

    @Test
    void os_maven_plugin_imports_silently_and_the_classifier_reads_the_host(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>rpc</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>io.netty</groupId>
                      <artifactId>netty-tcnative-boringssl-static</artifactId>
                      <version>2.0.70.Final</version>
                      <classifier>${os.detected.classifier}</classifier>
                    </dependency>
                  </dependencies>
                  <build>
                    <extensions>
                      <extension>
                        <groupId>kr.motd.maven</groupId>
                        <artifactId>os-maven-plugin</artifactId>
                        <version>1.7.1</version>
                      </extension>
                    </extensions>
                    <plugins>
                      <plugin>
                        <groupId>kr.motd.maven</groupId>
                        <artifactId>os-maven-plugin</artifactId>
                        <version>1.7.1</version>
                        <executions>
                          <execution>
                            <phase>initialize</phase>
                            <goals><goal>detect</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        String detected = HostClassifiers.properties().get("os.detected.classifier");
        assertThat(TestImporters.messages(result))
                .noneMatch(m -> m.contains("<build><extensions>"))
                .noneMatch(m -> m.contains("os-maven-plugin"));
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN))
                .singleElement()
                .satisfies(d -> assertThat(d.classifier()).isEqualTo(detected));
    }

    @Test
    void any_other_extension_is_a_tier_3_row_naming_its_coordinate(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <extensions>
                      <extension>
                        <groupId>org.apache.maven.wagon</groupId>
                        <artifactId>wagon-ssh</artifactId>
                        <version>2.6</version>
                      </extension>
                      <extension>
                        <groupId>io.github.gitflow-incremental-builder</groupId>
                        <artifactId>gitflow-incremental-builder</artifactId>
                        <version>${gib.version}</version>
                      </extension>
                      <extension>
                        <groupId>com.acme</groupId>
                        <artifactId>my-extension</artifactId>
                        <version>1.0</version>
                      </extension>
                    </extensions>
                  </build>
                </project>
                """);

        List<ImportReport.Issue> rows = result.report().issues().stream()
                .filter(i -> i.message().contains("<build><extensions>"))
                .toList();
        assertThat(rows).hasSize(3).allMatch(i -> i.severity() == ImportReport.Severity.ERROR);
        assertThat(rows.stream().map(ImportReport.Issue::message))
                .anyMatch(m -> m.contains("org.apache.maven.wagon:wagon-ssh:2.6 is the ssh transport of `mvn deploy`"))
                .anyMatch(m ->
                        m.contains("io.github.gitflow-incremental-builder:gitflow-incremental-builder builds only"))
                .anyMatch(m -> m.contains("com.acme:my-extension:1.0 is a Maven core extension jk does not load"))
                .noneMatch(m -> m.contains("${gib.version}"))
                .noneMatch(m -> m.contains("custom jk task"));
    }
}
