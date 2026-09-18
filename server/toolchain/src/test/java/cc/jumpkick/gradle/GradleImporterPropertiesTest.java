// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A version spelled through a Gradle property — {@code $junitVersion}, {@code ${mapstructVersion}}
 * — is written as the value the property has, from {@code gradle.properties}, {@code ext} or a
 * script-level {@code val}; a property nothing defines is a row naming it, and a {@code $} never
 * reaches the manifest.
 */
class GradleImporterPropertiesTest {

    /** junit-pioneer: {@code gradle.properties} holds {@code junitVersion}; the BOM is {@code platform("…:$junitVersion")}. */
    @Test
    void properties_from_gradle_properties_interpolate_in_both_spellings(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("gradle.properties"), """
                junitVersion=6.1.0
                jimfsVersion = 1.3.0
                """);
        Files.writeString(tmp.resolve("build.gradle.kts"), """
                plugins { java }
                dependencies {
                    implementation(platform("org.junit:junit-bom:$junitVersion"))
                    testImplementation("com.google.jimfs:jimfs:${jimfsVersion}")
                }
                """);

        GradleImporter.Result result = GradleImporter.importFrom(tmp.resolve("build.gradle.kts"));
        JkBuild build = result.jkBuild();

        assertThat(only(build, Scope.PLATFORM).version().raw()).isEqualTo("6.1.0");
        assertThat(only(build, Scope.TEST).version().raw()).isEqualTo("1.3.0");
        assertThat(messages(result.report())).noneMatch(m -> m.contains("verbatim"));
    }

    /** mapstruct-on-gradle ({@code ext {}}), spring-petclinic-kotlin ({@code val}), Groovy {@code def} and Kotlin {@code extra}. */
    @Test
    void ext_blocks_and_script_level_declarations_define_properties() {
        JkBuild build = GradleImporter.importFromString("""
                        ext {
                            mapstructVersion = "1.7.0.Beta1"
                        }
                        val boostrapVersion = "5.3.8"
                        def slf4jVersion = '2.0.17'
                        extra["guavaVersion"] = "33.4.8-jre"
                        val jacksonVersion by extra("2.19.0")
                        ext.testngVersion = '7.11.0'

                        dependencies {
                            implementation "org.mapstruct:mapstruct:${mapstructVersion}"
                            implementation("org.webjars.npm:bootstrap:$boostrapVersion")
                            implementation "org.slf4j:slf4j-api:$slf4jVersion"
                            implementation("com.google.guava:guava:${guavaVersion}")
                            implementation("tools.jackson.core:jackson-databind:$jacksonVersion")
                            testImplementation "org.testng:testng:${testngVersion}"
                        }
                        """, "app").jkBuild();

        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(d -> d.module() + ":" + d.version().raw())
                .containsExactly(
                        "org.mapstruct:mapstruct:1.7.0.Beta1",
                        "org.webjars.npm:bootstrap:5.3.8",
                        "org.slf4j:slf4j-api:2.0.17",
                        "com.google.guava:guava:33.4.8-jre",
                        "tools.jackson.core:jackson-databind:2.19.0");
        assertThat(only(build, Scope.TEST).version().raw()).isEqualTo("7.11.0");
    }

    @Test
    void an_undefined_property_is_a_row_naming_it_and_never_a_literal_dollar() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                dependencies {
                    runtimeOnly("org.webjars:webjars-locator-lite:${webjarsLocatorLiteVersion}")
                    implementation("org.slf4j:slf4j-api:$slf4jVersion")
                }
                """, "app");
        JkBuild build = result.jkBuild();

        Dependency locator = only(build, Scope.RUNTIME);
        assertThat(locator.module()).isEqualTo("org.webjars:webjars-locator-lite");
        assertThat(locator.isPlatformManaged()).as("written without a version").isTrue();
        assertThat(only(build, Scope.MAIN).isPlatformManaged()).isTrue();
        for (Scope scope : Scope.values()) {
            for (Dependency d : build.dependencies().of(scope)) {
                assertThat(d.version().raw()).doesNotContain("$");
            }
        }
        assertThat(messages(result.report()))
                .anySatisfy(
                        m -> assertThat(m).contains("webjarsLocatorLiteVersion").contains("webjars-locator-lite"))
                .anySatisfy(m -> assertThat(m).contains("slf4jVersion"))
                .noneMatch(m -> m.contains("verbatim"));
    }

    /** kotlin4example: refreshVersions writes {@code _} in the script and the pin in versions.properties. */
    @Test
    void the_refresh_versions_placeholder_reads_its_pin_from_versions_properties(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("versions.properties"), """
                version.kotlinx.coroutines=1.10.2
                version.org.slf4j..slf4j-api=2.0.17
                version.junit.jupiter=5.13.4
                """);
        Files.writeString(tmp.resolve("build.gradle.kts"), """
                dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:_")
                    implementation("org.slf4j:slf4j-api:_")
                    testImplementation("org.junit.jupiter:junit-jupiter-api:_")
                    testImplementation("io.kotest:kotest-assertions-core:_")
                }
                """);

        GradleImporter.Result result = GradleImporter.importFrom(tmp.resolve("build.gradle.kts"));
        JkBuild build = result.jkBuild();

        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(d -> d.module() + ":" + d.version().raw())
                .containsExactly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2", "org.slf4j:slf4j-api:2.0.17");
        List<Dependency> test = build.dependencies().of(Scope.TEST);
        assertThat(test.getFirst().version().raw()).isEqualTo("5.13.4");
        assertThat(test.get(1).isPlatformManaged()).as("no key fits kotest").isTrue();
        assertThat(messages(result.report()))
                .hasSize(1)
                .first()
                .asString()
                .contains("io.kotest:kotest-assertions-core")
                .contains("no entry for it");
    }

    /** No versions.properties beside the script: the placeholder is a row and the entry is written version-less. */
    @Test
    void the_refresh_versions_placeholder_without_a_file_is_a_row_not_a_version() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                dependencies {
                    implementation("io.github.microutils:kotlin-logging:_")
                }
                """, "app");

        Dependency logging = only(result.jkBuild(), Scope.MAIN);
        assertThat(logging.isPlatformManaged()).isTrue();
        assertThat(messages(result.report()))
                .anySatisfy(m -> assertThat(m).contains("`_`").contains("io.github.microutils:kotlin-logging"));
    }

    /** spring-petclinic-kotlin: {@code kotlin("jvm") version kotlinVersion} with the val declared in the plugins block. */
    @Test
    void plugin_versions_spelled_through_a_property_resolve(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("gradle.properties"), "bootVersion=4.1.1\n");
        Files.writeString(tmp.resolve("build.gradle.kts"), """
                plugins {
                    val kotlinVersion = "2.4.10"
                    id("org.springframework.boot") version "$bootVersion"
                    kotlin("jvm") version kotlinVersion
                    kotlin("plugin.spring") version kotlinVersion
                }
                """);

        JkBuild build =
                GradleImporter.importFrom(tmp.resolve("build.gradle.kts")).jkBuild();

        assertThat(Objects.requireNonNull(build.project().kotlin()).raw()).isEqualTo("2.4.10");
        assertThat(build.pluginConfig("spring-boot").orElseThrow().string("version"))
                .isEqualTo("4.1.1");
    }

    @Test
    void a_catalog_version_accessor_inside_a_coordinate_interpolates() {
        GradleVersionCatalog catalog = GradleVersionCatalog.parseToml("""
                [versions]
                okio = "3.10.2"
                """);

        JkBuild build = GradleImporter.importFromString("""
                        dependencies {
                            implementation("com.squareup.okio:okio:${libs.versions.okio.get()}")
                        }
                        """, "app", catalog).jkBuild();

        assertThat(only(build, Scope.MAIN).version().raw()).isEqualTo("3.10.2");
    }

    private static Dependency only(JkBuild build, Scope scope) {
        List<Dependency> deps = build.dependencies().of(scope);
        assertThat(deps).hasSize(1);
        return deps.getFirst();
    }

    private static List<String> messages(ImportReport report) {
        return report.issues().stream().map(ImportReport.Issue::message).toList();
    }
}
