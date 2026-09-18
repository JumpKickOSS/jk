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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scanner names the project from {@code settings.gradle} and keeps every Gradle configuration's
 * dependencies in the jk table that means the same thing, whatever shape the declaration takes.
 */
class GradleImporterConfigurationsTest {

    @Test
    void root_project_name_names_the_project_not_the_directory(@TempDir Path tmp) throws Exception {
        Path checkout = Files.createDirectories(tmp.resolve("checkout-2024"));
        Files.writeString(checkout.resolve("settings.gradle.kts"), """
                rootProject.name = "petclinic"
                """);
        Files.writeString(checkout.resolve("build.gradle.kts"), """
                plugins { java }
                group = "org.example"
                """);

        JkBuild build =
                GradleImporter.importFrom(checkout.resolve("build.gradle.kts")).jkBuild();

        assertThat(build.project().name()).isEqualTo("petclinic");
    }

    @Test
    void without_a_settings_file_the_directory_names_the_project(@TempDir Path tmp) throws Exception {
        Path checkout = Files.createDirectories(tmp.resolve("widget"));
        Files.writeString(checkout.resolve("build.gradle"), "plugins { id 'java' }\n");

        JkBuild build =
                GradleImporter.importFrom(checkout.resolve("build.gradle")).jkBuild();

        assertThat(build.project().name()).isEqualTo("widget");
    }

    @Test
    void compile_only_is_provided_and_runtime_only_is_runtime() {
        JkBuild build = GradleImporter.importFromString("""
                        dependencies {
                            compileOnly("org.projectlombok:lombok:1.18.42")
                            compileOnlyApi 'jakarta.servlet:jakarta.servlet-api:6.1.0'
                            runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
                            implementation("com.google.guava:guava:33.4.8-jre")
                        }
                        """, "app").jkBuild();

        assertThat(build.dependencies().of(Scope.PROVIDED))
                .extracting(Dependency::module)
                .containsExactly("org.projectlombok:lombok", "jakarta.servlet:jakarta.servlet-api");
        assertThat(build.dependencies().of(Scope.RUNTIME))
                .extracting(Dependency::module)
                .containsExactly("ch.qos.logback:logback-classic");
        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.google.guava:guava");
    }

    @Test
    void test_compile_only_lands_in_the_test_table_with_a_row_saying_so() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                dependencies {
                    testCompileOnly("org.apiguardian:apiguardian-api:1.1.2")
                }
                """, "app");

        assertThat(result.jkBuild().dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.apiguardian:apiguardian-api");
        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m)
                .contains("testCompileOnly")
                .contains("org.apiguardian:apiguardian-api")
                .contains("[test-dependencies]"));
    }

    /** junit-jupiter-extensions: every dependency carries a {@code because} closure. */
    @Test
    void a_dependency_with_a_closure_body_is_kept_and_its_excludes_carried() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                dependencies {
                    api(platform("org.junit:junit-bom:6.1.3"))
                    api("org.junit.jupiter:junit-jupiter-api") {
                        because 'building extensions in "main" using JUnit Jupiter API'
                    }
                    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine") {
                        because 'at least one engine is needed at test runtime'
                    }
                    implementation("org.springframework:spring-context:6.2.7") {
                        exclude group: 'commons-logging', module: 'commons-logging'
                        exclude(group = "org.springframework", module = "spring-jcl")
                    }
                }
                """, "extensions");
        JkBuild build = result.jkBuild();

        assertThat(build.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.junit:junit-bom");
        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter-api", "org.springframework:spring-context");
        assertThat(build.dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter-engine");
        Dependency spring = build.dependencies().of(Scope.MAIN).get(1);
        assertThat(spring.exclusions())
                .containsExactly("commons-logging:commons-logging", "org.springframework:spring-jcl");
        assertThat(result.report().hasErrors())
                .as(messages(result.report()).toString())
                .isFalse();
    }

    /** mapstruct-on-gradle: one Groovy line declares two test dependencies. */
    @Test
    void comma_separated_coordinates_each_import() {
        JkBuild build = GradleImporter.importFromString("""
                        dependencies {
                            testImplementation "org.testng:testng:6.10", "org.easytesting:fest-assert:1.4"
                            implementation("com.google.guava:guava:33.4.8-jre", "org.slf4j:slf4j-api:2.0.17")
                        }
                        """, "app").jkBuild();

        assertThat(build.dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.testng:testng", "org.easytesting:fest-assert");
        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.google.guava:guava", "org.slf4j:slf4j-api");
    }

    private static List<String> messages(ImportReport report) {
        return report.issues().stream().map(ImportReport.Issue::message).toList();
    }
}
