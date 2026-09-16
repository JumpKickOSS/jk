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
 * A reactor POM the workspace does not build — an aggregator, or a module only an inactive profile
 * lists — is not published anywhere, so a dependency that names it is dropped with a row instead
 * of being written as a coordinate the lock would go looking for.
 */
class PomReactorUnbuiltDependencyImportTest {

    private static final String PARENT = """
            <parent>
              <groupId>org.demo</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
            </parent>
            """;

    @Test
    void a_pom_type_dependency_on_an_aggregator_is_dropped_with_a_row_naming_its_modules(@TempDir Path root)
            throws Exception {
        writeReactor(root, "");
        write(root, "docs/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>docs</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.demo</groupId>
                      <artifactId>js-parent</artifactId>
                      <version>${project.version}</version>
                      <type>pom</type>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(PARENT));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        JkBuild docs = requireNonNull(result.modules().get("docs"));
        assertThat(docs.dependencies().of(Scope.PLATFORM))
                .as("an aggregator is not a BOM any repository has")
                .isEmpty();
        assertThat(result.report().issues())
                .filteredOn(i -> i.message().contains("js-parent names"))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.severity()).isEqualTo(ImportReport.Severity.WARNING);
                    assertThat(issue.message())
                            .startsWith(
                                    "[docs] `<type>pom</type>` on org.demo:js-parent names `js/pom.xml`, the aggregator of"
                                            + " `account-ui`, `admin-ui`")
                            .contains("no row is written");
                });
    }

    @Test
    void an_aggregator_with_compile_dependencies_of_its_own_names_what_the_pom_edge_carried(@TempDir Path root)
            throws Exception {
        writeReactor(root, """
                <dependencies>
                  <dependency>
                    <groupId>org.slf4j</groupId>
                    <artifactId>slf4j-api</artifactId>
                    <version>2.0.17</version>
                  </dependency>
                  <dependency>
                    <groupId>org.demo</groupId>
                    <artifactId>services</artifactId>
                    <version>1.0.0</version>
                    <scope>provided</scope>
                  </dependency>
                </dependencies>
                """);
        write(root, "docs/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>docs</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.demo</groupId>
                      <artifactId>js-parent</artifactId>
                      <version>1.0.0</version>
                      <type>pom</type>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(PARENT));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().issues())
                .filteredOn(i -> i.message().contains("js-parent names"))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.severity())
                            .as("Maven would have put slf4j-api on the classpath through the pom")
                            .isEqualTo(ImportReport.Severity.ERROR);
                    assertThat(issue.message())
                            .contains("its own dependencies org.slf4j:slf4j-api")
                            .doesNotContain("org.demo:services");
                });
    }

    @Test
    void a_dependency_on_a_module_only_an_inactive_profile_lists_names_the_profile(@TempDir Path root)
            throws Exception {
        writeReactor(root, "");
        write(root, "legacy/pom.xml", leaf("legacy"));
        write(root, "docs/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>docs</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.demo</groupId>
                      <artifactId>legacy</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(PARENT));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(result.root().workspace()).modules()).doesNotContain("legacy");
        JkBuild docs = requireNonNull(result.modules().get("docs"));
        List<Dependency> main = docs.dependencies().of(Scope.MAIN);
        assertThat(main)
                .as("no repository has a module Maven does not build here")
                .isEmpty();
        assertThat(result.report().issues())
                .filteredOn(i -> i.message().contains("org.demo:legacy"))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.severity()).isEqualTo(ImportReport.Severity.ERROR);
                    assertThat(issue.message())
                            .startsWith(
                                    "[docs] org.demo:legacy names `legacy/pom.xml`, which only profile `jdk8` of the"
                                            + " root pom.xml lists, and that profile is not active on this machine")
                            .contains("the dependency was not written");
                });
    }

    /** A root listing `js` (an aggregator of two members) and `docs`, plus `legacy` under an inactive profile. */
    private static void writeReactor(Path root, String aggregatorDependencies) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>js</module>
                    <module>docs</module>
                  </modules>
                  <profiles>
                    <profile>
                      <id>jdk8</id>
                      <activation><jdk>1.8</jdk></activation>
                      <modules>
                        <module>legacy</module>
                      </modules>
                    </profile>
                  </profiles>
                </project>
                """);
        write(root, "js/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>js-parent</artifactId>
                  <packaging>pom</packaging>
                  <modules>
                    <module>account-ui</module>
                    <module>admin-ui</module>
                  </modules>
                  %s
                </project>
                """.formatted(PARENT, aggregatorDependencies));
        write(root, "js/account-ui/pom.xml", leaf("account-ui"));
        write(root, "js/admin-ui/pom.xml", leaf("admin-ui"));
    }

    private static String leaf(String artifactId) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(PARENT, artifactId);
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
