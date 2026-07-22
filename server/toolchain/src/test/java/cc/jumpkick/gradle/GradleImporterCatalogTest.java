// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleImporterCatalogTest {

    @Test
    void catalog_accessors_become_jk_dependencies() {
        GradleVersionCatalog cat = GradleVersionCatalog.parseToml("""
                [versions]
                junit = "5.10.2"
                guava = "33.0.0-jre"

                [libraries]
                junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
                guava = { group = "com.google.guava", name = "guava", version.ref = "guava" }
                bom-web = { module = "org.springframework.boot:spring-boot-starter-web" }

                [bundles]
                testing = ["junit-jupiter"]
                """);

        String script = """
                plugins {
                    java
                }
                dependencies {
                    implementation(libs.guava)
                    testImplementation(libs.bundles.testing)
                    implementation(libs.bom.web)
                }
                """;

        GradleImporter.Result result = GradleImporter.importFromString(script, "app", cat);
        JkBuild build = result.jkBuild();

        List<Dependency> main = build.dependencies().of(Scope.MAIN);
        assertThat(main.stream().map(Dependency::module).toList())
                .contains("com.google.guava:guava", "org.springframework.boot:spring-boot-starter-web");
        // Version-less catalog lib → platform-managed root (no version pin).
        assertThat(main.stream()
                        .filter(d -> d.module().equals("org.springframework.boot:spring-boot-starter-web"))
                        .findFirst()
                        .orElseThrow()
                        .isPlatformManaged())
                .isTrue();

        List<Dependency> test = build.dependencies().of(Scope.TEST);
        assertThat(test.stream().map(Dependency::module).toList()).contains("org.junit.jupiter:junit-jupiter");

        Dependency guava = main.stream()
                .filter(d -> d.module().equals("com.google.guava:guava"))
                .findFirst()
                .orElseThrow();
        assertThat(guava.version().raw()).contains("33.0.0-jre");
    }

    @Test
    void unresolved_catalog_ref_is_reported_not_silent() {
        GradleVersionCatalog cat = GradleVersionCatalog.parseToml("""
                [libraries]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                """);

        String script = """
                dependencies {
                    implementation(libs.guava)
                    implementation(libs.does.not.exist)
                }
                """;

        GradleImporter.Result result = GradleImporter.importFromString(script, "app", cat);
        assertThat(result.report().issues().stream()
                        .map(i -> i.message())
                        .toList()
                        .toString())
                .contains("not found");
        assertThat(result.report().hasErrors()).isTrue();
        // guava still imported
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN).stream()
                        .map(Dependency::module)
                        .toList())
                .contains("com.google.guava:guava");
    }

    @Test
    void import_from_disk_reads_libs_versions_toml(@TempDir Path tmp) throws Exception {
        Path gradle = tmp.resolve("gradle");
        Files.createDirectories(gradle);
        Files.writeString(gradle.resolve("libs.versions.toml"), """
                [versions]
                leaf = "1.0"

                [libraries]
                leaf = { module = "com.foo:leaf", version.ref = "leaf" }
                """);
        Path script = tmp.resolve("build.gradle.kts");
        Files.writeString(script, """
                plugins { java }
                dependencies {
                    implementation(libs.leaf)
                }
                """);

        GradleImporter.Result result = GradleImporter.importFrom(script);
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN).stream()
                        .map(Dependency::module)
                        .toList())
                .containsExactly("com.foo:leaf");
    }

    @Test
    void missing_version_ref_surfaces_in_report() {
        GradleVersionCatalog cat = GradleVersionCatalog.parseToml("""
                [libraries]
                orphan = { module = "com.example:orphan", version.ref = "nope" }
                """);
        String script = """
                dependencies {
                    implementation(libs.orphan)
                }
                """;
        GradleImporter.Result result = GradleImporter.importFromString(script, "app", cat);
        assertThat(result.report().issues().stream()
                        .map(i -> i.message())
                        .toList()
                        .toString())
                .contains("version.ref");
        // Still imported as platform-managed version-less GA.
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN))
                .anyMatch(d -> d.module().equals("com.example:orphan") && d.isPlatformManaged());
    }
}
