// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A POM's inline {@code <dependencyManagement>} governs transitive versions after import as it
 * does under Maven: the pins no declared dependency uses are written to {@code
 * [managed-dependencies]}, a reactor parent's once on the workspace root, an entry's {@code
 * <exclusions>} ride the row as {@code exclude} whether or not a declared dependency uses its
 * version, and the shadow manifest rendered from the same model reads back with the table.
 */
class PomManagedImportTest {

    @Test
    void inline_pins_nothing_declares_are_written_as_managed_dependencies(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>0.1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>commons-io</groupId>
                        <artifactId>commons-io</artifactId>
                        <version>2.16.1</version>
                      </dependency>
                      <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>33.4.0-jre</version>
                      </dependency>
                      <dependency>
                        <groupId>org.yaml</groupId>
                        <artifactId>snakeyaml</artifactId>
                        <exclusions>
                          <exclusion><groupId>org.foo</groupId><artifactId>bar</artifactId></exclusion>
                        </exclusions>
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
                """);
        JkBuild build = result.jkBuild();

        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.google.guava:guava");
        assertThat(build.dependencies().of(Scope.MANAGED))
                .as("the pin guava's declaration uses is guava's own; commons-io governs a transitive")
                .extracting(Dependency::library, Dependency::module, d -> d.version()
                        .raw())
                .containsExactly(tuple("commons-io", "commons-io:commons-io", "2.16.1"));

        List<String> messages = TestImporters.messages(result);
        assertThat(messages)
                .anySatisfy(m -> assertThat(m)
                        .contains("`<dependencyManagement>` in this POM pins 1 version no declared dependency uses"
                                + " (commons-io:commons-io)")
                        .contains("written to [managed-dependencies]"))
                .anySatisfy(m -> assertThat(m).contains("1 entry with exclusions and no version (org.yaml:snakeyaml)"))
                .noneMatch(m -> m.contains("declared dependencies only"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("[managed-dependencies]\ncommons-io = ");
        assertThat(JkBuildParser.parse(rendered).dependencies().of(Scope.MANAGED))
                .as("the shadow manifest is this rendering, so it reads back")
                .extracting(Dependency::module)
                .containsExactly("commons-io:commons-io");
    }

    @Test
    void a_managed_entrys_exclusions_are_written_on_its_row_even_when_a_declaration_uses_the_pin(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>0.1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.apache.hadoop</groupId>
                        <artifactId>hadoop-common</artifactId>
                        <version>3.4.1</version>
                        <exclusions>
                          <exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>
                        </exclusions>
                      </dependency>
                      <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                        <exclusions>
                          <exclusion><groupId>org.hamcrest</groupId><artifactId>*</artifactId></exclusion>
                        </exclusions>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.hadoop</groupId>
                      <artifactId>hadoop-common</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        JkBuild build = result.jkBuild();

        assertThat(build.dependencies().of(Scope.MAIN))
                .as("the declaration takes the managed exclusions, as Maven's effective model gives them")
                .extracting(Dependency::module, Dependency::exclusions)
                .containsExactly(tuple("org.apache.hadoop:hadoop-common", List.of("*:*")));
        assertThat(build.dependencies().of(Scope.MANAGED))
                .as("both entries carry exclusions, so both are rows: they prune every edge onto the module")
                .extracting(Dependency::module, d -> d.version().raw(), Dependency::exclusions)
                .containsExactly(
                        tuple("org.apache.hadoop:hadoop-common", "3.4.1", List.of("*:*")),
                        tuple("junit:junit", "4.13.2", List.of("org.hamcrest:*")));
        assertThat(TestImporters.messages(result))
                .anySatisfy(m -> assertThat(m)
                        .contains("`<dependencyManagement>` in this POM excludes coordinates under 2 modules"
                                + " (org.apache.hadoop:hadoop-common, junit:junit)")
                        .contains("written to [managed-dependencies] with `exclude`"))
                .noneMatch(m -> m.contains("pins 1 version no declared dependency uses"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains(
                        "[managed-dependencies]\nhadoop-common = { group = \"org.apache.hadoop\", version = \"3.4.1\","
                                + " exclude = [\"*:*\"] }");
        assertThat(JkBuildParser.parse(rendered).dependencies().of(Scope.MANAGED))
                .extracting(Dependency::module, Dependency::exclusions)
                .containsExactly(
                        tuple("org.apache.hadoop:hadoop-common", List.of("*:*")),
                        tuple("junit:junit", List.of("org.hamcrest:*")));
    }

    @Test
    void a_reactor_parents_pins_are_written_once_on_the_root(@TempDir Path root) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>app</module></modules>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>commons-io</groupId><artifactId>commons-io</artifactId><version>2.16.1</version>
                      </dependency>
                      <dependency>
                        <groupId>com.ex</groupId><artifactId>core</artifactId><version>1.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.apache.hadoop</groupId><artifactId>hadoop-common</artifactId><version>3.4.1</version>
                        <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        write(root, "core/pom.xml", leaf("core", """
                <dependencies>
                  <dependency><groupId>org.apache.hadoop</groupId><artifactId>hadoop-common</artifactId></dependency>
                </dependencies>
                """));
        write(root, "app/pom.xml", leaf("app", """
                <dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId><version>2.3</version>
                    </dependency>
                  </dependencies>
                </dependencyManagement>
                """));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().hasErrors()).isFalse();
        assertThat(result.root().dependencies().of(Scope.MANAGED))
                .as("the parent's pin, once; the pin on the sibling `core` is the workspace's to supply; the"
                        + " entry `core` declares is a row for its exclusions")
                .extracting(Dependency::module, d -> d.version().raw(), Dependency::exclusions)
                .containsExactly(
                        tuple("commons-io:commons-io", "2.16.1", List.of()),
                        tuple("org.apache.hadoop:hadoop-common", "3.4.1", List.of("*:*")));
        assertThat(requireNonNull(result.modules().get("core")).dependencies().of(Scope.MANAGED))
                .isEmpty();
        assertThat(requireNonNull(result.modules().get("app")).dependencies().of(Scope.MANAGED))
                .as("a leaf's own table stays on the leaf")
                .extracting(Dependency::module)
                .containsExactly("org.yaml:snakeyaml");
        assertThat(JkBuildRenderer.render(result.root())).contains("[managed-dependencies]");
        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .filter(m -> m.contains("[managed-dependencies]"))
                .toList();
        assertThat(rows)
                .as("the parent's pins are one row at the root; a leaf's own table is the leaf's row")
                .satisfiesExactlyInAnyOrder(
                        m -> assertThat(m)
                                .startsWith("`<dependencyManagement>` of the reactor's parent POMs pins 1 version"
                                        + " no module declares (commons-io:commons-io)")
                                .contains("written once to the root's [managed-dependencies]"),
                        m -> assertThat(m)
                                .startsWith("`<dependencyManagement>` of the reactor's parent POMs excludes"
                                        + " coordinates under 1 module (org.apache.hadoop:hadoop-common)")
                                .contains("written once to the root's [managed-dependencies] with `exclude`"),
                        m -> assertThat(m).startsWith("[app] `<dependencyManagement>` in this POM pins 1 version"));
    }

    private static String leaf(String artifactId, String extra) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.ex</groupId><artifactId>parent</artifactId><version>1.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  %s
                </project>
                """.formatted(artifactId, extra);
    }

    private static void write(Path root, String rel, String xml) throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
