// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Scope;
import cc.jumpkick.mvn.PomImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build with a settings file is read through Gradle's model; when Gradle cannot evaluate it the
 * scanner reads the root script and the report says so; a lone build script is scanned.
 */
class GradleBuildImportTest {

    private static final String MODEL = """
            {"gradle":"9.5.1","rootName":"shop","settingsRepositories":[],"projects":[
              {"path":":","projectName":"shop","dir":"","group":"com.acme","version":"1.0","plugins":[],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]},
              {"path":":core","projectName":"core","dir":"core","group":"com.acme","version":"1.0","plugins":["java"],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],
               "configurations":[{"name":"implementation","dependencies":[{"kind":"module","group":"com.google.guava","artifact":"guava","version":"33.4.8-jre","excludes":[]}],"constraints":[]}],"tasks":[]}]}
            """;

    @Test
    void a_build_with_a_settings_file_is_read_through_the_model(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"shop\"\ninclude(\"core\")\n");
        Files.writeString(root.resolve("build.gradle.kts"), "subprojects { apply(plugin = \"java\") }\n");
        List<String> asked = new ArrayList<>();
        GradleBuildImport gradle = GradleBuildImport.withModel((dir, ids, progress) -> {
            asked.add(dir.toString());
            asked.addAll(ids);
            return MODEL;
        });

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("build.gradle.kts"), note -> {});

        assertThat(asked).contains(root.toAbsolutePath().toString(), "java", "org.jetbrains.kotlin.jvm");
        assertThat(result.modules()).containsOnlyKeys("core");
        assertThat(result.root().project().name()).isEqualTo("shop");
        assertThat(result.report().hasErrors()).isFalse();
    }

    @Test
    void the_settings_file_itself_is_a_source(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'shop'\ninclude 'core'\n");
        GradleBuildImport gradle = GradleBuildImport.withModel((dir, ids, progress) -> MODEL);

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("settings.gradle"), note -> {});

        assertThat(result.modules()).containsOnlyKeys("core");
    }

    @Test
    void when_gradle_cannot_evaluate_the_build_the_scanner_reads_the_root_script_and_a_row_says_so(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"shop\"\ninclude(\"core\")\n");
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins { java }
                group = "com.acme"
                dependencies { implementation("com.google.guava:guava:33.4.8-jre") }
                subprojects { apply(plugin = "java") }
                """);
        GradleBuildImport gradle = GradleBuildImport.withModel((dir, ids, progress) -> {
            throw new IOException(
                    "Gradle exited 1 evaluating " + dir + ": Plugin [id: 'com.acme.house'] was not found");
        });

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("build.gradle.kts"), note -> {});

        assertThat(result.modules()).isEmpty();
        assertThat(result.root().project().name()).isEqualTo("shop");
        assertThat(result.root().dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m)
                .contains("Gradle could not evaluate the build")
                .contains("com.acme.house")
                .contains("scanning build.gradle.kts alone"));
        assertThat(result.report().hasErrors()).isTrue();
    }

    @Test
    void a_model_read_over_the_resolve_budget_fails_the_import(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'shop'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
        GradleBuildImport gradle = GradleBuildImport.withModel((dir, ids, progress) -> {
            throw new IOException(PomImporter.BUDGET_EXCEEDED + "no Gradle output for 120 s while evaluating " + dir);
        });

        assertThatThrownBy(() -> gradle.importBuild(root.resolve("build.gradle"), note -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(PomImporter.BUDGET_EXCEEDED);
    }

    @Test
    void a_settings_only_root_that_gradle_cannot_read_is_refused_with_the_reason(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'gs-multi-module'\ninclude 'library'\n");
        GradleBuildImport gradle = GradleBuildImport.withModel((dir, ids, progress) -> {
            throw new IOException("Gradle 9.3.1 runs on JDK 17 to 25 and none is installed");
        });

        assertThatThrownBy(() -> gradle.importBuild(root.resolve("settings.gradle"), note -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("settings.gradle names a build with no build script beside it")
                .hasMessageContaining("none is installed");
    }

    @Test
    void one_project_of_a_larger_build_is_scanned_with_a_row_pointing_at_the_root(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"shop\"\ninclude(\"core\")\n");
        Path core = Files.createDirectories(root.resolve("core"));
        Files.writeString(core.resolve("build.gradle.kts"), "plugins { java }\n");
        List<String> reads = new ArrayList<>();
        GradleBuildImport gradle = GradleBuildImport.withModel((dir, ids, progress) -> {
            reads.add(dir.toString());
            return MODEL;
        });

        GradleBuildImport.Result result = gradle.importBuild(core.resolve("build.gradle.kts"), note -> {});

        assertThat(reads).as("a subproject alone is not evaluated by Gradle").isEmpty();
        assertThat(result.root().project().name()).isEqualTo("core");
        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m)
                .contains("one project of the build rooted at")
                .contains("settings.gradle.kts"));
    }

    @Test
    void a_lone_build_script_is_scanned(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\ngroup = \"com.acme\"\n");
        GradleBuildImport gradle = GradleBuildImport.withModel((dir, ids, progress) -> {
            throw new AssertionError("no settings file, so Gradle is not run");
        });

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("build.gradle.kts"), note -> {});

        assertThat(result.root().project().group()).isEqualTo("com.acme");
        assertThat(result.modules()).isEmpty();
    }

    private static List<String> messages(ImportReport report) {
        return report.issues().stream().map(ImportReport.Issue::message).toList();
    }
}
