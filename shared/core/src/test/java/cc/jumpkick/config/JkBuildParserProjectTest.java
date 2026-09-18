// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.DebugInfo;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.model.VersionSelector;
import org.junit.jupiter.api.Test;

class JkBuildParserProjectTest {

    @Test
    void m2_workspace_true_cannot_combine_with_explicit_keys() {
        // : silently returning (true,true) would discard the explicit integration = false.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [m2]
                        workspace = true
                        integration = false
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace = true");
    }

    @Test
    void m2_must_be_a_table() {
        // : a scalar `m2` must be a clean parse error, not a raw tomlj type exception.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "m2 = \"yes\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be a table");
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
        assertThat(parsed.build().platformPolicy()).isEqualTo(PlatformPolicy.FLOOR);
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
        assertThat(parsed.build().unmappedPolicy()).isEqualTo(UnmappedPolicy.STRICT);
        // Default is mediate.
        assertThat(JkBuildParser.parse(PROJECT).build().unmappedPolicy()).isEqualTo(UnmappedPolicy.MEDIATE);
    }

    @Test
    void resolve_pins_parses_and_defaults_to_exact() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [resolve]
                pins = "nearest"
                """);
        assertThat(parsed.build().pinPolicy()).isEqualTo(PinPolicy.NEAREST);
        assertThat(JkBuildParser.parse(PROJECT).build().pinPolicy()).isEqualTo(PinPolicy.EXACT);
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                        [resolve]
                        pins = "furthest"
                        """)).hasMessageContaining("[resolve].pins");
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
        assertThat(parsed.project().groovy())
                .isInstanceOfSatisfying(VersionSelector.Exact.class, exact -> assertThat(exact.version())
                        .isEqualTo("5.0.4"));
    }

    @Test
    void bare_groovy_version_pins_like_a_dependency() {
        JkBuild parsed = JkBuildParser.parse(PROJECT.replace("java     = 25", "groovy   = \"5.0.4\""));
        assertThat(parsed.project().isGroovy()).isTrue();
        assertThat(parsed.project().groovy()).isEqualTo(new VersionSelector.Exact("5.0.4", "5.0.4"));
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
        assertThat(parsed.project().scala())
                .isInstanceOfSatisfying(VersionSelector.Exact.class, exact -> assertThat(exact.version())
                        .isEqualTo("3.8.4"));
    }

    @Test
    void caret_scala_version_floats_within_the_line() {
        JkBuild parsed = JkBuildParser.parse(PROJECT.replace("java     = 25", "scala    = \"^3\""));
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
        assertThat(parsed.build()).isEqualTo(BuildBlock.EMPTY);
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
    void build_debug_defaults_to_full_and_names_a_level() {
        assertThat(JkBuildParser.parse(PROJECT).build().debug()).isEqualTo(DebugInfo.FULL);
        JkBuild lines = JkBuildParser.parse(PROJECT + """

                [build]
                debug = "lines"
                """);
        assertThat(lines.build().debug()).isEqualTo(DebugInfo.LINES);
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                        [build]
                        debug = "vars"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[build].debug")
                .hasMessageContaining("full, lines or none");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                        [build]
                        debug = true
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[build].debug must be a string");
    }

    @Test
    void build_rejects_an_unknown_key() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                        [build]
                        debbug = "full"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[build] unknown key `debbug`")
                .hasMessageContaining("debug");
    }

    @Test
    void parses_build_test_worker_jars_as_order_after() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [build]
                test-plugin-jars = ["publisher", "image-builder"]
                """);
        assertThat(parsed.build().testPluginJars()).containsExactly("publisher", "image-builder");
        // test-plugin-jars modules must build first → they're order-after prerequisites
        assertThat(parsed.build().allOrderAfter()).contains("publisher", "image-builder");
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

    /**
     * A pin of 0 is "auto", and auto is the build's share: the request arrives with the share
     * already resolved, and a module spelling the default out loud must not escape it and shard
     * across the whole machine while the ETA prices it on the share.
     */
    @Test
    void a_zero_pin_takes_the_builds_share_and_a_positive_pin_wins() {
        BuildBlock zero = JkBuildParser.parse(PROJECT + """

                [test]
                workers = 0
                """).build();
        assertThat(zero.effectiveTestWorkers(6)).isEqualTo(6);
        assertThat(zero.effectiveTestWorkers(0)).isEqualTo(0);
        BuildBlock pinned = JkBuildParser.parse(PROJECT + """

                [test]
                workers = 3
                """).build();
        assertThat(pinned.effectiveTestWorkers(6)).isEqualTo(3);
        assertThat(JkBuildParser.parse(PROJECT).build().effectiveTestWorkers(6)).isEqualTo(6);
    }

    @Test
    void parses_test_fixtures_true_as_the_default_root() {
        assertThat(JkBuildParser.parse(PROJECT).build().hasFixtures()).isFalse();
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [test]
                fixtures = true
                """);
        assertThat(parsed.build().hasFixtures()).isTrue();
        assertThat(parsed.build().fixtures()).isEqualTo(BuildBlock.DEFAULT_FIXTURES);
    }

    @Test
    void parses_test_fixtures_path_and_rejects_blank() {
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                fixtures = "src/helpers/java"
                """).build().fixtures()).isEqualTo("src/helpers/java");
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                fixtures = false
                """).build().hasFixtures()).isFalse();
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [test]
                fixtures = ""
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].fixtures");
    }

    @Test
    void parses_test_serial_tags() {
        assertThat(JkBuildParser.parse(PROJECT).build().testSerialTags()).isEmpty();
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                workers = 0
                serial-tags = ["integration", "slow"]
                """).build().testSerialTags()).containsExactly("integration", "slow");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [test]
                serial-tags = [1]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("serial-tags");
    }

    @Test
    void test_assertions_default_on_and_take_only_a_boolean() {
        assertThat(JkBuildParser.parse(PROJECT).build().testAssertions()).isTrue();
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                assertions = false
                """).build().testAssertions()).isFalse();
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [test]
                assertions = "off"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].assertions must be true or false");
    }

    @Test
    void test_coverage_is_off_unless_the_module_turns_every_run_into_a_coverage_run() {
        assertThat(JkBuildParser.parse(PROJECT).build().testCoverage()).isFalse();
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                coverage = true
                """).build().testCoverage()).isTrue();
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [test]
                coverage = "yes"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].coverage must be true or false");
    }

    @Test
    void parses_test_tools_as_names_on_path() {
        assertThat(JkBuildParser.parse(PROJECT).build().testTools()).isEmpty();
        assertThat(JkBuildParser.parse(PROJECT + """

                [test]
                tools = ["node", "git", "node"]
                """).build().testTools()).containsExactly("node", "git");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [test]
                tools = ["/usr/bin/node"]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].tools names an executable on PATH, not a path");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [test]
                tools = [1]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].tools must be an array of executable names");
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
}
