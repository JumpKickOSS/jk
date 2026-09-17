// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A framework plugin on a module of the framework's own reactor: {@code quarkus-maven-plugin} at
 * the reactor's version in a reactor that builds {@code quarkus-bom} implies a platform BOM no
 * repository publishes, so no {@code [quarkus]} table is written and one row says why. A release
 * version keeps its table, and so does a reactor that does not build the BOM.
 */
class PomSelfHostedFrameworkImportTest {

    @Test
    void the_quarkus_table_at_the_reactors_own_version_is_dropped_when_the_reactor_builds_the_bom(@TempDir Path root)
            throws Exception {
        write(root, "pom.xml", parent(List.of("bom/application", "app", "released")));
        write(root, "bom/application/pom.xml", bom(true));
        write(root, "app/pom.xml", leaf("quarkus-app", quarkusPlugin("${project.version}")));
        write(root, "released/pom.xml", leaf("quarkus-released", quarkusPlugin("3.39.2")));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().hasErrors()).isFalse();
        JkBuild app = requireNonNull(result.modules().get("app"));
        assertThat(app.pluginConfig("quarkus"))
                .as("the reactor's own version names no published platform BOM")
                .isEmpty();
        assertThat(JkBuildRenderer.render(app)).doesNotContain("[quarkus]");
        assertThat(requireNonNull(result.modules().get("released"))
                        .pluginConfig("quarkus")
                        .orElseThrow()
                        .string("version"))
                .as("a release version is a published platform BOM")
                .isEqualTo("3.39.2");
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anySatisfy(m -> assertThat(m)
                        .contains("(app)")
                        .contains("`[quarkus]`")
                        .contains("999-SNAPSHOT")
                        .contains("io.quarkus.platform:quarkus-bom:999-SNAPSHOT")
                        .contains("bom/application")
                        .contains("jk mvn"));
    }

    @Test
    void a_bom_leaf_marks_the_reactor_as_the_framework_too(@TempDir Path root) throws Exception {
        write(root, "pom.xml", parent(List.of("bom/application", "app")));
        write(root, "bom/application/pom.xml", bom(false));
        write(root, "app/pom.xml", leaf("quarkus-app", quarkusPlugin("${project.version}")));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().hasErrors()).isFalse();
        assertThat(requireNonNull(result.modules().get("app")).pluginConfig("quarkus"))
                .isEmpty();
        assertThat(requireNonNull(result.root().workspace()).modules()).containsExactly("app");
    }

    @Test
    void a_reactor_that_does_not_build_the_bom_keeps_the_table(@TempDir Path root) throws Exception {
        write(root, "pom.xml", parent(List.of("app")));
        write(root, "app/pom.xml", leaf("quarkus-app", quarkusPlugin("${project.version}")));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(result.modules().get("app"))
                        .pluginConfig("quarkus")
                        .orElseThrow()
                        .string("version"))
                .isEqualTo("999-SNAPSHOT");
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneSatisfy(m -> assertThat(m).contains("io.quarkus.platform:quarkus-bom:999-SNAPSHOT"));
    }

    private static String parent(List<String> modules) {
        String list =
                modules.stream().map(m -> "    <module>" + m + "</module>\n").reduce("", String::concat);
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-project</artifactId>
                  <version>999-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules>
                %s  </modules>
                </project>
                """.formatted(list);
    }

    /** Quarkus's {@code bom/application}: with plugins it is a module of the reactor, without them a BOM leaf. */
    private static String bom(boolean withPlugins) {
        String plugins = withPlugins ? """
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.cyclonedx</groupId>
                        <artifactId>cyclonedx-maven-plugin</artifactId>
                        <version>2.9.1</version>
                      </plugin>
                    </plugins>
                  </build>
                """ : "";
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-project</artifactId>
                    <version>999-SNAPSHOT</version>
                  </parent>
                  <artifactId>quarkus-bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.assertj</groupId>
                        <artifactId>assertj-core</artifactId>
                        <version>3.27.7</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                %s</project>
                """.formatted(plugins);
    }

    private static String quarkusPlugin(String version) {
        return """
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>io.quarkus</groupId>
                        <artifactId>quarkus-maven-plugin</artifactId>
                        <version>%s</version>
                      </plugin>
                    </plugins>
                  </build>
                """.formatted(version);
    }

    private static String leaf(String artifactId, String body) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-project</artifactId>
                    <version>999-SNAPSHOT</version>
                  </parent>
                  <artifactId>%s</artifactId>
                %s</project>
                """.formatted(artifactId, body);
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
