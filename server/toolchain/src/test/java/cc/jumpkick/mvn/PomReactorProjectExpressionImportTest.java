// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reactor POM may spell its own {@code <groupId>} or {@code <version>} as {@code
 * ${project.parent.groupId}} or {@code ${project.parent.version}}, and a sibling edge as {@code
 * ${project.groupId}:util:${project.version}}. The reactor match fills those from the POM's own
 * coordinates and {@code <parent>} block, so a module that names such a POM as its parent stays in
 * the workspace instead of being fetched from a repository.
 */
class PomReactorProjectExpressionImportTest {

    @Test
    void a_parent_whose_coordinates_are_project_expressions_is_matched_in_the_reactor(@TempDir Path root)
            throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>libs</module>
                  </modules>
                </project>
                """);
        write(root, "libs/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <groupId>${project.parent.groupId}</groupId>
                  <artifactId>libs-parent</artifactId>
                  <version>${project.parent.version}</version>
                  <packaging>pom</packaging>
                  <properties>
                    <demo.label>shared</demo.label>
                  </properties>
                  <modules>
                    <module>util</module>
                    <module>core</module>
                  </modules>
                </project>
                """);
        write(root, "libs/util/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>libs-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <groupId>${project.parent.groupId}</groupId>
                  <artifactId>util</artifactId>
                  <version>${project.parent.version}</version>
                </project>
                """);
        write(root, "libs/core/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>libs-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>${project.groupId}</groupId>
                      <artifactId>util</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .as("no parent fell through to the repository")
                .noneMatch(m -> m.contains("could not be resolved"));
        assertThat(requireNonNull(result.root().workspace()).modules()).containsExactly("libs/util", "libs/core");
        JkBuild core = requireNonNull(result.modules().get("libs/core"));
        assertThat(core.dependencies().of(Scope.MAIN)).singleElement().satisfies(d -> {
            assertThat(d.isWorkspace())
                    .as("a sibling under the expression-spelled parent is a workspace edge")
                    .isTrue();
            assertThat(d.library()).isEqualTo("util");
        });
        JkBuild util = requireNonNull(result.modules().get("libs/util"));
        assertThat(util.project().group()).isEqualTo("org.demo");
        assertThat(util.project().version()).isEqualTo("1.0.0");
    }

    private static void write(Path root, String relative, String xml) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
