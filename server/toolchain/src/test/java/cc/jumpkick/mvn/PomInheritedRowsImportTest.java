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
 * A row a parent's {@code <build>} puts on every module — an extension, a plugin with no mapping —
 * is said once, at the declaring POM with the count of modules inheriting it, while a module's own
 * declaration keeps its row at the module.
 */
class PomInheritedRowsImportTest {

    @Test
    void an_inherited_extension_or_plugin_is_one_row_at_the_declaring_pom_with_the_module_count(@TempDir Path root)
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
                    <module>app</module>
                  </modules>
                  <build>
                    <extensions>
                      <extension>
                        <groupId>org.apache.maven.wagon</groupId>
                        <artifactId>wagon-ssh</artifactId>
                        <version>2.6</version>
                      </extension>
                    </extensions>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-enforcer-plugin</artifactId>
                        <version>3.4.1</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        write(root, "libs/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>libs</artifactId>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>util</module>
                  </modules>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>net.revelc.code.formatter</groupId>
                        <artifactId>formatter-maven-plugin</artifactId>
                        <version>2.23.0</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(parent("parent", "..")));
        write(root, "libs/core/pom.xml", leaf("core", "libs", "../pom.xml", ""));
        write(root, "libs/util/pom.xml", leaf("util", "libs", "../pom.xml", ""));
        write(root, "app/pom.xml", leaf("app", "parent", "../pom.xml", """
                <build>
                  <extensions>
                    <extension>
                      <groupId>com.acme</groupId>
                      <artifactId>my-extension</artifactId>
                      <version>1.0</version>
                    </extension>
                  </extensions>
                  <plugins>
                    <plugin>
                      <groupId>com.acme</groupId>
                      <artifactId>acme-maven-plugin</artifactId>
                      <version>1.0</version>
                    </plugin>
                  </plugins>
                </build>
                """));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));
        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();

        assertThat(result.modules().keySet()).containsExactly("libs/core", "libs/util", "app");
        assertThat(rows.stream().filter(m -> m.contains("wagon-ssh")))
                .as("the root's extension: one row, unprefixed, counting the three modules that inherit it")
                .singleElement()
                .satisfies(m -> {
                    assertThat(m)
                            .startsWith(
                                    "`<build><extensions>` org.apache.maven.wagon:wagon-ssh:2.6 is the ssh transport");
                    assertThat(m).endsWith("Declared by the root pom.xml, inherited by 3 modules.");
                });
        assertThat(rows.stream().filter(m -> m.contains("maven-enforcer-plugin")))
                .singleElement()
                .satisfies(
                        m -> assertThat(m)
                                .isEqualTo(
                                        "`<plugin>maven-enforcer-plugin</plugin>` was not imported; docs/user/migration.md"
                                                + " lists where it lands in jk. Declared by the root pom.xml, inherited by 3 modules."));
        assertThat(rows.stream().filter(m -> m.contains("formatter-maven-plugin")))
                .as("an aggregator's plugin is named by its pom.xml and counts its own subtree")
                .singleElement()
                .satisfies(m -> assertThat(m).endsWith("Declared by `libs/pom.xml`, inherited by 2 modules."));
        assertThat(rows.stream().filter(m -> m.contains("my-extension")))
                .as("a module's own extension stays a row at the module")
                .singleElement()
                .satisfies(m -> {
                    assertThat(m).startsWith("[app] `<build><extensions>` com.acme:my-extension:1.0");
                    assertThat(m).doesNotContain("inherited by");
                });
        assertThat(rows.stream().filter(m -> m.contains("acme-maven-plugin")))
                .singleElement()
                .satisfies(
                        m -> assertThat(m).startsWith("[app] `<plugin>acme-maven-plugin</plugin>` was not imported"));
        assertThat(result.report().issues().stream()
                        .filter(i -> i.message().contains("wagon-ssh"))
                        .map(ImportReport.Issue::severity))
                .containsExactly(ImportReport.Severity.ERROR);
    }

    @Test
    void a_pom_imported_on_its_own_keeps_every_row(@TempDir Path root) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-enforcer-plugin</artifactId>
                        <version>3.4.1</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        write(root, "app/pom.xml", leaf("app", "parent", "../pom.xml", ""));

        PomImporter.Result result = TestImporters.offline(root).importFrom(root.resolve("app/pom.xml"));

        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.equals("`<plugin>maven-enforcer-plugin</plugin>` was not imported;"
                        + " docs/user/migration.md lists where it lands in jk."));
    }

    private static String parent(String artifactId, String relativePath) {
        return """
                <parent>
                  <groupId>org.demo</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <relativePath>%s/pom.xml</relativePath>
                </parent>
                """.formatted(artifactId, relativePath);
    }

    private static String leaf(String artifactId, String parentArtifactId, String relativePath, String build) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0</version>
                    <relativePath>%s</relativePath>
                  </parent>
                  <artifactId>%s</artifactId>
                  %s
                </project>
                """.formatted(parentArtifactId, relativePath, artifactId, build);
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
