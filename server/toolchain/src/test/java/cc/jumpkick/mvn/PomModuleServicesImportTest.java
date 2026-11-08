// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where Helidon's {@code helidon-services-plugin} lands: a module that runs its {@code services}
 * goal over a {@code module-info.java} gets {@code [build] module-services = true} and no "not
 * imported" row; a module without a descriptor, or one that only manages the plugin, gets nothing.
 */
class PomModuleServicesImportTest {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.helidon.common</groupId>
              <artifactId>helidon-common-config</artifactId>
              <version>27.0.0</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build>
            """;
    private static final String TAIL = """
              </build>
            </project>
            """;
    private static final String PLUGIN = """
            <plugin>
              <groupId>io.helidon.build-tools</groupId>
              <artifactId>helidon-services-plugin</artifactId>
              <version>4.0.16</version>
              <executions>
                <execution>
                  <id>helidon-services</id>
                  <phase>compile</phase>
                  <goals><goal>services</goal></goals>
                </execution>
              </executions>
              <configuration><mode>%s</mode></configuration>
            </plugin>
            """;

    @Test
    void the_services_goal_over_a_descriptor_is_the_key(@TempDir Path tempDir) throws Exception {
        descriptor(tempDir);
        PomImporter.Result result =
                TestImporters.importXml(tempDir, HEAD + "<plugins>" + PLUGIN.formatted("fail") + "</plugins>" + TAIL);
        List<String> messages = TestImporters.messages(result);

        assertThat(result.jkBuild().build().moduleServices()).isTrue();
        assertThat(messages).noneMatch(m -> m.contains("helidon-services-plugin"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("\n[build]\nmodule-services = true\n");
        assertThat(JkBuildParser.parse(rendered).build().moduleServices()).isTrue();
    }

    @Test
    void another_mode_is_a_row(@TempDir Path tempDir) throws Exception {
        descriptor(tempDir);
        PomImporter.Result result =
                TestImporters.importXml(tempDir, HEAD + "<plugins>" + PLUGIN.formatted("warn") + "</plugins>" + TAIL);

        assertThat(result.jkBuild().build().moduleServices()).isTrue();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.contains("helidon-services-plugin <mode> warn: imported as fail"));
    }

    @Test
    void a_module_without_a_descriptor_gets_nothing_and_no_row(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("project/src/main/java"));
        PomImporter.Result result =
                TestImporters.importXml(tempDir, HEAD + "<plugins>" + PLUGIN.formatted("fail") + "</plugins>" + TAIL);

        assertThat(result.jkBuild().build().moduleServices()).isFalse();
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("helidon-services-plugin"));
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("module-services");
    }

    @Test
    void a_managed_plugin_the_module_does_not_run_gets_nothing(@TempDir Path tempDir) throws Exception {
        descriptor(tempDir);
        PomImporter.Result result = TestImporters.importXml(
                tempDir,
                HEAD + "<pluginManagement><plugins>" + PLUGIN.formatted("fail") + "</plugins></pluginManagement>"
                        + TAIL);

        assertThat(result.jkBuild().build().moduleServices()).isFalse();
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("module-services");
    }

    private static void descriptor(Path tempDir) throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src/main/java"));
        Files.writeString(src.resolve("module-info.java"), """
                module io.helidon.common.config {
                    provides io.helidon.common.config.spi.ConfigProvider with io.helidon.common.config.Impl;
                }
                """);
    }
}
