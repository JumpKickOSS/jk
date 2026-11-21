// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GradleExporterTest {

    private static JkBuild parse(String toml) {
        return JkBuildParser.parse(toml);
    }

    @Test
    void application_project_emits_plugins_toolchain_and_mainClass() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.2.3"
                jdk  = { version = "25" }
                java = 25

                [application]
                main = "com.example.Main"

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts).contains("plugins {");
        assertThat(kts).contains("    java");
        assertThat(kts).contains("    application");
        assertThat(kts).contains("group = \"com.example\"");
        assertThat(kts).contains("version = \"1.2.3\"");
        assertThat(kts).contains("implementation(\"com.google.guava:guava:33.0.0-jre\")");
        assertThat(kts).contains("languageVersion = JavaLanguageVersion.of(25)");
        assertThat(kts).contains("mainClass = \"com.example.Main\"");
        assertThat(kts).contains("mavenCentral()");
    }

    @Test
    void managed_dependencies_export_as_constraints() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.2.3"
                java = 25

                [managed-dependencies]
                commons-io = "commons-io:commons-io:2.16.1"
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts)
                .contains("    constraints {\n        implementation(\"commons-io:commons-io:2.16.1\")\n    }\n");
        assertThat(kts).doesNotContain("\n    implementation(\"commons-io");
    }

    @Test
    void test_processors_export_as_testAnnotationProcessor() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [processor-dependencies]
                lombok = { group = "org.projectlombok", name = "lombok", version = "1.18.42" }

                [test-processor-dependencies]
                mapstruct-ap = { group = "org.mapstruct", name = "mapstruct-processor", version = "1.6.3" }
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts)
                .contains("    annotationProcessor(\"org.projectlombok:lombok:1.18.42\")")
                .contains("    testAnnotationProcessor(\"org.mapstruct:mapstruct-processor:1.6.3\")");
    }

    @Test
    void exclusions_export_as_exclude_blocks_and_a_constraint_cannot_carry_one() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                kafka = { group = "org.apache.kafka", name = "kafka-clients", version = "4.1.0", exclude = ["org.slf4j:slf4j-api", "com.github.luben:*", "*:snappy-java"] }
                netty = { group = "io.netty", name = "netty-all", version = "4.2.7.Final", exclude = ["*:*"] }

                [managed-dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.5.0-jre", exclude = ["*:listenablefuture"] }
                """);

        GradleExporter.Result result = GradleExporter.export(b, Map.of());
        String kts = result.buildFiles().get("");

        assertThat(kts)
                .contains("    implementation(\"org.apache.kafka:kafka-clients:4.1.0\") {\n"
                        + "        exclude(group = \"org.slf4j\", module = \"slf4j-api\")\n"
                        + "        exclude(group = \"com.github.luben\")\n"
                        + "        exclude(module = \"snappy-java\")\n"
                        + "    }\n")
                .contains("    implementation(\"io.netty:netty-all:4.2.7.Final\") {\n"
                        + "        isTransitive = false\n"
                        + "    }\n")
                .contains("        implementation(\"com.google.guava:guava:33.5.0-jre\")\n");
        assertThat(result.report().issues()).anySatisfy(i -> assertThat(i.message())
                .contains("com.google.guava:guava")
                .contains("*:listenablefuture")
                .contains("dropped; add them where the dependency is declared"));
    }

    @Test
    void locked_version_overrides_declared_selector() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }
                """);

        String kts = GradleExporter.export(b, Map.of("com.google.guava:guava", "33.4.0-jre"))
                .buildFiles()
                .get("");

        assertThat(kts).contains("implementation(\"com.google.guava:guava:33.4.0-jre\")");
        assertThat(kts).doesNotContain("33.0.0-jre");
    }

    @Test
    void floating_selector_without_lock_warns() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }
                """);

        GradleExporter.Result r = GradleExporter.export(b, Map.of());

        assertThat(r.buildFiles().get("")).contains("guava:33.0.0-jre");
        assertThat(r.report().issues()).anyMatch(i -> i.message().contains("guava"));
    }

    @Test
    void kotlin_shadow_and_native_emit_their_plugins() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25
                kotlin = "2.3.21"

                [application]
                main       = "com.example.Main"
                assembly = true

                [native]
                enabled = "always"
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts).contains("kotlin(\"jvm\") version \"2.3.21\"");
        assertThat(kts).contains("id(\"com.gradleup.shadow\")");
        assertThat(kts).contains("id(\"org.graalvm.buildtools.native\")");
    }

    @Test
    void settings_includes_foojay_and_workspace_modules() {
        JkBuild root = parse("""
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["mod-a", "mod-b"]
                """);
        JkBuild a = parse("""
                group = "com.example"
                name  = "mod-a"
                version = "1.0.0"
                java = 25
                """);

        GradleExporter.Result r = GradleExporter.export(root, Map.of("mod-a", a), Map.of());

        assertThat(r.settings()).contains("rootProject.name = \"root\"");
        assertThat(r.settings()).contains("foojay-resolver-convention");
        assertThat(r.settings()).contains("include(\":mod-a\")");
        assertThat(r.buildFiles()).containsKey("");
        assertThat(r.buildFiles()).containsKey("mod-a");
    }

    // Note: inline `path = "..."` dependencies were removed from the manifest schema (path targets
    // are now declared as workspace modules), so there is no path→includeBuild export case to test.

    @Test
    void a_string_literal_escapes_templates_and_control_characters() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [manifest]
                Built-By = "${System.getenv(\\"USER\\")} $HOME"
                Notes = "line one\\nline two\\r\\tend"
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts)
                .contains("\"Built-By\" to \"\\${System.getenv(\\\"USER\\\")} \\$HOME\"")
                .contains("\"Notes\" to \"line one\\nline two\\r\\tend\"")
                .doesNotContain("line one\nline two");
    }

    @Test
    void export_dependencies_apply_java_library_for_api() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "lib"
                version = "1.0.0"
                java = 25

                [export-dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.5.0-jre" }
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts).contains("plugins {\n    `java-library`\n").doesNotContain("\n    java\n");
        assertThat(kts).contains("    api(\"com.google.guava:guava:33.5.0-jre\")");
    }

    @Test
    void without_export_dependencies_the_plain_java_plugin_is_applied() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts).contains("plugins {\n    java\n").doesNotContain("java-library");
    }

    @Test
    void classifier_and_tests_kind_keep_their_artifact_and_read_their_lock_row() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }
                lwjgl = { group = "org.lwjgl", name = "lwjgl", version = "^3.3.0" }
                lwjgl-natives = { group = "org.lwjgl", name = "lwjgl", version = "^3.3.0", classifier = "natives-linux" }

                [test-dependencies]
                helpers = { group = "com.acme", name = "helpers", version = "^1.2.0", kind = "tests" }
                """);
        // Lock rows key on the package id, as jk-lock.toml writes them.
        Map<String, String> locked = Map.of(
                "com.google.guava:guava:jar:", "33.5.0-jre",
                "org.lwjgl:lwjgl:jar:", "3.3.6",
                "org.lwjgl:lwjgl:jar:natives-linux", "3.3.6",
                "com.acme:helpers:jar:tests", "1.2.9");

        GradleExporter.Result r = GradleExporter.export(b, locked);
        String kts = r.buildFiles().get("");

        assertThat(kts)
                .contains("    implementation(\"com.google.guava:guava:33.5.0-jre\")\n")
                .contains("    implementation(\"org.lwjgl:lwjgl:3.3.6\")\n")
                .contains("    implementation(\"org.lwjgl:lwjgl:3.3.6:natives-linux\")\n")
                .contains("    testImplementation(\"com.acme:helpers:1.2.9:tests\")\n");
        assertThat(r.report().issues()).as("every version came from the lock").isEmpty();
    }

    @Test
    void git_dependency_is_dropped_with_warning() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                acme = { git = "https://example.com/acme.git", tag = "v1.0" }
                """);

        GradleExporter.Result r = GradleExporter.export(b, Map.of());

        assertThat(r.report().issues()).anyMatch(i -> i.message().contains("git source dropped"));
    }

    @Test
    void a_workspace_sibling_is_a_project_reference() {
        JkBuild root = parse("""
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["libs/core", "app"]

                [workspace.dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.5.0-jre" }
                """);
        JkBuild core = parse("""
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                java = 25
                """);
        JkBuild app = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                core = { workspace = true }
                guava = { workspace = true }

                [test-dependencies]
                core-tests = { workspace = true, name = "core", kind = "tests" }
                """);
        Map<String, JkBuild> modules = new LinkedHashMap<>();
        modules.put("libs/core", core);
        modules.put("app", app);

        GradleExporter.Result r = GradleExporter.export(root, modules, Map.of());

        String kts = r.buildFiles().get("app");
        assertThat(kts).contains("    implementation(project(\":libs:core\"))\n");
        assertThat(kts).contains("    implementation(\"com.google.guava:guava:33.5.0-jre\")\n");
        assertThat(kts).contains("    testImplementation(project(\":libs:core\"))\n");
        assertThat(kts).doesNotContain("workspace:");
        assertThat(r.report().issues()).anyMatch(i -> i.message().startsWith("com.example:core: tests of"));
    }

    @Test
    void dev_dependencies_are_dropped_with_a_report_row() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dev-dependencies]
                devtools = { group = "org.springframework.boot", name = "spring-boot-devtools", version = "4.0.0" }
                """);

        GradleExporter.Result r = GradleExporter.export(b, Map.of());

        assertThat(r.buildFiles().get("")).doesNotContain("devtools");
        assertThat(r.report().issues()).anyMatch(i -> i.message().equals("[dev-dependencies] dropped"));
    }

    @Test
    void a_war_module_applies_the_war_plugin_and_reports_its_own_webapp() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "web"
                version = "1.0.0"
                java = 25

                [war]
                webapp = "web"
                """);

        GradleExporter.Result r = GradleExporter.export(b, Map.of());

        assertThat(r.buildFiles().get("")).contains("    java\n    war\n");
        assertThat(r.report().issues()).anyMatch(i -> i.message().startsWith("[war] name and webapp dropped"));
    }
}
