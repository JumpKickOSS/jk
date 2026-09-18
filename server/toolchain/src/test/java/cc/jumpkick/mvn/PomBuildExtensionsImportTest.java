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
 * properties and leaves no row, whether declared as an extension or as a {@code detect} goal; an
 * extension whose effect jk's build has on its own terms is a Tier-2 row naming it, a packaging jk
 * does not build is a Tier-3 row, and an unknown extension is a Tier-3 row naming its coordinate.
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
    void a_known_extension_is_a_tier_2_row_and_an_unknown_one_a_tier_3_row(@TempDir Path tempDir) throws Exception {
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
        assertThat(rows).hasSize(3);
        assertThat(rows)
                .filteredOn(i -> i.severity() == ImportReport.Severity.WARNING)
                .extracting(ImportReport.Issue::message)
                .as("an effect jk's build has on its own terms is a row to review, not a construct left behind")
                .anyMatch(m -> m.contains("org.apache.maven.wagon:wagon-ssh:2.6 is the ssh transport of `mvn deploy`")
                        && m.endsWith("Drop it from the POM when the jk build does not need it."))
                .anyMatch(m ->
                        m.contains("io.github.gitflow-incremental-builder:gitflow-incremental-builder builds only"))
                .noneMatch(m -> m.contains("${gib.version}"))
                .noneMatch(m -> m.contains("`jk mvn`"));
        assertThat(rows)
                .filteredOn(i -> i.severity() == ImportReport.Severity.ERROR)
                .extracting(ImportReport.Issue::message)
                .singleElement()
                .asString()
                .contains("com.acme:my-extension:1.0 is a Maven core extension jk does not load")
                .endsWith("or keep running that step with `jk mvn`.");
    }

    @Test
    void lifecycle_plugins_are_named_by_their_role_instead_of_the_generic_plugin_row(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>bundle</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <extensions>
                      <extension>
                        <groupId>org.apache.maven.archetype</groupId>
                        <artifactId>archetype-packaging</artifactId>
                        <version>3.2.1</version>
                      </extension>
                    </extensions>
                    <plugins>
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>flatten-maven-plugin</artifactId>
                        <version>1.6.0</version>
                        <executions>
                          <execution>
                            <id>flatten</id>
                            <phase>process-resources</phase>
                            <goals><goal>flatten</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <groupId>org.eclipse.tycho</groupId>
                        <artifactId>tycho-maven-plugin</artifactId>
                        <version>4.0.8</version>
                        <extensions>true</extensions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        List<ImportReport.Issue> rows = result.report().issues();
        assertThat(rows)
                .extracting(ImportReport.Issue::message)
                .as("a lifecycle plugin with a role gets that row and not the generic one")
                .noneMatch(m -> m.contains("was not imported"));
        assertThat(rows)
                .filteredOn(i -> i.severity() == ImportReport.Severity.WARNING)
                .extracting(ImportReport.Issue::message)
                .singleElement()
                .asString()
                .startsWith("`<plugin>` org.codehaus.mojo:flatten-maven-plugin:1.6.0 writes the flattened POM")
                .contains("`jk publish` writes its POM from jk.toml");
        assertThat(rows)
                .filteredOn(i -> i.severity() == ImportReport.Severity.ERROR)
                .extracting(ImportReport.Issue::message)
                .hasSize(2)
                .anyMatch(m ->
                        m.startsWith("`<plugin>` org.eclipse.tycho:tycho-maven-plugin:4.0.8 is the Tycho lifecycle"))
                .anyMatch(m -> m.startsWith("`<build><extensions>` org.apache.maven.archetype:archetype-packaging:3.2.1"
                        + " is the `maven-archetype` packaging"));
    }
}
