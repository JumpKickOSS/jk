// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Layout;
import cc.jumpkick.model.SourcesMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkBuildParserApplicationTest {

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
        assertThat(parsed.applicationOpt().orElseThrow().nativeImage()).isTrue();
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
        assertThat(parsed.nativeConfigOpt()).isPresent();
        JkBuild.NativeConfig nc = parsed.nativeConfigOpt().orElseThrow();
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
        assertThat(parsed.nativeConfigOpt()).isPresent();
        assertThat(parsed.nativeMode()).isEqualTo(JkBuild.NativeMode.DISABLED);
        assertThat(parsed.nativeImage()).isFalse();
        assertThat(parsed.nativeConfigOpt().orElseThrow().name()).isEqualTo("myapp");
    }

    @Test
    void native_name_strips_a_trailing_exe() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [native]
                name = "jk.exe"
                """);
        assertThat(parsed.nativeConfigOpt().orElseThrow().name()).isEqualTo("jk");
        parsed = JkBuildParser.parse(PROJECT + """

                [native]
                name = "JK.EXE"
                """);
        assertThat(parsed.nativeConfigOpt().orElseThrow().name()).isEqualTo("JK");
    }

    @Test
    void compact_key_is_inert() {
        // `compact` is no longer supported; a stray one in an old jk.toml has no effect.
        JkBuild parsed = JkBuildParser.parse(PROJECT + "compact = true\n");
        assertThat(parsed.project().name()).isEqualTo("widget");
    }

    @Test
    void layout_defaults_to_auto_and_accepts_override() {
        assertThat(JkBuildParser.parse(PROJECT).project().layout()).isEqualTo(Layout.AUTO);
        assertThat(JkBuildParser.parse(PROJECT + "layout = \"simple\"\n")
                        .project()
                        .layout())
                .isEqualTo(Layout.SIMPLE);
        assertThat(JkBuildParser.parse(PROJECT + "layout = \"traditional\"\n")
                        .project()
                        .layout())
                .isEqualTo(Layout.TRADITIONAL);
        assertThat(JkBuildParser.parse(PROJECT + "layout = \"auto\"\n")
                        .project()
                        .layout())
                .isEqualTo(Layout.AUTO);
    }

    @Test
    void layout_rejects_unknown_value() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "layout = \"mill\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("layout must be");
    }

    @Test
    void sources_mode_parsed() {
        assertThat(JkBuildParser.parse(PROJECT).project().sourcesMode()).isEqualTo(SourcesMode.DISABLED);
        assertThat(JkBuildParser.parse(PROJECT + "sources = true\n").project().sourcesMode())
                .isEqualTo(SourcesMode.PUBLISH);
        assertThat(JkBuildParser.parse(PROJECT + "sources = \"always\"\n")
                        .project()
                        .sourcesMode())
                .isEqualTo(SourcesMode.ALWAYS);
        assertThat(JkBuildParser.parse(PROJECT + "sources = false\n").project().sourcesMode())
                .isEqualTo(SourcesMode.DISABLED);
    }

    @Test
    void m2_table_defaults_true_explicit_false_opts_out() {
        assertThat(JkBuildParser.parse(PROJECT).project().m2integration()).isTrue();
        assertThat(JkBuildParser.parse(PROJECT).project().m2install()).isTrue();
        assertThat(JkBuildParser.parse(PROJECT + "\n[m2]\nintegration = true\n")
                        .project()
                        .m2integration())
                .isTrue();
        assertThat(JkBuildParser.parse(PROJECT + "\n[m2]\nintegration = false\n")
                        .project()
                        .m2integration())
                .isFalse();
        JkBuild both = JkBuildParser.parse(PROJECT + "\n[m2]\nintegration = true\ninstall = false\n");
        assertThat(both.project().m2integration()).isTrue();
        assertThat(both.project().m2install()).isFalse();
    }

    @Test
    void flat_m2integration_key_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "m2integration = false\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[m2]");
    }
}
