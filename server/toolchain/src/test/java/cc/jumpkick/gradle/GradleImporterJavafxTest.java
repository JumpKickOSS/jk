// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code org.openjfx.javafxplugin} block imports as {@code [javafx]} plus the OpenJFX
 * dependency the plugin would have added for each module, in either script dialect.
 */
class GradleImporterJavafxTest {

    @Test
    void a_kotlin_block_writes_the_table_and_one_dependency_per_module() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                plugins {
                    application
                    id("org.openjfx.javafxplugin") version "0.1.0"
                }
                javafx {
                    version = "27"
                    modules("javafx.controls", "javafx.web")
                }
                """, "app");

        assertThat(result.jkBuild().pluginConfig("javafx").orElseThrow().values())
                .isEqualTo(Map.of("modules", List.of("javafx.controls", "javafx.web")));
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN))
                .extracting(Dependency::module, d -> d.version().raw())
                .contains(tuple("org.openjfx:javafx-controls", "27"), tuple("org.openjfx:javafx-web", "27"));
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneMatch(m -> m.contains("org.openjfx.javafxplugin") && m.contains("not yet mapped"));
    }

    @Test
    void a_groovy_block_with_the_default_modules_writes_an_empty_table() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                plugins {
                    id 'application'
                    id 'org.openjfx.javafxplugin' version '0.1.0'
                }
                javafx {
                    version = '27'
                    modules = [ 'javafx.controls', 'javafx.fxml' ]
                }
                """, "app");

        assertThat(result.jkBuild().pluginConfig("javafx").orElseThrow().values())
                .isEmpty();
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .contains("org.openjfx:javafx-controls", "org.openjfx:javafx-fxml");
    }

    @Test
    void no_version_writes_no_dependencies_and_says_so() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                plugins {
                    id("org.openjfx.javafxplugin") version "0.1.0"
                }
                javafx {
                    modules("javafx.controls")
                }
                """, "app");

        assertThat(result.jkBuild().dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .noneMatch(m -> m.startsWith("org.openjfx:"));
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anyMatch(m -> m.contains("no `javafx { version }`"));
    }
}
