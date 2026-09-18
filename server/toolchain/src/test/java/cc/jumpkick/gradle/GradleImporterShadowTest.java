// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Shadow is the fat jar — the application's when the script names a main, the library's when it
 * does not — and its {@code relocate} calls are the assembly's rules where jk's whole-package
 * rules agree with them.
 */
class GradleImporterShadowTest {

    @Test
    void shadow_relocations_in_the_kotlin_dsl_are_the_applications_rules() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                plugins {
                    application
                    id("com.gradleup.shadow") version "9.0.0"
                }
                application { mainClass.set("com.acme.App") }
                tasks.shadowJar {
                    relocate("com.google.common", "shadow.com.google.common")
                    relocate("io.grpc", "shadow.io.grpc") {
                        exclude("io.grpc.netty.shaded.io.grpc.netty.*")
                    }
                    relocate("io.grpc.netty.shaded.io.grpc.netty", "shadow.io.grpc.netty.shaded.io.grpc.netty") {
                        include("io.grpc.netty.shaded.io.grpc.netty.*")
                    }
                }
                """, "tool");

        JkBuild.Application app = result.jkBuild().applicationOpt().orElseThrow();
        assertThat(app.main()).isEqualTo("com.acme.App");
        assertThat(app.assembly()).isTrue();
        assertThat(app.relocate())
                .containsExactly(
                        Map.entry("com.google.common", "shadow.com.google.common"),
                        Map.entry("io.grpc", "shadow.io.grpc"));
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneMatch(m -> m.contains("relocate"))
                .noneMatch(m -> m.contains("not yet mapped"));
    }

    @Test
    void shadow_on_a_script_with_no_main_is_the_librarys_fat_jar_and_an_uncovered_exclude_is_a_row() {
        GradleImporter.Result result = GradleImporter.importFromString("""
                plugins {
                    id 'java-library'
                    id 'com.github.johnrengelman.shadow' version '8.1.1'
                }
                shadowJar {
                    relocate 'org.apache.lucene', 'org.demo.shaded.lucene9'
                    relocate('org.apache.commons.io', 'org.demo.shaded.commons.io') {
                        exclude 'org.apache.commons.io.input.*'
                    }
                }
                """, "lucene9-shaded");

        JkBuild build = result.jkBuild();
        assertThat(build.applicationOpt()).isEmpty();
        assertThat(build.libraryOpt()).isPresent();
        assertThat(build.assembly()).isTrue();
        assertThat(build.relocate()).containsExactly(Map.entry("org.apache.lucene", "org.demo.shaded.lucene9"));
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anyMatch(m -> m.startsWith("Shadow `relocate` org.apache.commons.io → org.demo.shaded.commons.io"
                        + " (excludes org.apache.commons.io.input.*) —"));
    }
}
