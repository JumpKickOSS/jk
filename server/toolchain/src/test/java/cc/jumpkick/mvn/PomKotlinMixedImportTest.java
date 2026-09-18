// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A POM with {@code kotlin-maven-plugin} beside Java main sources is a mixed module: {@code java =}
 * for javac's level and {@code kotlin =} for kotlinc, so the Kotlin test tree compiles against the
 * Java classes. A module whose main tree is Kotlin alone keeps {@code kotlin =} on its own.
 */
class PomKotlinMixedImportTest {

    private static final String KOTLIN_PLUGIN = """
            <plugin>
              <groupId>org.jetbrains.kotlin</groupId>
              <artifactId>kotlin-maven-plugin</artifactId>
              <version>2.4.20</version>
              <executions>
                <execution>
                  <id>test-compile</id>
                  <goals><goal>test-compile</goal></goals>
                  <configuration>
                    <sourceDirs>
                      <source>src/test/java</source>
                      <source>src/test/kotlin</source>
                    </sourceDirs>
                  </configuration>
                </execution>
              </executions>
            </plugin>
            """;

    private static String pom(String plugins) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>net.demo</groupId>
                  <artifactId>faker</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                  <build>
                    <plugins>
                """ + plugins + """
                    </plugins>
                  </build>
                </project>
                """;
    }

    @Test
    void java_main_sources_beside_kotlin_tests_import_as_a_mixed_module(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(project.resolve("src/main/java/net/demo"));
        Files.writeString(project.resolve("src/main/java/net/demo/Faker.java"), "package net.demo; class Faker {}");
        Files.createDirectories(project.resolve("src/test/kotlin/net/demo"));
        Files.writeString(
                project.resolve("src/test/kotlin/net/demo/FakerTest.kt"), "package net.demo\nclass FakerTest");

        PomImporter.Result result = TestImporters.importXml(tempDir, pom(KOTLIN_PLUGIN));
        JkBuild build = result.jkBuild();

        assertThat(build.project().java()).as("javac's level for src/main/java").isEqualTo(17);
        assertThat(build.project().kotlin())
                .as("kotlinc's version for the Kotlin sources")
                .isNotNull()
                .extracting(VersionSelector::raw)
                .isEqualTo("2.4.20");
        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("kotlin   = \"2.4.20\"\n").contains("java     = 17\n");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.project().java()).isEqualTo(17);
        assertThat(reparsed.project().isKotlin()).isTrue();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("`kotlin-maven-plugin` beside Java sources under `src/main/java`"))
                .noneMatch(m -> m.contains("was not imported"));
    }

    @Test
    void a_kotlin_only_main_tree_keeps_kotlin_alone(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(project.resolve("src/main/kotlin/net/demo"));
        Files.writeString(project.resolve("src/main/kotlin/net/demo/Faker.kt"), "package net.demo\nclass Faker");

        PomImporter.Result result =
                TestImporters.importXml(tempDir, pom(KOTLIN_PLUGIN.replace("test-compile", "compile")));

        assertThat(result.jkBuild().project().java()).isEqualTo(0);
        assertThat(result.jkBuild().project().kotlin()).isNotNull();
        assertThat(JkBuildRenderer.render(result.jkBuild()))
                .contains("kotlin   = \"2.4.20\"\n")
                .doesNotContain("java     = ");
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("mixed"));
    }

    /** An archive's POM has no tree behind it: a Kotlin plugin that never binds {@code compile} leaves main to javac. */
    @Test
    void without_a_tree_a_test_compile_only_plugin_means_java_main(@TempDir Path tempDir) throws Exception {
        PomImporter importer = TestImporters.offline(tempDir);
        PomImporter.Result result = importer.importFromBytes(pom(KOTLIN_PLUGIN).getBytes());
        assertThat(result.jkBuild().project().java()).isEqualTo(17);
        assertThat(result.jkBuild().project().kotlin()).isNotNull();
    }
}
