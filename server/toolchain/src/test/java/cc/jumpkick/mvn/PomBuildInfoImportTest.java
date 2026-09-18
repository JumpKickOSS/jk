// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where git build info lands: both git-commit-id plugin ids and Boot's {@code build-info} goal are
 * the {@code [build-info]} table; a properties file under the output directory keeps its name; the
 * constructs jk does not reproduce are rows.
 */
class PomBuildInfoImportTest {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.ex</groupId>
              <artifactId>svc</artifactId>
              <version>1.0.0</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins>
            """;
    private static final String TAIL = """
              </plugins></build>
            </project>
            """;

    @Test
    void the_legacy_plugin_id_with_a_renamed_file_under_the_output_directory(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                <plugin>
                  <groupId>pl.project13.maven</groupId>
                  <artifactId>git-commit-id-plugin</artifactId>
                  <version>2.2.6</version>
                  <executions><execution><goals><goal>revision</goal></goals></execution></executions>
                  <configuration>
                    <verbose>true</verbose>
                    <dateFormat>yyyy-MM-dd'T'HH:mm:ssZ</dateFormat>
                    <generateGitPropertiesFile>true</generateGitPropertiesFile>
                    <generateGitPropertiesFilename>${project.build.outputDirectory}/apollo-git.properties</generateGitPropertiesFilename>
                    <failOnNoGitDirectory>false</failOnNoGitDirectory>
                  </configuration>
                </plugin>
                """ + TAIL);
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        assertThat(build.build().buildInfo()).isEqualTo(new BuildBlock.BuildInfo("apollo-git.properties", false));
        assertThat(messages).noneMatch(m -> m.startsWith("`<plugin>"));
        assertThat(messages).noneMatch(m -> m.contains("git-commit-id"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("\n[build-info]\nfile = \"apollo-git.properties\"\n");
        assertThat(JkBuildParser.parse(rendered).build().buildInfo())
                .isEqualTo(new BuildBlock.BuildInfo("apollo-git.properties", false));
    }

    @Test
    void the_current_plugin_id_at_its_defaults_is_the_empty_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                <plugin>
                  <groupId>io.github.git-commit-id</groupId>
                  <artifactId>git-commit-id-maven-plugin</artifactId>
                  <version>9.0.1</version>
                  <executions><execution><id>extract-git-info</id><goals><goal>revision</goal></goals></execution></executions>
                  <configuration><failOnNoGitDirectory>false</failOnNoGitDirectory></configuration>
                </plugin>
                """ + TAIL);
        JkBuild build = result.jkBuild();

        assertThat(build.build().buildInfo()).isEqualTo(BuildBlock.BuildInfo.DEFAULT);
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("git-commit-id"));
        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("\n[build-info]\n");
        assertThat(rendered).doesNotContain("file = ");
        assertThat(JkBuildParser.parse(rendered).build().buildInfo()).isEqualTo(BuildBlock.BuildInfo.DEFAULT);
    }

    @Test
    void boots_build_info_goal_is_the_same_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                <plugin>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-maven-plugin</artifactId>
                  <version>3.5.5</version>
                  <executions><execution><goals><goal>build-info</goal><goal>repackage</goal></goals></execution></executions>
                </plugin>
                """ + TAIL);
        JkBuild build = result.jkBuild();

        assertThat(build.pluginConfig("spring-boot")).isPresent();
        assertThat(build.build().buildInfo()).isEqualTo(BuildBlock.BuildInfo.DEFAULT);
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("build-info"));
    }

    @Test
    void a_boot_plugin_without_the_goal_writes_no_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                <plugin>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-maven-plugin</artifactId>
                  <version>3.5.5</version>
                  <executions><execution><goals><goal>repackage</goal></goals></execution></executions>
                </plugin>
                """ + TAIL);
        assertThat(result.jkBuild().build().buildInfo()).isNull();
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("[build-info]");
    }

    @Test
    void json_output_and_a_file_outside_the_output_directory_are_rows(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                <plugin>
                  <groupId>io.github.git-commit-id</groupId>
                  <artifactId>git-commit-id-maven-plugin</artifactId>
                  <version>9.0.1</version>
                  <executions><execution><goals><goal>revision</goal></goals></execution></executions>
                  <configuration>
                    <format>json</format>
                    <dateFormat>yyyy-MM-dd HH:mm:ss</dateFormat>
                    <generateGitPropertiesFilename>${project.basedir}/src/main/resources/build/git.json</generateGitPropertiesFilename>
                  </configuration>
                </plugin>
                """ + TAIL);
        List<String> messages = TestImporters.messages(result);

        assertThat(result.jkBuild().build().buildInfo())
                .as("the file keeps its name inside the jar; the directory outside classes does not travel")
                .isEqualTo(new BuildBlock.BuildInfo("git.json", false));
        assertThat(messages).anyMatch(m -> m.contains("`<format>json`"));
        assertThat(messages).anyMatch(m -> m.contains("`<dateFormat>`"));
        assertThat(messages).anyMatch(m -> m.contains("`<generateGitPropertiesFilename>`"));
    }
}
