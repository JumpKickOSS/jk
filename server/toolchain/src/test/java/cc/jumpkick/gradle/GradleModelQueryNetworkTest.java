// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real thing: a three-module Gradle build with a version catalog, evaluated by a Gradle the
 * query provisions (verified download, or a healthy install on this machine) in a fork, imports
 * as a workspace whose members carry the catalog's coordinates and each other as workspace edges.
 *
 * <p>Gradle is provisioned into the tools root of the home this JVM runs under ({@link
 * JkDirs#tools()}): the module's sandbox home under {@code jk test}, which stays warm between
 * runs, so the distribution downloads once per host rather than once per run.
 */
@Tag("network")
class GradleModelQueryNetworkTest {

    @Test
    void a_three_module_build_with_a_version_catalog_imports_as_a_workspace(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("shop"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("gradle/libs.versions.toml"), """
                [versions]
                guava = "33.4.8-jre"
                junit = "6.1.3"

                [libraries]
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
                junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
                picocli = "info.picocli:picocli:4.7.7"
                """);
        Files.writeString(root.resolve("settings.gradle.kts"), """
                rootProject.name = "shop"
                include("core", "api", "app")
                """);
        Files.writeString(root.resolve("build.gradle.kts"), """
                subprojects {
                    apply(plugin = "java")
                    group = "com.acme"
                    version = "1.2.0"
                    repositories { mavenCentral() }
                    dependencies {
                        "testImplementation"(platform(rootProject.libs.junit.bom))
                        "testImplementation"(rootProject.libs.junit.jupiter)
                    }
                }
                tasks.register("release") { group = "publishing" }
                """);
        Path core = Files.createDirectories(root.resolve("core"));
        Files.writeString(core.resolve("build.gradle.kts"), """
                plugins { `java-library` }
                dependencies {
                    api(libs.guava)
                    compileOnly("org.projectlombok:lombok:1.18.42")
                }
                """);
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(api.resolve("build.gradle.kts"), """
                plugins { `java-library` }
                dependencies { api(project(":core")) }
                """);
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("build.gradle.kts"), """
                plugins { application }
                application { mainClass.set("com.acme.App") }
                dependencies {
                    implementation(project(":api"))
                    implementation(libs.picocli)
                }
                """);

        List<String> progress = new ArrayList<>();
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("settings.gradle.kts"), progress::add);

        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors())
                .as("Gradle evaluated the build: " + rows)
                .isFalse();
        assertThat(progress).anySatisfy(p -> assertThat(p).contains("Gradle " + GradleResolver.DEFAULT_VERSION));
        assertThat(result.root().project().name()).isEqualTo("shop");
        assertThat(Objects.requireNonNull(result.root().workspace()).modules()).containsExactly("api", "app", "core");
        JkBuild coreBuild = module(result, "core");
        assertThat(coreBuild.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.google.guava:guava");
        assertThat(coreBuild.dependencies().of(Scope.MAIN).getFirst().version().raw())
                .isEqualTo("33.4.8-jre");
        assertThat(coreBuild.dependencies().of(Scope.PROVIDED))
                .extracting(Dependency::module)
                .containsExactly("org.projectlombok:lombok");
        assertThat(coreBuild.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.junit:junit-bom");
        assertThat(coreBuild.dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter");
        JkBuild apiBuild = module(result, "api");
        assertThat(apiBuild.dependencies().of(Scope.MAIN))
                .extracting(Dependency::library)
                .containsExactly("core");
        assertThat(apiBuild.dependencies().of(Scope.MAIN).getFirst().isWorkspace())
                .isTrue();
        JkBuild appBuild = module(result, "app");
        assertThat(appBuild.dependencies().of(Scope.MAIN))
                .extracting(Dependency::library)
                .containsExactly("api", "picocli");
        assertThat(Objects.requireNonNull(appBuild.application()).main()).isEqualTo("com.acme.App");
        assertThat(rows).anySatisfy(r -> assertThat(r).contains("task `release`"));
    }

    /**
     * A root that imports a BOM for itself while its subprojects apply dependency-management on
     * their own: a subproject's platforms are the BOMs the blocks reaching it name — its own
     * script's and the root's {@code subprojects { }} block's — and not the BOM the root's
     * top-level block imports for the root alone.
     */
    @Test
    void a_roots_own_bom_import_is_not_attributed_to_the_subprojects(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("shop"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle"), """
                rootProject.name = 'shop'
                include 'core', 'api'
                """);
        Files.writeString(root.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'io.spring.dependency-management' version '1.1.7'
                }
                repositories { mavenCentral() }
                dependencyManagement {
                    imports { mavenBom 'org.springframework.boot:spring-boot-dependencies:3.5.11' }
                }
                subprojects {
                    apply plugin: 'java'
                    apply plugin: 'io.spring.dependency-management'
                    group = 'com.acme'
                    version = '1.2.0'
                    repositories { mavenCentral() }
                    dependencyManagement {
                        imports { mavenBom 'org.junit:junit-bom:6.1.3' }
                    }
                }
                """);
        Path core = Files.createDirectories(root.resolve("core"));
        Files.writeString(core.resolve("build.gradle"), """
                dependencyManagement {
                    imports { mavenBom 'com.fasterxml.jackson:jackson-bom:2.19.2' }
                }
                dependencies {
                    implementation 'com.fasterxml.jackson.core:jackson-databind'
                    testImplementation 'org.junit.jupiter:junit-jupiter'
                }
                """);
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(api.resolve("build.gradle"), """
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter'
                }
                """);
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("settings.gradle"), progress -> {});

        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors())
                .as("Gradle evaluated the build: " + rows)
                .isFalse();
        assertThat(module(result, "core").dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("com.fasterxml.jackson:jackson-bom", "org.junit:junit-bom");
        assertThat(module(result, "api").dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.junit:junit-bom");
    }

    private static JkBuild module(GradleBuildImport.Result result, String path) {
        return Objects.requireNonNull(result.modules().get(path), path);
    }
}
