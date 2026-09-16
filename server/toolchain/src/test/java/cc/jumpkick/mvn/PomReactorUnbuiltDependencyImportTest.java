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
 * of being written as a coordinate the lock would go looking for; a {@code <type>pom</type>} edge
 * to an aggregator hands the aggregator's own classpath dependencies to the dependent instead.
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

    /**
     * keycloak's shape: {@code docs/maven-plugin} depends on the {@code js} aggregator as a pom, and
     * the aggregator declares compile dependencies of its own, one of them a reactor member. Maven
     * put those on the dependent's classpath through the pom, so the import writes them on the
     * dependent in the pom edge's place, the member as a workspace edge; what the dependent declares
     * itself is left alone, and a {@code provided} dependency of the aggregator never rode.
     */
    @Test
    void a_pom_type_dependency_on_an_aggregator_with_dependencies_carries_them_onto_the_dependent(@TempDir Path root)
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
                    <artifactId>account-ui</artifactId>
                    <version>1.0.0</version>
                  </dependency>
                  <dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava</artifactId>
                    <version>33.4.0-jre</version>
                    <scope>runtime</scope>
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
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.16</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(PARENT));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        JkBuild docs = requireNonNull(result.modules().get("docs"));
        assertThat(docs.dependencies().of(Scope.PLATFORM)).isEmpty();
        assertThat(docs.dependencies().of(Scope.MAIN))
                .extracting(d -> d.isWorkspace()
                        ? d.workspaceName() + " (workspace)"
                        : d.module() + "=" + d.version().raw())
                .as("the dependent's own pin stays; the aggregator's compile deps follow, the member as an edge")
                .containsExactly("org.slf4j:slf4j-api=2.0.16", "account-ui (workspace)");
        assertThat(docs.dependencies().of(Scope.RUNTIME))
                .extracting(d -> d.module() + "=" + d.version().raw())
                .containsExactly("com.google.guava:guava=33.4.0-jre");
        assertThat(docs.dependencies().of(Scope.PROVIDED)).isEmpty();
        assertThat(result.report().issues())
                .filteredOn(i -> i.message().contains("js-parent names"))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.severity())
                            .as("nothing Maven put on the classpath is lost")
                            .isEqualTo(ImportReport.Severity.WARNING);
                    assertThat(issue.message())
                            .contains("its own dependencies org.demo:account-ui (workspace), com.google.guava:guava")
                            .contains("are written on this module in its place")
                            .doesNotContain("org.demo:services")
                            .doesNotContain("slf4j");
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
