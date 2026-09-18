// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Project;
import org.junit.jupiter.api.Test;

/**
 * The scanner reads a build script's Java level the way the model import reads Gradle's: {@code
 * --release}, source and target compatibility are the language level ({@code java}), and only a
 * {@code java.toolchain} or {@code jvmToolchain} line is a JDK pin ({@code jdk}).
 */
class GradleImporterJavaLevelTest {

    @Test
    void source_compatibility_is_the_language_level_not_a_jdk_pin() {
        Project p = scan("""
                plugins { java }
                java { sourceCompatibility = JavaVersion.VERSION_17 }
                """);

        assertThat(p.java()).isEqualTo(17);
        assertThat(p.jdk()).isNull();
    }

    @Test
    void a_groovy_string_compatibility_and_the_1_dot_spelling_read_as_majors() {
        assertThat(scan("java { sourceCompatibility = '17' }").java()).isEqualTo(17);
        assertThat(scan("sourceCompatibility = JavaVersion.VERSION_1_8").java()).isEqualTo(8);
        assertThat(scan("java { targetCompatibility = '11' }").java()).isEqualTo(11);
    }

    @Test
    void a_toolchain_is_the_jdk_pin_and_the_level_when_the_script_names_no_other() {
        Project p = scan("""
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """);

        assertThat(p.jdk()).isEqualTo("21");
        assertThat(p.java()).isEqualTo(21);
    }

    @Test
    void release_wins_over_source_compatibility_beside_a_toolchain() {
        Project p = scan("""
                java {
                    sourceCompatibility = JavaVersion.VERSION_21
                    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
                }
                tasks.withType<JavaCompile> { options.release.set(17) }
                """);

        assertThat(p.jdk()).isEqualTo("21");
        assertThat(p.java()).isEqualTo(17);
    }

    @Test
    void a_script_naming_no_level_writes_neither_field() {
        Project p = scan("""
                plugins { java }
                group = "com.acme"
                """);

        assertThat(p.jdk()).isNull();
        assertThat(p.java()).isZero();
    }

    @Test
    void a_kotlin_jvm_toolchain_is_the_jdk_pin_and_the_java_level_stays_unset() {
        Project p = scan("""
                plugins { kotlin("jvm") version "2.4.10" }
                kotlin { jvmToolchain(21) }
                """);

        assertThat(p.jdk()).isEqualTo("21");
        assertThat(p.java()).isZero();
    }

    private static Project scan(String script) {
        return GradleImporter.importFromString(script, "app").jkBuild().project();
    }
}
