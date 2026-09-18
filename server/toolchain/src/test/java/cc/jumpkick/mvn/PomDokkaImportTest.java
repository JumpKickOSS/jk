// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.gradle.GradleImporter;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where Dokka lands: the Maven plugin's version and the Gradle plugin's inline version are the
 * {@code [dokka]} pin; a Maven plugin bound only to its {@code dokka} goal keeps the HTML format.
 */
class PomDokkaImportTest {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.ex</groupId>
              <artifactId>lib</artifactId>
              <version>1.0.0</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins>
            """;
    private static final String TAIL = """
              </plugins></build>
            </project>
            """;

    @Test
    void the_maven_plugin_version_is_the_pin_and_the_javadoc_goal_keeps_the_default_format(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                <plugin>
                  <groupId>org.jetbrains.dokka</groupId>
                  <artifactId>dokka-maven-plugin</artifactId>
                  <version>2.1.0</version>
                  <executions><execution><phase>prepare-package</phase><goals><goal>javadocJar</goal></goals></execution></executions>
                </plugin>
                """ + TAIL);
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        BuildBlock.Dokka dokka = build.build().dokka();
        assertThat(dokka.version()).isEqualTo(VersionSelector.parse("2.1.0"));
        assertThat(dokka.format()).isEqualTo(BuildBlock.Dokka.Format.JAVADOC);
        assertThat(messages).noneMatch(m -> m.startsWith("`<plugin>"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("\n[dokka]\nversion = \"2.1.0\"\n");
        assertThat(rendered).doesNotContain("format = ");
        assertThat(JkBuildParser.parse(rendered).build().dokka()).isEqualTo(dokka);
    }

    @Test
    void the_dokka_goal_alone_is_the_html_format(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                <plugin>
                  <groupId>org.jetbrains.dokka</groupId>
                  <artifactId>dokka-maven-plugin</artifactId>
                  <version>2.2.0</version>
                  <executions><execution><goals><goal>dokka</goal></goals></execution></executions>
                </plugin>
                """ + TAIL);
        BuildBlock.Dokka dokka = result.jkBuild().build().dokka();
        assertThat(dokka.format()).isEqualTo(BuildBlock.Dokka.Format.HTML);
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("\n[dokka]\nformat = \"html\"\n");
        assertThat(rendered).doesNotContain("version = \"2.2.0\"");
    }

    @Test
    void a_pom_without_the_plugin_renders_no_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + TAIL);
        assertThat(result.jkBuild().build().dokka()).isEqualTo(BuildBlock.Dokka.DEFAULT);
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("[dokka]");
    }

    @Test
    void the_gradle_plugin_s_inline_version_is_the_pin() {
        var result = GradleImporter.importFromString("""
                plugins {
                    kotlin("jvm") version "2.4.20"
                    id("org.jetbrains.dokka") version "2.1.0"
                }
                """, "demo");
        assertThat(result.jkBuild().build().dokka().version()).isEqualTo(VersionSelector.parse("2.1.0"));
        assertThat(result.report().issues()).noneMatch(i -> i.message().contains("org.jetbrains.dokka"));
        assertThat(JkBuildRenderer.render(result.jkBuild())).contains("\n[dokka]\nversion = \"2.1.0\"\n");
    }
}
