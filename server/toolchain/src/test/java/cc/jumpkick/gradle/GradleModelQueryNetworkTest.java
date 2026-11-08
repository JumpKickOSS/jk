// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportProgress;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        GradleBuildImport.Result result =
                gradle.importBuild(root.resolve("settings.gradle.kts"), GradleModelQueryTest.milestones(progress));

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
        assertThat(rows).anySatisfy(r -> assertThat(r).contains("task release ("));
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

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("settings.gradle"), ImportProgress.NONE);

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

    /**
     * A subproject whose declared jakarta.xml.bind-api is below the one jakarta.xml.ws-api asks
     * for, compiled by the groovy plugin over Java sources with a release set on every JavaCompile:
     * the pin is the version Gradle resolves, the Groovy the one its classpath resolves, and java
     * the release.
     */
    @Test
    void resolved_versions_raise_a_low_pin_and_name_the_groovy_a_groovy_module_compiles_with(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle.kts"), """
                rootProject.name = "ws"
                include("app")
                """);
        Files.writeString(root.resolve("build.gradle.kts"), "");
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(
                app.resolve("build.gradle.kts"), """
                plugins { groovy }
                repositories { mavenCentral() }
                dependencies {
                    implementation("jakarta.xml.ws:jakarta.xml.ws-api:4.0.3")
                    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.4")
                    testImplementation(platform("org.apache.groovy:groovy-bom:5.0.2"))
                    testImplementation("org.apache.groovy:groovy")
                }
                java { toolchain { languageVersion = JavaLanguageVersion.of(%d) } }
                tasks.withType<JavaCompile>().configureEach { options.release = 17 }
                """.formatted(Runtime.version().feature()));
        Path source = app.resolve("src/test/java/ws/WsTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package ws; class WsTest {}");
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("settings.gradle.kts"), ImportProgress.NONE);

        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors())
                .as("Gradle evaluated the build: " + rows)
                .isFalse();
        JkBuild appBuild = module(result, "app");
        Dependency bind = appBuild.dependencies().of(Scope.MAIN).stream()
                .filter(d -> d.module().equals("jakarta.xml.bind:jakarta.xml.bind-api"))
                .findFirst()
                .orElseThrow();
        assertThat(bind.version().raw()).as(rows.toString()).isEqualTo("4.0.5");
        assertThat(Objects.requireNonNull(appBuild.project().groovy()).raw()).isEqualTo("5.0.2");
        assertThat(result.root().project().java()).isEqualTo(17);
        assertThat(result.root().project().jdkMajor())
                .as("a toolchain the host JDK compiles to, with Gradle's default vendor, is no jdk pin")
                .isZero();
        assertThat(appBuild.project().java()).as("inherited from the root").isZero();
        assertThat(appBuild.build().javac().args())
                .as("--release is jk's to pass")
                .isEmpty();
    }

    /**
     * A named repository's content filter, its release policy and the variable its header
     * credential reads come out of the fork as Gradle evaluates them; the value itself never does.
     */
    @Test
    void a_filtered_repository_with_an_environment_credential_imports_its_name_groups_and_variable(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle.kts"), """
                rootProject.name = "ws"
                include("lib")
                """);
        Files.writeString(root.resolve("build.gradle.kts"), "");
        Path lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(lib.resolve("build.gradle.kts"), """
                plugins { `java-library` }
                repositories {
                    mavenCentral()
                    maven {
                        url = uri("https://packages.corp.invalid/api/v4/projects/7/packages/maven")
                        name = "CorpPackages"
                        credentials(HttpHeaderCredentials::class) {
                            name = "Private-Token"
                            value = System.getenv("JK_TEST_CORP_PULL_TOKEN")
                        }
                        authentication { create<HttpHeaderAuthentication>("header") }
                        content { includeGroup("com.corp") }
                        mavenContent { releasesOnly() }
                    }
                }
                """);
        Path source = lib.resolve("src/main/java/corp/Lib.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package corp; public class Lib {}");
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));
        Map<String, String> shell = new HashMap<>(System.getenv());
        shell.put("JK_TEST_CORP_PULL_TOKEN", "glpat-not-a-real-token-0123456789");

        GradleBuildImport.Result result = RequestShell.where(
                shell, () -> gradle.importBuild(root.resolve("settings.gradle.kts"), ImportProgress.NONE));

        assertThat(result.root().repositories())
                .as("the build asks Central first, so it stays first")
                .hasSize(2)
                .last()
                .satisfies(r -> {
                    assertThat(r.name()).isEqualTo("corppackages");
                    assertThat(r.groups()).containsExactly("com.corp");
                    assertThat(r.snapshots()).isFalse();
                });
        assertThat(result.machineCredentials()).singleElement().satisfies(t -> assertThat(t.credential())
                .containsExactly(Map.entry("token", "${env:JK_TEST_CORP_PULL_TOKEN}")));
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneSatisfy(m -> assertThat(m).contains("glpat-not-a-real-token"));
    }

    /**
     * The Avro plugin reading schemas another task downloads under build/, before any build ran:
     * the directory still counts though it is empty, and the row names the task that fills it.
     */
    @Test
    void avro_schemas_a_task_downloads_name_the_task(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("flags"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"flags\"\n");
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
                }
                group = "com.acme"
                version = "1.0"
                repositories { mavenCentral() }
                dependencies { implementation("org.apache.avro:avro:1.12.0") }
                val shared = layout.buildDirectory.dir("generated/avro/shared")
                val downloadSchemas by tasks.registering { outputs.dir(shared) }
                tasks.named<com.github.davidmc24.gradle.plugin.avro.GenerateAvroJavaTask>("generateAvroJava") {
                    dependsOn(downloadSchemas)
                    setSource(shared)
                }
                """);
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("settings.gradle.kts"), ImportProgress.NONE);

        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors())
                .as("Gradle evaluated the build: " + rows)
                .isFalse();
        assertThat(rows).contains("Avro schemas come from task downloadSchemas; see [schema-registry]");
    }

    /**
     * A private plugin, here a precompiled script plugin in buildSrc, taught to the import by the
     * build's .jk/import.toml: its task's URL and the subjects file become [schema-registry], and
     * the key and secret its task reads from the environment become variable references.
     */
    @Test
    void a_build_s_import_rule_maps_a_private_plugin_s_task_and_credential(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("flags"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'flags'\n");
        Files.createDirectories(root.resolve("buildSrc/src/main/groovy"));
        Files.writeString(root.resolve("buildSrc/build.gradle"), "plugins { id 'groovy-gradle-plugin' }\n");
        Files.writeString(root.resolve("buildSrc/src/main/groovy/com.acme.kafka-helper.gradle"), """
                abstract class Loader extends DefaultTask {
                    @Input abstract Property<String> getRegistry()
                    @Input @Optional abstract Property<String> getApiKey()
                    @Input @Optional abstract Property<String> getApiSecret()
                    @TaskAction void load() { }
                }
                tasks.register('sharedSchemasLoader', Loader)
                """);
        Files.writeString(root.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.acme.kafka-helper'
                }
                group = 'com.acme'
                version = '1.0'
                tasks.named('sharedSchemasLoader') {
                    registry = 'https://registry.example.com'
                    apiKey = System.getenv('ACME_REGISTRY_KEY')
                    apiSecret = System.getenv('ACME_REGISTRY_SECRET')
                }
                """);
        Files.createDirectories(root.resolve("src/main/resources/kafka"));
        Files.writeString(root.resolve("src/main/resources/kafka/shared-schema.yaml"), "schemas:\n  flags-value: 18\n");
        Files.createDirectories(root.resolve(".jk"));
        Files.writeString(root.resolve(".jk/import.toml"), """
                [[import.gradle-plugin]]
                id          = "com.acme.kafka-helper"
                table       = "schema-registry"
                task        = "sharedSchemasLoader"
                properties  = { registry = "url" }
                files       = { "src/main/resources/kafka/shared-schema.yaml#schemas" = "subjects" }
                credentials = { username = "apiKey", password = "apiSecret" }
                """);
        Map<String, String> shell = new HashMap<>(System.getenv());
        shell.put("ACME_REGISTRY_KEY", "key-0123456789");
        shell.put("ACME_REGISTRY_SECRET", "secret-0123456789");
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));

        GradleBuildImport.Result result = RequestShell.where(
                shell, () -> gradle.importBuild(root.resolve("settings.gradle"), ImportProgress.NONE));

        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors())
                .as("Gradle evaluated the build: " + rows)
                .isFalse();
        assertThat(result.root().pluginConfig("schema-registry").orElseThrow().values())
                .containsOnly(
                        Map.entry("url", "https://registry.example.com"),
                        Map.entry("subjects", Map.of("flags-value", "18")),
                        Map.entry("repository", "kafka-helper"));
        assertThat(result.machineCredentials()).singleElement().satisfies(c -> assertThat(c.credential())
                .containsExactly(
                        Map.entry("username", "${env:ACME_REGISTRY_KEY}"),
                        Map.entry("password", "${env:ACME_REGISTRY_SECRET}")));
        assertThat(String.join("\n", rows)).doesNotContain("0123456789").doesNotContain("kafka-helper: not imported");
    }

    /** The Confluent schema-registry Gradle plugin's download block, read off the evaluated extension. */
    @Test
    void the_registry_plugin_s_download_block_imports_as_schema_registry(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("orders"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        maven("https://packages.confluent.io/maven/")
                    }
                }
                rootProject.name = "orders"
                """);
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("com.github.imflog.kafka-schema-registry-gradle-plugin") version "2.7.0"
                }
                group = "com.acme"
                version = "1.0"
                schemaRegistry {
                    url.set("https://registry.example.com")
                    clientConfig.set(mapOf(
                        "basic.auth.credentials.source" to "USER_INFO",
                        "basic.auth.user.info" to "${System.getenv("ACME_SR_KEY")}:${System.getenv("ACME_SR_SECRET")}"))
                    download {
                        subject("orders-value", "src/main/avro/registry", 3)
                        subject("money", "src/main/avro/registry")
                    }
                }
                """);
        Map<String, String> shell = new HashMap<>(System.getenv());
        shell.put("ACME_SR_KEY", "key-0123456789");
        shell.put("ACME_SR_SECRET", "secret-0123456789");
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));

        GradleBuildImport.Result result = RequestShell.where(
                shell, () -> gradle.importBuild(root.resolve("settings.gradle.kts"), ImportProgress.NONE));

        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors())
                .as("Gradle evaluated the build: " + rows)
                .isFalse();
        assertThat(result.root().pluginConfig("schema-registry").orElseThrow().values())
                .containsOnly(
                        Map.entry("url", "https://registry.example.com"),
                        Map.entry("subjects", Map.of("orders-value", "3", "money", "latest")),
                        Map.entry("repository", "schema-registry"));
        assertThat(result.machineCredentials()).singleElement().satisfies(c -> assertThat(c.credential())
                .containsExactly(
                        Map.entry("username", "${env:ACME_SR_KEY}"), Map.entry("password", "${env:ACME_SR_SECRET}")));
        assertThat(String.join("\n", rows)).doesNotContain("0123456789").doesNotContain("SchemaRegistryPlugin");
    }

    /** The jsonschema2pojo Gradle plugin's extension imports as [json-schema], its output raising no row. */
    @Test
    void the_jsonschema2pojo_plugin_imports_as_json_schema(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("events"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'events'\n");
        Files.writeString(root.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.jsonschema2pojo' version '1.3.3'
                }
                group = 'com.acme'
                version = '1.0'
                jsonSchema2Pojo {
                    sourceFiles = files('src/main/schemas')
                    targetPackage = 'com.acme.events'
                    generateBuilders = true
                    includeJsr303Annotations = true
                }
                """);
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("settings.gradle"), ImportProgress.NONE);

        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors())
                .as("Gradle evaluated the build: " + rows)
                .isFalse();
        assertThat(result.root().pluginConfig("json-schema").orElseThrow().values())
                .containsOnly(
                        Map.entry("src", "src/main/schemas"),
                        Map.entry("package", "com.acme.events"),
                        Map.entry("builders", true),
                        Map.entry("jsr303", true));
        assertThat(rows).noneMatch(r -> r.contains("jsonschema2pojo") || r.contains("outside jk's layout"));
    }

    /** sngular's scs-multiapi Gradle plugin's AsyncAPI spec imports as [asyncapi]. */
    @Test
    void the_scs_multiapi_plugin_s_asyncapi_spec_imports_as_asyncapi(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("orders"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
                root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GradleResolver.DEFAULT_VERSION
                        + "-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'orders'\n");
        Files.writeString(root.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.sngular.scs-multiapi-gradle-plugin' version '8.4.0'
                }
                group = 'com.acme'
                version = '1.0'
                asyncapimodel {
                    specFile {
                        filePath = './src/main/resources/api/event-api.yml'
                        consumer {
                            ids = 'publishOperation'
                            modelPackage = 'com.acme.orders.model'
                        }
                    }
                }
                """);
        GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, tmp.resolve("tmp")));

        GradleBuildImport.Result result = gradle.importBuild(root.resolve("settings.gradle"), ImportProgress.NONE);

        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors())
                .as("Gradle evaluated the build: " + rows)
                .isFalse();
        assertThat(result.root().pluginConfig("asyncapi").orElseThrow().values())
                .containsOnly(
                        Map.entry("src", "src/main/resources/api/event-api.yml"),
                        Map.entry("package", "com.acme.orders.model"));
        assertThat(rows).noneMatch(r -> r.contains("ScsMultiApi") || r.contains("scs-multiapi-gradle-plugin"));
    }

    private static JkBuild module(GradleBuildImport.Result result, String path) {
        return Objects.requireNonNull(result.modules().get(path), path);
    }
}
