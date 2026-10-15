// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code javafx-maven-plugin} imports as {@code [javafx]}: the modules are the POM's OpenJFX
 * dependencies, and a modular {@code module/class} main is the class.
 */
class PomJavafxImportTest {

    @Test
    void the_plugin_becomes_the_table_and_its_modular_main_the_application_main(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result =
                TestImporters.importXml(tempDir, pom(List.of("javafx-controls", "javafx-fxml"), """
                <plugin>
                  <groupId>org.openjfx</groupId>
                  <artifactId>javafx-maven-plugin</artifactId>
                  <version>0.0.8</version>
                  <configuration>
                    <mainClass>hellofx/org.openjfx.App</mainClass>
                  </configuration>
                </plugin>
                """));

        assertThat(result.jkBuild().pluginConfig("javafx").orElseThrow().values())
                .as("the default modules are not written")
                .isEmpty();
        assertThat(result.jkBuild().mainClass()).isEqualTo("org.openjfx.App");
        assertThat(messages(result)).noneMatch(m -> m.contains("javafx-maven-plugin` is not"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[javafx]");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("javafx")).isPresent();
    }

    @Test
    void other_modules_are_written_and_options_are_reported(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result =
                TestImporters.importXml(tempDir, pom(List.of("javafx-controls", "javafx-web"), """
                <plugin>
                  <groupId>org.openjfx</groupId>
                  <artifactId>javafx-maven-plugin</artifactId>
                  <version>0.0.8</version>
                  <configuration>
                    <mainClass>org.openjfx.App</mainClass>
                    <options><option>-Dprism.order=sw</option></options>
                  </configuration>
                </plugin>
                """));

        assertThat(result.jkBuild().pluginConfig("javafx").orElseThrow().values())
                .isEqualTo(Map.of("modules", List.of("javafx.controls", "javafx.web")));
        assertThat(messages(result)).anyMatch(m -> m.contains("<options> has no `[javafx]` key"));
    }

    private static String pom(List<String> openjfx, String plugin) {
        StringBuilder deps = new StringBuilder();
        for (String artifact : openjfx) {
            deps.append("""
                    <dependency>
                      <groupId>org.openjfx</groupId>
                      <artifactId>%s</artifactId>
                      <version>27</version>
                    </dependency>
                    """.formatted(artifact));
        }
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>hellofx</artifactId>
                  <version>1.0</version>
                  <dependencies>
                """ + deps + """
                  </dependencies>
                  <build>
                    <plugins>
                """ + plugin + """
                    </plugins>
                  </build>
                </project>
                """;
    }
}
