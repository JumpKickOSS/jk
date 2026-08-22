// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkBuildParserTest {

    private static final String PROJECT = """
            group    = "com.example"
            name     = "widget"
            version  = "1.0.0"
            jdk      = 25
            java     = 25
            """;

    @Test
    void dead_test_tag_keys_fail_with_a_migration_message() {
        // silently ignoring the renamed keys would run the tests the config excluded.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [test]
                        default-exclude-tags = ["slow"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("renamed to exclude-tags");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [test]
                        exclude-tag = ["slow"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("plural");
    }

    @Test
    void platform_policy_survives_kotlin_plugins_rebuild() {
        // the kotlin-plugins fold used a ctor that hard-reset platformPolicy to ENFORCED.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                kotlin = "2.1.0"

                [resolve]
                platform = "floor"

                [[kotlin-plugins]]
                coordinate = "org.jetbrains.kotlin:kotlin-serialization"
                """);
        assertThat(parsed.build().kotlinPlugins()).hasSize(1);
        assertThat(parsed.build().platformPolicy()).isEqualTo(cc.jumpkick.model.PlatformPolicy.FLOOR);
    }

    @Test
    void resolve_unmapped_parses_and_survives_kotlin_plugins_rebuild() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                kotlin = "2.1.0"

                [resolve]
                unmapped = "strict"

                [[kotlin-plugins]]
                coordinate = "org.jetbrains.kotlin:kotlin-serialization"
                """);
        assertThat(parsed.build().unmappedPolicy()).isEqualTo(cc.jumpkick.model.UnmappedPolicy.STRICT);
        // Default is mediate.
        assertThat(JkBuildParser.parse(PROJECT).build().unmappedPolicy())
                .isEqualTo(cc.jumpkick.model.UnmappedPolicy.MEDIATE);
    }

    @Test
    void parses_minimal_project_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().name()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("1.0.0");
        assertThat(parsed.project().jdk()).isEqualTo("25");
        assertThat(parsed.project().java()).isEqualTo(25);
        assertThat(parsed.project().isKotlin()).isFalse();
        assertThat(parsed.project().isGroovy()).isFalse();
        assertThat(parsed.project().isScala()).isFalse();
        assertThat(parsed.mainClass()).isNull();
        assertThat(parsed.isApplication()).isFalse();
        assertThat(parsed.assembly()).isFalse();
        // [native] absent entirely → DISABLED (its presence is the enable switch now)
        assertThat(parsed.nativeMode()).isEqualTo(JkBuild.NativeMode.DISABLED);
        assertThat(parsed.nativeImage()).isFalse();
        assertThat(parsed.project().description()).isNull();
    }

    @Test
    void parses_groovy_version_pin() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25
                groovy   = "=5.0.4"
                """);
        assertThat(parsed.project().isGroovy()).isTrue();
        assertThat(parsed.project().languageName()).isEqualTo("groovy");
        assertThat(parsed.project().groovy()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) parsed.project().groovy()).version())
                .isEqualTo("5.0.4");
    }

    @Test
    void bare_groovy_version_floats_like_a_dependency() {
        JkBuild parsed = JkBuildParser.parse(PROJECT.replace("java     = 25", "groovy   = \"5.0.4\""));
        assertThat(parsed.project().isGroovy()).isTrue();
        assertThat(parsed.project().groovy()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void blank_groovy_version_means_not_a_groovy_project() {
        JkBuild parsed = JkBuildParser.parse(PROJECT.replace("java     = 25", "groovy   = \"\""));
        assertThat(parsed.project().isGroovy()).isFalse();
        assertThat(parsed.project().groovy()).isNull();
        assertThat(parsed.project().languageName()).isEqualTo("java");
    }

    @Test
    void parses_scala_version_pin() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25
                scala    = "=3.8.4"
                """);
        assertThat(parsed.project().isScala()).isTrue();
        assertThat(parsed.project().languageName()).isEqualTo("scala");
        assertThat(parsed.project().scala()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) parsed.project().scala()).version()).isEqualTo("3.8.4");
    }

    @Test
    void bare_scala_version_floats_like_a_dependency() {
        JkBuild parsed = JkBuildParser.parse(PROJECT.replace("java     = 25", "scala    = \"3\""));
        assertThat(parsed.project().isScala()).isTrue();
        assertThat(parsed.project().scala()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(parsed.project().languageName()).isEqualTo("scala");
    }

    @Test
    void blank_scala_version_means_not_a_scala_project() {
        JkBuild parsed = JkBuildParser.parse(PROJECT.replace("java     = 25", "scala    = \"\""));
        assertThat(parsed.project().isScala()).isFalse();
        assertThat(parsed.project().scala()).isNull();
        assertThat(parsed.project().languageName()).isEqualTo("java");
    }

    @Test
    void absent_build_block_yields_empty_order_after() {
        JkBuild parsed = JkBuildParser.parse(PROJECT);
        assertThat(parsed.build()).isEqualTo(JkBuild.Build.EMPTY);
        assertThat(parsed.build().orderAfter()).isEmpty();
    }

    @Test
    void parses_build_order_after() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [build]
                order-after = ["kotlin-compiler", "cc.jumpkick:auditor"]
                """);
        assertThat(parsed.build().orderAfter()).containsExactly("kotlin-compiler", "cc.jumpkick:auditor");
    }

    @Test
    void rejects_non_string_order_after_entry() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [build]
                order-after = [123]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[build].order-after");
    }

    @Test
    void build_lint_defaults_on_and_can_be_disabled() {
        assertThat(JkBuildParser.parse(PROJECT).build().lint()).isTrue(); // absent → on
        JkBuild off = JkBuildParser.parse(PROJECT + """

                [build]
                lint = false
                """);
        assertThat(off.build().lint()).isFalse();
    }

    @Test
    void parses_build_test_worker_jars_as_order_after() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [build]
                test-plugin-jars = ["publisher", "compat-bridge"]
                """);
        assertThat(parsed.build().testPluginJars()).containsExactly("publisher", "compat-bridge");
        // test-plugin-jars modules must build first → they're order-after prerequisites
        assertThat(parsed.build().allOrderAfter()).contains("publisher", "compat-bridge");
    }

    @Test
    void extra_resources_is_not_a_build_setting() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [build]
                extra-resources = [ { from = "jk-plugin.toml", into = "" } ]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[build].extra-resources")
                .hasMessageContaining("jk-plugin.toml");
    }

    @Test
    void parses_test_workers_pin_and_parallel_false_alias() {
        assertThat(JkBuildParser.parse(PROJECT).build().testWorkers()).isNull();
        assertThat(JkBuildParser.parse(PROJECT + """

                [build]
                test-workers = 1
                """).build().testWorkers()).isEqualTo(1);
        assertThat(JkBuildParser.parse(PROJECT + """

                [build]
                test-parallel = false
                """).build().testWorkers()).isEqualTo(1);
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                workers = 2
                """).build().testWorkers()).isEqualTo(2);
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                parallel = false
                """).build().testWorkers()).isEqualTo(1);
        assertThat(JkBuildParser.parse(PROJECT + """

                [build]
                test-workers = 4

                [test]
                parallel = false
                """).build().effectiveTestWorkers(0))
                .isEqualTo(1);
    }

    @Test
    void parses_test_serial_tags() {
        assertThat(JkBuildParser.parse(PROJECT).build().testSerialTags()).isEmpty();
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                workers = 0
                serial-tags = ["integration", "slow"]
                """).build().testSerialTags()).containsExactly("integration", "slow");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [test]
                serial-tags = [1]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("serial-tags");
    }

    @Test
    void parses_optional_description() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                description = "A widget for widgeting."
                """);
        assertThat(parsed.project().description()).isEqualTo("A widget for widgeting.");
    }

    @Test
    void blank_description_normalises_to_null() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                description = "   "
                """);
        assertThat(parsed.project().description()).isNull();
    }

    @Test
    void missing_name_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(""))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejects_legacy_project_table() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[project] was removed");
    }

    @Test
    void rejects_project_java_below_17() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                java     = 11
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("java = 11")
                .hasMessageContaining("JDK 17 and above");
    }

    @Test
    void java_accepts_quoted_string_and_coerces() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                java     = "25"
                """);
        assertThat(parsed.project().java()).isEqualTo(25);
    }

    @Test
    void rejects_non_numeric_java_string() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                java     = "twenty-five"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("java");
    }

    @Test
    void rejects_project_jdk_below_17() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 8
                java     = 25
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jdk = 8");
    }

    @Test
    void parses_format_block() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"

                [format]
                style  = "standard"
                java   = "palantir"
                kotlin = "kotlinlang"
                """);
        assertThat(parsed.format().style()).isEqualTo("standard");
        assertThat(parsed.format().java()).isEqualTo("palantir");
        assertThat(parsed.format().kotlin()).isEqualTo("kotlinlang");
        assertThat(parsed.format().optimizeImports()).isNull();
        assertThat(parsed.format().importOrder()).isNull();
        assertThat(parsed.format().removeUnusedImports()).isNull();
    }

    @Test
    void parses_format_hygiene_toggles() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"

                [format]
                optimize-imports       = false
                import-order           = false
                remove-unused-imports  = true
                """);
        assertThat(parsed.format().optimizeImports()).isFalse();
        assertThat(parsed.format().importOrder()).isFalse();
        assertThat(parsed.format().removeUnusedImports()).isTrue();
    }

    @Test
    void format_block_absent_is_empty() {
        JkBuild parsed = JkBuildParser.parse(PROJECT);
        assertThat(parsed.format()).isEqualTo(JkBuild.FormatConfig.EMPTY);
        assertThat(parsed.format().java()).isNull();
    }

    @Test
    void parses_jdk_vendor_major_spec() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "temurin-25"
                """);
        assertThat(parsed.project().jdk()).isEqualTo("temurin-25");
        assertThat(parsed.project().jdkMajor()).isEqualTo(25);
        // java falls back to the jdk major when not given explicitly.
        assertThat(parsed.project().javaRelease()).isEqualTo(25);
    }

    @Test
    void parses_jdk_keyword_specs() {
        // lts/stable/latest/native are accepted as-is (the same keywords --jdk/JK_JDK/
        // .jdk-version accept) — resolved downstream by JdkKeywords, not by this parser.
        for (String keyword : new String[] {"lts", "stable", "latest", "native"}) {
            JkBuild parsed = JkBuildParser.parse("""
                    group    = "com.example"
                    name     = "widget"
                    version  = "1.0.0"
                    jdk      = "%s"
                    """.formatted(keyword));
            assertThat(parsed.project().jdk()).isEqualTo(keyword);
        }
    }

    @Test
    void parses_jdk_bare_major_string() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "25"
                """);
        assertThat(parsed.project().jdk()).isEqualTo("25");
        assertThat(parsed.project().jdkMajor()).isEqualTo(25);
    }

    @Test
    void parses_graal_spec_variants() {
        assertThat(JkBuildParser.parse(graal("\"graalvm-25\"")).graal()).isEqualTo("graalvm-25");
        assertThat(JkBuildParser.parse(graal("25")).graal()).isEqualTo("25");
        assertThat(JkBuildParser.parse(graal("\"native\"")).graal()).isEqualTo("native");
        // [native] declared, graal key omitted → defaults to the "graalvm" spec.
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\n").graal()).isEqualTo("graalvm");
        // No [native] table at all → null.
        assertThat(JkBuildParser.parse(PROJECT).graal()).isNull();
    }

    @Test
    void rejects_graal_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse(graal("\"graalvm-25.0.3\"")))
                .hasMessageContaining("[native].graal");
    }

    private static String graal(String value) {
        return PROJECT + """

                [native]
                graal = %s
                """.formatted(value);
    }

    @Test
    void accepts_unquoted_integer_jdk_as_bare_major() {
        // Back-compat: the old integer form coerces to a bare-major string.
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25
                """);
        assertThat(parsed.project().jdk()).isEqualTo("25");
    }

    @Test
    void rejects_jdk_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "25.0.3"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("point release");
    }

    @Test
    void rejects_jdk_vendor_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "temurin-25.0.3"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("point release");
    }

    @Test
    void missing_version_on_standalone_marks_workspace_inherit() {
        // Non-root omit of version is inheritance (member-shaped); not a hard parse error.
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                """);
        assertThat(parsed.project().inheritsVersionFromWorkspace()).isTrue();
        assertThat(parsed.project().requiresWorkspaceRoot()).isTrue();
    }

    @Test
    void workspace_root_still_requires_concrete_version() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "root"

                [workspace]
                modules = ["lib"]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("version");
    }

    @Test
    void module_shaped_only_name_is_valid_parse() {
        JkBuild parsed = JkBuildParser.parse("""
                name = "foo"
                """);
        assertThat(parsed.project().name()).isEqualTo("foo");
        assertThat(parsed.project().inherits(JkBuild.ProjectInherit.GROUP)).isTrue();
        assertThat(parsed.project().inherits(JkBuild.ProjectInherit.VERSION)).isTrue();
        assertThat(parsed.project().inherits(JkBuild.ProjectInherit.JAVA)).isTrue();
        assertThat(parsed.project().inherits(JkBuild.ProjectInherit.DESCRIPTION))
                .isFalse();
        assertThat(parsed.project().description()).isNull();
    }

    @Test
    void version_workspace_true_parses_as_inheritance_sentinel() {
        // Dotted key form (Cargo-style).
        JkBuild dotted = JkBuildParser.parse("""
                group   = "com.example"
                name    = "mod"
                version.workspace = true
                jdk     = 25
                java    = 25
                """);
        assertThat(dotted.project().inheritsVersionFromWorkspace()).isTrue();
        assertThat(dotted.project().version()).isEqualTo(JkBuild.VERSION_FROM_WORKSPACE);

        // Inline table form.
        JkBuild inline = JkBuildParser.parse("""
                group   = "com.example"
                name    = "mod"
                version = { workspace = true }
                jdk     = 25
                java    = 25
                """);
        assertThat(inline.project().inheritsVersionFromWorkspace()).isTrue();
    }

    @Test
    void project_field_workspace_inheritance_parses_multiple_fields() {
        JkBuild parsed = JkBuildParser.parse("""
                group.workspace = true
                name    = "mod"
                version.workspace = true
                java.workspace = true
                jdk.workspace = true
                description.workspace = true
                """);
        var p = parsed.project();
        assertThat(p.inheritsFromWorkspace()).isTrue();
        assertThat(p.inherits(JkBuild.ProjectInherit.GROUP)).isTrue();
        assertThat(p.inherits(JkBuild.ProjectInherit.VERSION)).isTrue();
        assertThat(p.inherits(JkBuild.ProjectInherit.JAVA)).isTrue();
        assertThat(p.inherits(JkBuild.ProjectInherit.JDK)).isTrue();
        assertThat(p.inherits(JkBuild.ProjectInherit.DESCRIPTION)).isTrue();
        assertThat(p.name()).isEqualTo("mod");
    }

    @Test
    void name_workspace_inheritance_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group   = "com.example"
                name.workspace = true
                version = "1.0.0"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("workspace");
    }

    @Test
    void version_workspace_true_rejected_on_workspace_root() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group   = "com.example"
                name    = "root"
                version.workspace = true

                [workspace]
                modules = ["lib"]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace root");
    }

    @Test
    void version_workspace_must_be_true() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group   = "com.example"
                name    = "mod"
                version = { workspace = false }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace");
    }

    @Test
    void parses_name_as_key_dep_with_full_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                slf4j-api = { group = "org.slf4j", name = "slf4j-api", version = "2.0.16" }
                picocli   = { group = "info.picocli", name = "picocli", version = "^4.7.7" }

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = ">=5.10, <6" }
                """);

        var mainDeps = parsed.dependencies().of(Scope.MAIN);
        assertThat(mainDeps).hasSize(2);

        // Bare version on the new format → Caret (Cargo-style default).
        var slf4j = mainDeps.get(0);
        assertThat(slf4j.library()).isEqualTo("slf4j-api");
        assertThat(slf4j.module()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(slf4j.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(slf4j.pinned()).isFalse();

        var picocli = mainDeps.get(1);
        assertThat(picocli.library()).isEqualTo("picocli");
        assertThat(picocli.module()).isEqualTo("info.picocli:picocli");
        assertThat(picocli.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(picocli.pinned()).isFalse();

        var testDeps = parsed.dependencies().of(Scope.TEST);
        assertThat(testDeps).hasSize(1);
        assertThat(testDeps.getFirst().pinned()).isFalse();
        assertThat(testDeps.getFirst().version()).isInstanceOf(VersionSelector.Range.class);
    }

    @Test
    void artifact_defaults_to_key_name() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.library()).isEqualTo("picocli");
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void equals_selector_pins_dep() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lib = { group = "com.example", version = "=1.2.3" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) dep.version()).version()).isEqualTo("1.2.3");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void bare_version_is_caret_floating() {
        // Per the v1 locked default: bare "1.2.3" reads as ^1.2.3.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lib = { group = "com.example", version = "1.2.3" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(((VersionSelector.Caret) dep.version()).version()).isEqualTo("1.2.3");
        assertThat(dep.pinned()).isFalse();
    }

    @Test
    void latest_selector_floats() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lib = { group = "com.example", version = "latest" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Latest.class);
        assertThat(dep.pinned()).isFalse();
    }

    @Test
    void default_scope_shorthand_treats_dependencies_as_main() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                slf4j-api = { group = "org.slf4j", version = "2.0.16" }
                picocli   = { group = "info.picocli", version = "4.7.7" }
                """);

        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(2);
        assertThat(parsed.dependencies().of(Scope.TEST)).isEmpty();
    }

    @Test
    void main_and_test_dependencies_can_coexist_as_separate_top_level_tables() {
        // [dependencies] (MAIN) and [test-dependencies] (TEST) are independent
        // top-level sections; they may both appear in the same jk.toml.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                stray = { group = "com.example", version = "1.0" }

                [test-dependencies]
                junit = { group = "org.junit.jupiter", name = "junit-jupiter", version = "5.10.0" }
                """);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
    }

    @Test
    void inline_path_dependency_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared-utils = { path = "../shared-utils" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource().rawPath()).isEqualTo("../shared-utils");
        assertThat(dep.module()).isEqualTo("path:shared-utils");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void inline_path_dependency_rejects_an_explicit_coordinate() {
        // Like git, a path dep discovers its coordinate from the target — declaring
        // group/name/version here is an error (named specifically, not a multi-source complaint).
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                picocli = { path = "../picocli", group = "info.picocli" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("with `path` must not set `group`");
    }

    @Test
    void git_source_inline_on_dep_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                codec = { git = "https://github.com/acme/codec", tag = "v0.9.1" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().originalUrl()).isEqualTo("https://github.com/acme/codec");
        assertThat(dep.gitSource().ref()).isInstanceOf(GitRefSpec.Tag.class);
        assertThat(dep.module()).isEqualTo("git:codec");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void git_source_must_set_exactly_one_ref() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                codec = { git = "https://github.com/acme/codec", tag = "v1", branch = "main" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    void git_source_discovers_coordinate_from_repo() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        // Discovery: a placeholder module the resolver rewrites once the repo's
        // project coordinate is known.
        assertThat(dep.module()).isEqualTo("git:mylib");
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Tag("v1.4.0"));
    }

    @Test
    void git_source_rejects_group_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", group = "com.acme" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`group`");
    }

    @Test
    void git_source_rejects_name_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", name = "widgets" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`name`");
    }

    @Test
    void git_source_rejects_version_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", tag = "v1.0", version = "1.0.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`version`");
    }

    @Test
    void git_source_rejects_fetch_field() {
        // `fetch` (the branch-tip freshness window) is gone — every git dep is pinned
        // in jk-lock.toml and only moves on an explicit `jk update --git` / `jk fetch`.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", fetch = "48h" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("fetch");
    }

    @Test
    void git_source_requires_tag_branch_rev_or_embedded_ref() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/widgets" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set `tag`, `branch`, or `rev`");
    }

    @Test
    void git_source_url_embedded_branch_via_at() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/jin-tonic/jin@main" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.originalUrl()).isEqualTo("https://github.com/jin-tonic/jin");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Branch("main"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_embedded_tag_via_at_is_deep_clone() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = { git = "https://github.com/psf/requests.git@v1.2.3" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse(); // URL-embedded → always deep
    }

    @Test
    void git_source_explicit_tag_is_shallow_clone() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.4.0"));
        assertThat(src.shallow()).isTrue(); // explicit tag = → shallow
    }

    @Test
    void git_source_url_embedded_rev_via_hash() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/user/repo#8f3a1b2c4d5e6f" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_embedded_ref_and_explicit_ref_conflict() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/widgets@main", branch = "main" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("URL-embedded ref");
    }

    @Test
    void git_source_subdir_via_bang_before_ref() {
        // url!subdir@ref form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo!components/auth@v1.2.3" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.originalUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_subdir_via_bang_after_ref() {
        // url@ref!subdir form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "git://github.com/user/repo@v1.2.3!components/auth" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.originalUrl()).isEqualTo("git://github.com/user/repo");
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_subdir_via_bang_with_sha() {
        // url#sha!subdir form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo#8f3a1b2c4d5e6f!components/auth" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_subdir_and_explicit_path_conflict() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo!components/auth", branch = "main", path = "components/auth" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("sub-directory");
    }

    @Test
    void multiple_sources_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/bad", branch = "main", version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void no_source_on_dep_parses_as_platform_managed() {
        // Contract change (spring-boot plan §3.1): group-only = version supplied by an
        // imported [platform-dependencies] BOM at resolve time. Resolve errors clearly
        // when no BOM covers the module.
        JkBuild b = JkBuildParser.parse(PROJECT + """
                [dependencies]
                widget = { group = "com.example" }
                """);
        assertThat(b.dependencies().of(cc.jumpkick.model.Scope.MAIN).get(0).isPlatformManaged())
                .isTrue();
    }

    @Test
    void missing_group_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("group");
    }

    @Test
    void dep_string_value_is_catalog_shorthand_not_v0_6_coord_string() {
        // A string value is now treated as the version shorthand for a
        // catalog-known name. Pasting the old v0.6 coord-string form
        // ("group:artifact:version") trips the unknown-short-name error.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                "org.foo:bar" = "1.0"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void parses_workspace_dependencies_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a", "b"]

                [workspace.dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.0" }
                assertj-core  = { group = "org.assertj",       name = "assertj-core",  version = "3.27.7" }
                """);
        assertThat(parsed.workspace().dependencies()).hasSize(2);
        var jj = parsed.workspace().dependencies().get("junit-jupiter");
        assertThat(jj.group()).isEqualTo("org.junit.jupiter");
        assertThat(jj.artifact()).isEqualTo("junit-jupiter");
        assertThat(jj.module()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(jj.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void workspace_dependencies_artifact_defaults_to_key() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """);
        var pico = parsed.workspace().dependencies().get("picocli");
        assertThat(pico.artifact()).isEqualTo("picocli");
    }

    @Test
    void workspace_dependencies_string_shorthand_resolves_through_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                jackson-databind = "2.18.2"
                """, TEST_CATALOG);
        var jd = parsed.workspace().dependencies().get("jackson-databind");
        assertThat(jd.module()).isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(jd.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void workspace_dependencies_table_without_group_resolves_through_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                picocli = { version = "4.7.7" }
                """, TEST_CATALOG);
        var pico = parsed.workspace().dependencies().get("picocli");
        assertThat(pico.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void workspace_dependencies_unknown_shorthand_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                some-unknown-thing = "1.0.0"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void workspace_dependencies_string_shorthand_rejects_git_url() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                shared = "https://github.com/acme/shared"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("version spec");
    }

    @Test
    void workspace_dependencies_rejects_path_source() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                shared-lib = { group = "com.acme", path = "../shared" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[workspace] modules");
    }

    @Test
    void workspace_true_resolves_against_workspace_dependencies() {
        // A workspace = true dep with a matching [workspace.dependencies]
        // entry materializes that entry's coord directly during parse.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = []

                [workspace.dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.0" }

                [test-dependencies]
                junit-jupiter.workspace = true
                """);
        var dep = parsed.dependencies().of(Scope.TEST).getFirst();
        assertThat(dep.library()).isEqualTo("junit-jupiter");
        assertThat(dep.module()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(dep.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void workspace_true_without_match_emits_placeholder_for_merge() {
        // Without a [workspace.dependencies] match the parser emits a
        // placeholder that WorkspaceMerge resolves against the sibling
        // list. The single-file parser cannot fail here because it does
        // not own the sibling roster.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jk-core.workspace = true
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.library()).isEqualTo("jk-core");
        assertThat(dep.module()).startsWith("workspace:");
    }

    @Test
    void workspace_true_cannot_combine_with_version() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { workspace = true, version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void workspace_true_cannot_combine_with_group() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { workspace = true, group = "com.example" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must not set `group`");
    }

    @Test
    void workspace_false_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { workspace = false }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be `true`");
    }

    @Test
    void parses_repositories_string_and_table_form() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                internal = { url = "https://nexus.example/repository/maven-releases/" }
                """);
        assertThat(parsed.repositories()).extracting(r -> r.name()).containsExactlyInAnyOrder("central", "internal");
        // No inline credential on either repo.
        assertThat(parsed.repositories())
                .allSatisfy(r -> assertThat(r.credential()).isEmpty());
    }

    @Test
    void parses_repository_exclusive_groups() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.central]
                url = "https://repo.maven.apache.org/maven2/"

                [repositories.internal]
                url = "https://repo.acme.com/maven"
                groups = ["com.acme", "com.acme.*"]
                """);
        var internal = parsed.repositories().stream()
                .filter(r -> r.name().equals("internal"))
                .findFirst()
                .orElseThrow();
        assertThat(internal.groups()).containsExactly("com.acme", "com.acme.*");
        var central = parsed.repositories().stream()
                .filter(r -> r.name().equals("central"))
                .findFirst()
                .orElseThrow();
        assertThat(central.groups()).isEmpty();
    }

    @Test
    void parses_inline_token_and_basic_credentials() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.ghp]
                url = "https://maven.pkg.github.com/JumpKickOSS/jk"
                token = "ghp_literaltoken"

                [repositories.nexus]
                url = "https://nexus.example/repository/maven-releases/"
                username = "deployer"
                password = "s3cr3t"
                """);

        var ghp = parsed.repositories().stream()
                .filter(r -> r.name().equals("ghp"))
                .findFirst()
                .orElseThrow();
        assertThat(ghp.credential()).contains(new RepoCredential.Bearer("ghp_literaltoken"));

        var nexus = parsed.repositories().stream()
                .filter(r -> r.name().equals("nexus"))
                .findFirst()
                .orElseThrow();
        assertThat(nexus.credential()).contains(new RepoCredential.Basic("deployer", "s3cr3t"));
    }

    @Test
    void parses_object_store_config_for_s3_repo() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.s3-releases]
                url = "s3://acme-artifacts/releases"
                region = "us-west-2"
                endpoint = "https://minio.internal:9000"
                access-key = "AKID"
                secret-key = "SEKRIT"
                """);
        var spec = parsed.repositories().get(0);
        assertThat(spec.objectStore()).hasValueSatisfying(c -> {
            assertThat(c.region()).isEqualTo("us-west-2");
            assertThat(c.endpoint()).isEqualTo("https://minio.internal:9000");
            assertThat(c.accessKey()).isEqualTo("AKID");
            assertThat(c.secretKey()).isEqualTo("SEKRIT");
            assertThat(c.hasExplicitCredentials()).isTrue();
        });
    }

    @Test
    void object_store_config_absent_when_no_keys() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.plain]
                url = "https://repo.example/maven"
                """);
        assertThat(parsed.repositories().get(0).objectStore()).isEmpty();
    }

    @Test
    void object_store_keys_keep_their_raw_env_references() {
        // Same contract as credentialsraw out of the parse, expanded by RepoGroupBuilder
        // where the request's environment is in scope. Object-store keys are secrets, so they must
        // not be committed literally — but the parse is not the place to resolve them.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.s3]
                url = "s3://bucket/maven"
                access-key = "${AWS_KEY}"
                secret-key = "literal-secret"
                """);
        assertThat(parsed.repositories().get(0).objectStore()).hasValueSatisfying(c -> {
            assertThat(c.accessKey()).isEqualTo("${AWS_KEY}");
            assertThat(c.secretKey()).isEqualTo("literal-secret");
        });
    }

    @Test
    void an_inline_credential_keeps_its_raw_env_reference() {
        // The parse deliberately does NOT interpolateit stays a pure function of the
        // file's bytes, so the memo needs no environment in its key and the engine cannot
        // accidentally resolve against the daemon's environment instead of the caller's.
        // Expansion — and its strictness — is RepoCredentialResolver's job; see
        // RepoCredentialResolverTest.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.r]
                url = "https://nexus.example/repo/"
                token = "${PATH}"
                """);
        assertThat(parsed.repositories().get(0).credential()).contains(new RepoCredential.Bearer("${PATH}"));
    }

    @Test
    void an_unset_env_var_no_longer_fails_the_parse() {
        // It fails when the repository is USED, not when a manifest merely mentions it — so a
        // manifest may reference a private mirror this machine has no credentials for.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.r]
                url = "https://nexus.example/repo/"
                token = "${JK_DEFINITELY_UNSET_VAR_XYZ}"
                """);
        assertThat(parsed.repositories().get(0).credential())
                .contains(new RepoCredential.Bearer("${JK_DEFINITELY_UNSET_VAR_XYZ}"));
    }

    @Test
    void parses_workspace_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["core", "io"]
                """);
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().modules()).containsExactly("core", "io");
        assertThat(parsed.workspace().dependencies()).isEmpty();
    }

    @Test
    void parses_profiles_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [profiles.dev]
                javac = ["-g", "-parameters"]
                jvm-args = ["-Xshare:auto"]

                [profiles.ci]
                inherits = "dev"
                javac = ["-Werror"]
                """);
        assertThat(parsed.profiles().byName().get("dev").javacArgs()).contains("-g");
        assertThat(parsed.profiles().byName().get("ci").inherits()).isEqualTo("dev");
    }

    @Test
    void profiles_tag_keys_record_presence_including_empty_lists() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [profiles.local]
                exclude-tags = ["slow", "bench"]

                [profiles.ci]
                exclude-tags = []
                include-tags = ["smoke"]
                """);
        var local = parsed.profiles().byName().get("local");
        assertThat(local.excludeTagsSet()).isTrue();
        assertThat(local.includeTagsSet()).isFalse();
        assertThat(local.excludeTags()).containsExactly("slow", "bench");

        var ci = parsed.profiles().byName().get("ci");
        assertThat(ci.excludeTagsSet()).isTrue();
        assertThat(ci.excludeTags()).isEmpty();
        assertThat(ci.includeTagsSet()).isTrue();
        assertThat(ci.includeTags()).containsExactly("smoke");
    }

    @Test
    void parse_test_tags_reads_include_and_exclude(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), PROJECT + """
                        [test]
                        include-tags = ["unit"]
                        exclude-tags = ["slow", "bench"]
                        """);
        var tags = JkBuildParser.parseTestTags(dir.resolve("jk.toml"));
        assertThat(tags.includeTags()).containsExactly("unit");
        assertThat(tags.excludeTags()).containsExactly("slow", "bench");
    }

    // ───────────────────────────────────────────────────────────────
    // Library-catalog shorthand
    // ───────────────────────────────────────────────────────────────

    /** Synthetic catalog used so the tests don't drift with the bundled set. */
    private static final LibraryCatalog TEST_CATALOG = LibraryCatalog.of(Map.of(
            "jackson-databind", new LibraryCatalog.Module("tools.jackson.core", "jackson-databind"),
            "picocli", new LibraryCatalog.Module("info.picocli", "picocli")));

    @Test
    void catalog_key_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse("catalog = \"bundled\"\n" + PROJECT))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("catalog = … was removed");
    }

    @Test
    void libraries_table_in_jk_toml_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [libraries]
                picocli = "io.fork:picocli"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jk-libs.toml");
    }

    @Test
    void shorthand_relative_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                my-lib = "./some/local/path"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource().rawPath()).isEqualTo("./some/local/path");
        assertThat(dep.module()).isEqualTo("path:my-lib");
    }

    @Test
    void shorthand_parent_relative_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared = "../shared-utils"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource().rawPath()).isEqualTo("../shared-utils");
    }

    @Test
    void shorthand_absolute_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared = "/opt/libs/shared"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource().rawPath()).isEqualTo("/opt/libs/shared");
    }

    @Test
    void shorthand_https_url_defaults_to_main_branch() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = "https://github.com/psf/requests"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Branch("main"));
        assertThat(dep.gitSource().shallow()).isFalse();
        assertThat(dep.module()).isEqualTo("git:requests");
    }

    @Test
    void shorthand_git_url_with_embedded_branch() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jin = "git://github.com/jin-tonic/jin@mybranch"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Branch("mybranch"));
        assertThat(dep.gitSource().shallow()).isFalse();
    }

    @Test
    void shorthand_git_url_with_embedded_tag() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = "https://github.com/psf/requests.git@v1.2.3"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(dep.gitSource().shallow()).isFalse(); // URL-embedded → always deep
    }

    @Test
    void shorthand_git_url_with_embedded_sha() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                repo = "git://github.com/user/repo#8f3a1b2c4d5e6f"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
    }

    @Test
    void shorthand_git_url_with_subdir() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = "https://github.com/user/repo!components/auth@main"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().path()).isEqualTo("components/auth");
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Branch("main"));
    }

    @Test
    void shorthand_ambiguous_string_is_unknown_library() {
        // A string that isn't a version spec, keyword, git URL, or explicit path
        // prefix is never guessed at as a path dep — it's an unknown short name.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                my-dep = "some-dir"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_string_with_slash_is_unknown_library() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = "components/auth"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_reserved_keyword_latest_is_always_catalog() {
        // "latest" is reserved — never statted, always a version lookup.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "latest"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_reserved_keyword_stable_is_always_catalog() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "stable"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_version_starting_with_digit_is_catalog() {
        // "1.2.3" starts with a digit → treated as version spec, not a path.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "1.2.3"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_caret_version_is_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = "^2.18.0"
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isFalse();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void isVersionSpecOrKeyword_covers_all_reserved_words() {
        assertThat(JkBuildParser.isVersionSpecOrKeyword("latest")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("stable")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("lts")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("preview")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("^2.0.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("~1.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("=1.2.3")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword(">=1.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("1.2.3")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("some-dir")).isFalse();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("components/auth")).isFalse();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("my-library")).isFalse();
    }

    @Test
    void shorthand_string_value_resolves_through_catalog() {
        // The cargo-add experience: `name = "1.0.0"` looks up the coord in
        // the bundled catalog and treats the version as caret-floating.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = "2.18.2"
                """, TEST_CATALOG);
        var deps = parsed.dependencies().of(Scope.MAIN);
        assertThat(deps).hasSize(1);
        assertThat(deps.getFirst().library()).isEqualTo("jackson-databind");
        assertThat(deps.getFirst().module()).isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(deps.getFirst().version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void shorthand_table_without_group_resolves_through_catalog() {
        // A dep table that lists only `version` (no `group`) falls back to
        // the catalog the same way the string shorthand does.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                picocli = { version = "4.7.7" }
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void explicit_group_overrides_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = { group = "io.fork", version = "2.18.2" }
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("io.fork:jackson-databind");
    }

    @Test
    void unknown_shorthand_string_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                some-unknown-thing = "1.0.0"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void unknown_shorthand_surfaces_did_you_mean_for_split_families() {
        // The parser's unknown-library message includes suggestions drawn
        // from the catalog. For the Jackson family this means typing the
        // unprefixed name surfaces both major-version flavors.
        LibraryCatalog splitFamily = LibraryCatalog.of(Map.of(
                "jackson2-databind", new LibraryCatalog.Module("com.fasterxml.jackson.core", "jackson-databind"),
                "jackson3-databind", new LibraryCatalog.Module("tools.jackson.core", "jackson-databind")));

        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = "2.18.2"
                """, splitFamily))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name `jackson-databind`")
                .hasMessageContaining("Did you mean:")
                .hasMessageContaining("jackson2-databind")
                .hasMessageContaining("jackson3-databind");
    }

    @Test
    void unknown_table_without_group_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                also-unknown = { version = "1.0.0" }
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set a `group`");
    }

    @Test
    void project_jk_libs_toml_overrides_passed_in_catalog(@TempDir Path tmp) throws Exception {
        // Workspace-root jk-libs.toml is the top of the lookup chain when parsing from disk.
        Files.writeString(tmp.resolve("jk.toml"), PROJECT + """
                        [dependencies]
                        picocli = "4.7.7"
                        """);
        Files.writeString(tmp.resolve("jk-libs.toml"), """
                [libraries]
                picocli = "io.fork:picocli"
                """);
        JkBuild parsed = JkBuildParser.parse(tmp.resolve("jk.toml"));
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("io.fork:picocli");
    }

    @Test
    void project_jk_libs_toml_can_introduce_a_brand_new_short_name(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), PROJECT + """
                        [dependencies]
                        internal-widget = "0.1.0"
                        """);
        Files.writeString(tmp.resolve("jk-libs.toml"), """
                [libraries]
                internal-widget = "com.acme:internal-widget"
                """);
        JkBuild parsed = JkBuildParser.parse(tmp.resolve("jk.toml"));
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("com.acme:internal-widget");
    }

    @Test
    void project_jk_libs_toml_with_versioned_coord_is_ignored_with_warning(@TempDir Path tmp) throws Exception {
        // Malformed project layer is fail-soft (warn + skip), same as a bad global layer.
        Files.writeString(tmp.resolve("jk.toml"), PROJECT + """
                        [dependencies]
                        picocli = "4.7.7"
                        """);
        Files.writeString(tmp.resolve("jk-libs.toml"), """
                [libraries]
                bad = "com.acme:bad:1.0.0"
                """);
        // picocli resolves through the system catalog (bundled), not the skipped project layer.
        JkBuild parsed = JkBuildParser.parse(tmp.resolve("jk.toml"));
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void module_may_not_have_jk_libs_toml(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        Path mod = root.resolve("lib");
        Files.createDirectories(mod);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["lib"]
                """);
        Files.writeString(mod.resolve("jk.toml"), """
                name = "lib"
                """);
        Files.writeString(mod.resolve("jk-libs.toml"), """
                [libraries]
                x = "com.acme:x"
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(mod.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("only allowed at the workspace root");
    }

    @Test
    void parses_optional_dependency_flag() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre", optional = true }
                jackson-databind = { group = "com.fasterxml.jackson.core", version = "2.18.2" }
                """);
        var deps = parsed.dependencies().of(cc.jumpkick.model.Scope.MAIN);
        assertThat(deps)
                .filteredOn(d -> d.library().equals("guava"))
                .singleElement()
                .satisfies(d -> assertThat(d.optional()).isTrue());
        assertThat(deps)
                .filteredOn(d -> d.library().equals("jackson-databind"))
                .singleElement()
                .satisfies(d -> assertThat(d.optional()).isFalse());
    }

    @Test
    void parses_features_block_with_dep_names() {
        // Feature `deps` are now dep names (not coord strings). Resolution
        // happens at activation time, against [dependencies.*].
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [features]
                default = ["postgres"]

                [features.postgres]
                deps = ["postgres-jdbc", "hikari"]
                """);
        assertThat(parsed.features().defaults()).containsExactly("postgres");
        assertThat(parsed.features().byName().get("postgres").deps()).containsExactly("postgres-jdbc", "hikari");
    }

    // --- application / native / m2integration -----------------------------------

    @Test
    void application_absent_means_not_an_application() {
        assertThat(JkBuildParser.parse(PROJECT).isApplication()).isFalse();
        assertThat(JkBuildParser.parse(PROJECT).mainClass()).isNull();
    }

    @Test
    void application_table_is_rejected_on_a_plugin_module(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk-plugin.toml"), "[plugin]\nid = \"x\"\ntable = \"x\"\n");
        Files.writeString(
                dir.resolve("jk.toml"),
                PROJECT + "\n[application]\nmain = \"cc.jumpkick.plugin.process.PluginMain\"\n");
        assertThatThrownBy(() -> JkBuildParser.parse(dir.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[application]")
                .hasMessageContaining("plugin worker");
    }

    @Test
    void application_present_with_main() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + "\n[application]\nmain = \"com.example.Main\"\n");
        assertThat(parsed.isApplication()).isTrue();
        assertThat(parsed.mainClass()).isEqualTo("com.example.Main");
    }

    @Test
    void application_requires_main() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "\n[application]\nassembly = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[application].main");
    }

    @Test
    void application_native_true_is_always() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [application]
                main   = "com.example.App"
                native = true
                """);
        assertThat(parsed.isApplication()).isTrue();
        assertThat(parsed.application().orElseThrow().nativeImage()).isTrue();
        assertThat(parsed.nativeMode()).isEqualTo(JkBuild.NativeMode.ALWAYS);
        assertThat(parsed.graal()).isEqualTo("graalvm");
    }

    @Test
    void application_native_true_conflicts_with_native_enabled_false() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [application]
                main   = "com.example.App"
                native = true

                [native]
                enabled = false
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[application].native")
                .hasMessageContaining("enabled = false");
    }

    @Test
    void minified_implies_assembly_and_enables_the_minified_plugin_without_a_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + "\n[application]\nmain = \"demo.App\"\nminified = true\n");
        assertThat(parsed.isApplication()).isTrue();
        assertThat(parsed.minified()).isTrue();
        assertThat(parsed.assembly())
                .as("the fat jar is built beside the minified one")
                .isTrue();
        assertThat(parsed.pluginConfig("minified")).isPresent();
    }

    @Test
    void the_old_shrink_spelling_points_at_minified() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "\n[application]\nassembly = \"shrink\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("minified = true");
    }

    @Test
    void application_assembly_rejects_unknown_string() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "\n[application]\nassembly = \"shadow\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("assembly");
    }

    @Test
    void artifact_override_minified_injects_the_minified_plugin() {
        JkBuild base = JkBuildParser.parse(PROJECT + "\n[application]\nmain = \"demo.App\"\n");
        assertThat(base.assembly()).isFalse();

        JkBuild min = JkBuildParser.withArtifactOverride(base, new JkBuildParser.ArtifactOverride(false, true));
        assertThat(min.minified()).isTrue();
        assertThat(min.assembly()).isTrue();
        assertThat(min.pluginConfig("minified")).isPresent();

        assertThat(JkBuildParser.parseArtifactOverride("fat"))
                .isEqualTo(new JkBuildParser.ArtifactOverride(true, false));
        assertThat(JkBuildParser.parseArtifactOverride("minified"))
                .isEqualTo(new JkBuildParser.ArtifactOverride(true, true));
        assertThat(JkBuildParser.parseArtifactOverride("")).isNull();
    }

    @Test
    void artifact_override_fat_strips_the_minified_plugin() {
        JkBuild min = JkBuildParser.parse(PROJECT + "\n[application]\nmain = \"demo.App\"\nminified = true\n");
        assertThat(min.pluginConfig("minified")).isPresent();

        JkBuild fat = JkBuildParser.withArtifactOverride(min, new JkBuildParser.ArtifactOverride(true, false));
        assertThat(fat.assembly()).isTrue();
        assertThat(fat.minified()).isFalse();
        assertThat(fat.pluginConfig("minified")).isEmpty();
    }

    @Test
    void native_enabled_modes() {
        assertThat(JkBuildParser.parse(PROJECT).nativeMode()).isEqualTo(JkBuild.NativeMode.DISABLED);
        // Table presence == enabled true
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\n").nativeMode()).isEqualTo(JkBuild.NativeMode.SUPPORTED);
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\nenabled = true\n").nativeMode())
                .isEqualTo(JkBuild.NativeMode.SUPPORTED);
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\nenabled = false\n")
                        .nativeMode())
                .isEqualTo(JkBuild.NativeMode.DISABLED);
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\nenabled = \"always\"\n")
                        .nativeMode())
                .isEqualTo(JkBuild.NativeMode.ALWAYS);
        // Legacy always = true
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\nalways = true\n").nativeMode())
                .isEqualTo(JkBuild.NativeMode.ALWAYS);
    }

    @Test
    void native_main_class_key_was_renamed() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [native]
                main-class = "com.example.NativeMain"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[native].main-class")
                .hasMessageContaining("use main");
    }

    @Test
    void native_config_fields_parsed() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [native]
                enabled    = "always"
                main = "com.example.NativeMain"
                name       = "myapp"
                args       = ["-O3", "--gc=serial"]
                """);
        assertThat(parsed.nativeConfig()).isPresent();
        JkBuild.NativeConfig nc = parsed.nativeConfig().orElseThrow();
        assertThat(nc.mainClass()).isEqualTo("com.example.NativeMain");
        assertThat(nc.name()).isEqualTo("myapp");
        assertThat(nc.args()).containsExactly("-O3", "--gc=serial");
        assertThat(nc.enabled()).isEqualTo(JkBuild.NativeMode.ALWAYS);
        assertThat(nc.always()).isTrue();
        assertThat(nc.graal()).isEqualTo("graalvm"); // defaulted — no graal key given
    }

    @Test
    void native_enabled_false_keeps_table_but_disables_native_command() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [native]
                enabled = false
                name = "myapp"
                """);
        assertThat(parsed.nativeConfig()).isPresent();
        assertThat(parsed.nativeMode()).isEqualTo(JkBuild.NativeMode.DISABLED);
        assertThat(parsed.nativeImage()).isFalse();
        assertThat(parsed.nativeConfig().orElseThrow().name()).isEqualTo("myapp");
    }

    @Test
    void compact_key_is_inert() {
        // `compact` is no longer supported; a stray one in an old jk.toml has no effect.
        JkBuild parsed = JkBuildParser.parse(PROJECT + "compact = true\n");
        assertThat(parsed.project().name()).isEqualTo("widget");
    }

    @Test
    void layout_defaults_to_auto_and_accepts_override() {
        assertThat(JkBuildParser.parse(PROJECT).project().layout()).isEqualTo(JkBuild.Layout.AUTO);
        assertThat(JkBuildParser.parse(PROJECT + "layout = \"simple\"\n")
                        .project()
                        .layout())
                .isEqualTo(JkBuild.Layout.SIMPLE);
        assertThat(JkBuildParser.parse(PROJECT + "layout = \"traditional\"\n")
                        .project()
                        .layout())
                .isEqualTo(JkBuild.Layout.TRADITIONAL);
        assertThat(JkBuildParser.parse(PROJECT + "layout = \"auto\"\n")
                        .project()
                        .layout())
                .isEqualTo(JkBuild.Layout.AUTO);
    }

    @Test
    void layout_rejects_unknown_value() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "layout = \"mill\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("layout must be");
    }

    @Test
    void sources_mode_parsed() {
        assertThat(JkBuildParser.parse(PROJECT).project().sourcesMode())
                .isEqualTo(cc.jumpkick.model.JkBuild.SourcesMode.DISABLED);
        assertThat(JkBuildParser.parse(PROJECT + "sources = true\n").project().sourcesMode())
                .isEqualTo(cc.jumpkick.model.JkBuild.SourcesMode.PUBLISH);
        assertThat(JkBuildParser.parse(PROJECT + "sources = \"always\"\n")
                        .project()
                        .sourcesMode())
                .isEqualTo(cc.jumpkick.model.JkBuild.SourcesMode.ALWAYS);
        assertThat(JkBuildParser.parse(PROJECT + "sources = false\n").project().sourcesMode())
                .isEqualTo(cc.jumpkick.model.JkBuild.SourcesMode.DISABLED);
    }

    @Test
    void m2integration_defaults_true_explicit_false_opts_out() {
        assertThat(JkBuildParser.parse(PROJECT).project().m2integration()).isTrue();
        assertThat(JkBuildParser.parse(PROJECT + "m2integration = true\n")
                        .project()
                        .m2integration())
                .isTrue();
        assertThat(JkBuildParser.parse(PROJECT + "m2integration = false\n")
                        .project()
                        .m2integration())
                .isFalse();
    }

    // ── splitEmbeddedUrl unit tests ──────────────────────────────────────────

    @Test
    void split_url_no_embedded() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isNull();
    }

    @Test
    void split_url_at_ref_only() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo@main");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isEqualTo("@main");
    }

    @Test
    void split_url_hash_ref_only() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo#8f3a1b");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_ref_then_subdir() {
        var p = JkBuildParser.splitEmbeddedUrl("git://github.com/user/repo@v1.2.3!components/auth");
        assertThat(p.baseUrl()).isEqualTo("git://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("@v1.2.3");
    }

    @Test
    void split_url_subdir_then_ref() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo!components/auth@v1.2.3");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("@v1.2.3");
    }

    @Test
    void split_url_hash_then_subdir() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo#8f3a1b!components/auth");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_subdir_then_hash() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo!components/auth#8f3a1b");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_git_at_host_not_confused_for_ref() {
        // "git@github.com" is userinfo, not an embedded ref
        var p = JkBuildParser.splitEmbeddedUrl("git@github.com:user/repo");
        assertThat(p.baseUrl()).isEqualTo("git@github.com:user/repo");
        assertThat(p.refSpec()).isNull();
    }

    @Test
    void dev_and_test_dev_scope_tables_parse() {
        // Dev-loop scopes (spring-boot plan §3.2): run-only / run+test, never packaged.
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [dev-dependencies]
                devtools = { group = "org.springframework.boot", name = "spring-boot-devtools", version = "4.0.0" }

                [test-dev-dependencies]
                testcontainers = { group = "org.springframework.boot", name = "spring-boot-testcontainers", version = "4.0.0" }
                """);
        assertThat(b.dependencies().of(cc.jumpkick.model.Scope.DEV)).hasSize(1);
        assertThat(b.dependencies().of(cc.jumpkick.model.Scope.DEV).get(0).module())
                .isEqualTo("org.springframework.boot:spring-boot-devtools");
        assertThat(b.dependencies().of(cc.jumpkick.model.Scope.TEST_DEV)).hasSize(1);
    }

    @Test
    void versionless_dep_with_group_parses_as_platform_managed() {
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.0" }

                [dependencies]
                starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }
                """);
        var dep = b.dependencies().of(cc.jumpkick.model.Scope.MAIN).get(0);
        assertThat(dep.isPlatformManaged()).isTrue();
        assertThat(dep.module()).isEqualTo("org.springframework.boot:spring-boot-starter-webmvc");
    }

    @Test
    void versionless_dep_without_group_still_errors() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [dependencies]
                mystery = { optional = false }
                """)).hasMessageContaining("must set exactly one of");
    }

    @Test
    void runtime_and_platform_scope_tables_parse() {
        // [platform-dependencies] (BOM imports) and [runtime-dependencies] were previously
        // importer-only; hand-written manifests must be able to declare them (Boot BOM flow).
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.0" }

                [runtime-dependencies]
                postgres-jdbc = { group = "org.postgresql", name = "postgresql", version = "42.7.4" }
                """);
        assertThat(b.dependencies().of(cc.jumpkick.model.Scope.PLATFORM)).hasSize(1);
        assertThat(b.dependencies().of(cc.jumpkick.model.Scope.RUNTIME)).hasSize(1);
    }

    @Test
    void spring_boot_table_parses_and_auto_imports_the_bom() {
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "shop"
                version = "1.0"

                [spring-boot]
                version = "4.0.0"

                [dependencies]
                starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }
                """);
        assertThat(b.isSpringBoot()).isTrue();
        var sb = b.pluginConfig(JkBuild.SPRING_BOOT_ID).orElseThrow();
        assertThat(sb.string("version")).isEqualTo("4.0.0");
        assertThat(sb.bool("build-info", false)).isFalse();
        assertThat(sb.bool("include-tools", true)).isTrue();
        assertThat(sb.bool("aot")).isEmpty(); // unset aot = tri-state auto (follows [native] presence)
        // version = "4.0.0" alone imports the BOM — no [platform-dependencies] boilerplate.
        var platform = b.dependencies().of(cc.jumpkick.model.Scope.PLATFORM);
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).module()).isEqualTo("org.springframework.boot:spring-boot-dependencies");
        assertThat(platform.get(0).version().raw()).isEqualTo("4.0.0");
        assertThat(platform.get(0).version()).isInstanceOf(cc.jumpkick.model.VersionSelector.Caret.class);
        // ...which makes the versionless starter platform-managed.
        assertThat(b.dependencies().of(cc.jumpkick.model.Scope.MAIN).get(0).isPlatformManaged())
                .isTrue();
    }

    @Test
    void spring_boot_table_requires_a_version() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [spring-boot]
                build-info = true
                """))
                .hasMessageContaining("[spring-boot].version is required");
    }

    @Test
    void spring_boot_options_parse() {
        JkBuild b = JkBuildParser.parse(PROJECT + """

                [spring-boot]
                version = "4.0.0"
                aot = true
                build-info = true
                include-tools = false
                aot-args = ["--spring.profiles.active=prod"]
                """);
        var sb = b.pluginConfig(JkBuild.SPRING_BOOT_ID).orElseThrow();
        assertThat(sb.bool("aot")).contains(true); // explicit aot wins over [native] absence
        assertThat(sb.bool("build-info", false)).isTrue();
        assertThat(sb.bool("include-tools", true)).isFalse();
        assertThat(sb.stringList("aot-args")).containsExactly("--spring.profiles.active=prod");
    }

    @Test
    void spring_boot_bom_is_not_duplicated_when_user_declares_it() {
        // A deliberate [platform-dependencies] spring-boot-dependencies entry wins
        // the auto-import must not add a second (conflicting) BOM row.
        JkBuild b = JkBuildParser.parse(PROJECT + """

                [spring-boot]
                version = "4.0.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.1" }
                """);
        var platform = b.dependencies().of(cc.jumpkick.model.Scope.PLATFORM);
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).version().raw()).isEqualTo("4.0.1");
    }

    /**
     * the parse memo must hold one entry per file. Keying it by (path, size, mtime) made
     * every save of a jk.toml strand the previous JkBuild for the process's lifetime.
     *
     * <p>The memo is process-wide, so other tests may already hold entries — assert rewrites do not
     * grow the cache, not that size is exactly 1.
     */
    @Test
    void parse_memo_replaces_the_entry_for_a_rewritten_file(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("jk.toml");
        Files.writeString(file, PROJECT);
        JkBuild first = JkBuildParser.parseLocal(file);
        assertThat(JkBuildParser.parseLocal(file)).isSameAs(first); // warm hit
        int sizeAfterFirst = JkBuildParser.parseCacheSizeForTest();

        for (int i = 0; i < 20; i++) {
            Files.writeString(file, PROJECT + "\n# edit " + i + "\n");
            // Distinct mtime so the stamp really moves on every rewrite.
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L + i * 1000L));
            JkBuildParser.parseLocal(file);
        }
        assertThat(JkBuildParser.parseCacheSizeForTest())
                .as("one entry per file, not one per revision")
                .isEqualTo(sizeAfterFirst);
    }

    @Test
    void micronaut_table_imports_platform_bom_with_caret_version() {
        var b = JkBuildParser.parse(PROJECT + """
                [micronaut]
                version = "5"

                [dependencies]
                micronaut-http-server-netty = { group = "io.micronaut", name = "micronaut-http-server-netty" }
                """);
        assertThat(b.isMicronaut()).isTrue();
        var platform = b.dependencies().of(cc.jumpkick.model.Scope.PLATFORM);
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).module()).isEqualTo("io.micronaut.platform:micronaut-platform");
        assertThat(platform.get(0).version().raw()).isEqualTo("5");
        assertThat(platform.get(0).version()).isInstanceOf(cc.jumpkick.model.VersionSelector.Caret.class);
        assertThat(b.dependencies().of(cc.jumpkick.model.Scope.MAIN).get(0).isPlatformManaged())
                .isTrue();
    }
}
