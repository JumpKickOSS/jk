// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.BuildBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code <resources>} as {@code [resources]}, on the shape Jenkins' parent gives every module. */
class PomResourcesImportTest {

    private static final String PARENT = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.ex</groupId>
              <artifactId>parent</artifactId>
              <version>2.583</version>
              <packaging>pom</packaging>
              <properties>
                <remoting.version>3391.va_37fa_a_305d6d</remoting.version>
              </properties>
              <build>
                <resources>
                  <resource>
                    <filtering>false</filtering>
                    <directory>${basedir}/src/main/resources</directory>
                  </resource>
                  <resource>
                    <filtering>true</filtering>
                    <directory>${basedir}/src/filter/resources</directory>
                  </resource>
                </resources>
              </build>
            </project>
            """;

    private static Path child(Path root, String name, String props) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.ex</groupId>
                    <artifactId>parent</artifactId>
                    <version>2.583</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>%s</artifactId>
                  <properties>%s</properties>
                </project>
                """.formatted(name, props));
        return dir;
    }

    @Test
    void a_filtered_directory_is_a_filtered_root_with_the_values_its_files_reference(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), PARENT);
        Path core = child(
                root,
                "core",
                "<remoting.minimum.supported.version>3176.v207ec082a_8c0</remoting.minimum.supported.version>");
        Path info = Files.createDirectories(core.resolve("src/filter/resources/jenkins/slaves"));
        Files.writeString(info.resolve("remoting-info.properties"), """
                remoting.embedded.version=${remoting.version}
                remoting.minimum.supported.version=${remoting.minimum.supported.version}
                """);
        Path model = Files.createDirectories(core.resolve("src/filter/resources/jenkins/model"));
        Files.writeString(model.resolve("jenkins-version.properties"), """
                version=${project.version}
                changelog.url=${changelog.url}
                home=${env.HOME}
                """);
        Files.createDirectories(core.resolve("src/main/resources"));

        PomImporter.Result result = TestImporters.offline(root).importFrom(core.resolve("pom.xml"));
        BuildBlock.Resources r = result.jkBuild().build().resources();

        assertThat(r.filtered()).containsExactly("src/filter/resources");
        assertThat(r.dirs()).as("the layout root is jk's already").isEmpty();
        assertThat(r.properties())
                .containsEntry("remoting.version", "3391.va_37fa_a_305d6d")
                .containsEntry("remoting.minimum.supported.version", "3176.v207ec082a_8c0")
                .doesNotContainKey("project.version");
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.contains("${changelog.url}")
                        && m.contains("${env.HOME}")
                        && m.contains("[resources.properties]"));

        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("\n[resources]\nfiltered = [\"src/filter/resources\"]\n");
        assertThat(JkBuildParser.parse(rendered).build().resources()).isEqualTo(r);
    }

    @Test
    void a_module_without_the_inherited_directory_gets_no_resources_table(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), PARENT);
        Path cli = child(root, "cli", "");
        Files.createDirectories(cli.resolve("src/main/resources"));

        PomImporter.Result result = TestImporters.offline(root).importFrom(cli.resolve("pom.xml"));

        assertThat(result.jkBuild().build().resources()).isEqualTo(BuildBlock.Resources.EMPTY);
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("[resources]");
    }

    @Test
    void an_extra_root_includes_and_target_path_are_rows(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <resources>
                      <resource><directory>src/main/config</directory><targetPath>conf</targetPath></resource>
                    </resources>
                    <testResources>
                      <testResource><directory>src/it/resources</directory><filtering>true</filtering></testResource>
                    </testResources>
                  </build>
                </project>
                """);
        Files.createDirectories(root.resolve("src/main/config"));
        Files.createDirectories(root.resolve("src/it/resources"));
        Files.writeString(root.resolve("src/it/resources/it.properties"), "name=${project.artifactId}\n");

        PomImporter.Result result = TestImporters.offline(root).importFrom(root.resolve("pom.xml"));
        BuildBlock.Resources r = result.jkBuild().build().resources();

        assertThat(r.dirs()).containsExactly("src/main/config");
        assertThat(r.testFiltered()).containsExactly("src/it/resources");
        assertThat(r.properties()).isEmpty();
        assertThat(TestImporters.messages(result)).anyMatch(m -> m.contains("`<targetPath>` on src/main/config"));
    }

    /**
     * neo4j's parent: the layout root, its META-INF again with that same target path, and the module
     * directory itself narrowed to three license files. The first two are the layout root's files
     * where they already land; the module directory is never a root, it would carry the sources.
     */
    @Test
    void the_module_directory_and_a_layout_subdirectory_at_its_own_path_are_no_roots(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <resources>
                      <resource><directory>${basedir}/src/main/resources</directory></resource>
                      <resource>
                        <targetPath>META-INF</targetPath>
                        <directory>${basedir}/src/main/resources/META-INF/</directory>
                      </resource>
                      <resource>
                        <targetPath>META-INF</targetPath>
                        <directory>${basedir}</directory>
                        <includes><include>LICENSE.txt</include></includes>
                      </resource>
                    </resources>
                    <testResources>
                      <testResource><directory>${basedir}</directory></testResource>
                    </testResources>
                  </build>
                </project>
                """);
        Files.createDirectories(root.resolve("src/main/resources/META-INF"));
        Files.writeString(root.resolve("LICENSE.txt"), "license\n");

        PomImporter.Result result = TestImporters.offline(root).importFrom(root.resolve("pom.xml"));
        BuildBlock.Resources r = result.jkBuild().build().resources();

        assertThat(r).isEqualTo(BuildBlock.Resources.EMPTY);
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).doesNotContain("[resources]");
        assertThat(JkBuildParser.parse(rendered).build().resources()).isEqualTo(r);
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.contains("the module directory itself"))
                .noneMatch(m -> m.contains("`<targetPath>` on src/main/resources/META-INF"));
    }
}
