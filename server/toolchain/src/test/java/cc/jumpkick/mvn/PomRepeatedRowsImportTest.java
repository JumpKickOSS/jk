// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module row whose text is the same in several modules of a reactor — a dependency every module
 * inherits from the workspace parent, a sibling BOM several modules import — is one row at the
 * first module with the count of modules saying it and the module list elided past a few, while a
 * row only one module says keeps its own row.
 */
class PomRepeatedRowsImportTest {

    @Test
    void a_row_repeated_verbatim_across_modules_is_one_row_with_the_module_count(@TempDir Path root) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>bom</module>
                    <module>m1</module>
                    <module>m2</module>
                    <module>m3</module>
                    <module>m4</module>
                    <module>m5</module>
                  </modules>
                  <dependencies>
                    <dependency>
                      <groupId>org.projectlombok</groupId>
                      <artifactId>lombok</artifactId>
                      <version>1.18.34</version>
                      <scope>provided</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        write(root, "bom/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>33.0.0-jre</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        String bomImport = """
                <dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.demo</groupId>
                      <artifactId>bom</artifactId>
                      <version>1.0.0</version>
                      <type>pom</type>
                      <scope>import</scope>
                    </dependency>
                  </dependencies>
                </dependencyManagement>
                """;
        String pomType = """
                <dependencies>
                  <dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava-bom</artifactId>
                    <version>33.0.0-jre</version>
                    <type>pom</type>
                  </dependency>
                </dependencies>
                """;
        write(root, "m1/pom.xml", leaf("m1", bomImport));
        write(root, "m2/pom.xml", leaf("m2", bomImport));
        write(root, "m3/pom.xml", leaf("m3", ""));
        write(root, "m4/pom.xml", leaf("m4", pomType));
        write(root, "m5/pom.xml", leaf("m5", ""));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));
        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();

        assertThat(result.modules().keySet()).containsExactly("m1", "m2", "m3", "m4", "m5");
        assertThat(rows.stream().filter(m -> m.contains("inherited from workspace parent")))
                .as("a dependency every module inherits: one row at the first module, the list elided past three")
                .singleElement()
                .isEqualTo("[m1] dependencies org.projectlombok:lombok inherited from workspace parent"
                        + " org.demo:parent:1.0.0. (5 modules: m1, m2, m3, …)");
        assertThat(rows.stream().filter(m -> m.contains("imports the reactor BOM")))
                .as("a sibling BOM two modules import: one row naming both")
                .singleElement()
                .satisfies(m -> {
                    assertThat(m)
                            .startsWith("[m1] `<dependencyManagement>` imports the reactor BOM `bom` (org.demo:bom)");
                    assertThat(m).endsWith(" (2 modules: m1, m2)");
                });
        assertThat(rows.stream().filter(m -> m.contains("<type>pom</type>")))
                .as("a row one module says keeps its own row, with no count")
                .singleElement()
                .satisfies(m -> {
                    assertThat(m).startsWith("[m4] `<type>pom</type>` on com.google.guava:guava-bom");
                    assertThat(m).doesNotContain("modules:");
                });
        assertThat(rows.stream().filter(m -> m.contains("(1 module"))).isEmpty();
    }

    private static String leaf(String artifactId, String body) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>%s</artifactId>
                  %s
                </project>
                """.formatted(artifactId, body);
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
