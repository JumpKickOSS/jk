// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven's CI-friendly versions: {@code ${revision}${changelist}} in {@code <version>} is filled
 * from the POM's own properties, for the project, for the reactor parent match and for a sibling
 * BOM a module imports by {@code ${project.version}}.
 */
class PomCiFriendlyImportTest {

    private static final String PARENT = """
            <parent>
              <groupId>org.demo</groupId>
              <artifactId>parent</artifactId>
              <version>${revision}${changelist}</version>
            </parent>
            """;

    @Test
    void a_reactor_versioned_by_revision_and_changelist_imports_with_the_concrete_version(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>${revision}${changelist}</version>
                  <packaging>pom</packaging>
                  <properties>
                    <revision>2.583</revision>
                    <changelist>-SNAPSHOT</changelist>
                  </properties>
                  <modules>
                    <module>bom</module>
                    <module>core</module>
                  </modules>
                </project>
                """);
        Files.createDirectories(root.resolve("bom"));
        Files.writeString(root.resolve("bom/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>33.4.0-jre</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(PARENT));
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>core</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.demo</groupId>
                        <artifactId>bom</artifactId>
                        <version>${project.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(PARENT));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneMatch(m -> m.contains("could not be resolved"));
        assertThat(result.root().project().version()).isEqualTo("2.583-SNAPSHOT");
        JkBuild core = requireNonNull(result.modules().get("core"));
        assertThat(core.project().version()).isEqualTo("2.583-SNAPSHOT");
        List<Dependency> main = core.dependencies().of(Scope.MAIN);
        assertThat(main)
                .extracting(d -> d.module() + "=" + d.version().raw())
                .as("the sibling BOM's managed version reaches the module without a repository")
                .containsExactly("com.google.guava:guava=33.4.0-jre");
        assertThat(core.dependencies().of(Scope.PLATFORM))
                .as("a sibling BOM is not a published platform")
                .isEmpty();
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anyMatch(m ->
                        m.startsWith("[core] `<dependencyManagement>` imports the reactor BOM `bom` (org.demo:bom)"));
    }

    @Test
    void a_placeholder_no_pom_defines_is_written_as_the_fallback_with_a_row(@TempDir Path tmp) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>app</artifactId>
                  <version>${revision}</version>
                </project>
                """);

        assertThat(result.jkBuild().project().version()).isEqualTo("0.0.0-SNAPSHOT");
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.contains("references revision") && m.contains("0.0.0-SNAPSHOT"));
    }
}
