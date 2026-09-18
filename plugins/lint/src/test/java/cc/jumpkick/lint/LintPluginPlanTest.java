// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TaskSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code [lint]} table as the steps it registers: one per enabled tool, after compile, keyed on what it reads. */
class LintPluginPlanTest {

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new LintPlugin().manifest();
        assertThat(manifest.id()).isEqualTo("jk-lint");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKLINT:");
    }

    @Test
    void an_empty_table_enables_nothing() {
        assertThat(LintPlugin.enabled(new PluginConfig("lint", Map.of()))).isEmpty();
    }

    @Test
    void each_tool_is_enabled_by_its_own_key() {
        PluginConfig config = new PluginConfig(
                "lint",
                Map.of(
                        "checkstyle",
                        "config/checkstyle.xml",
                        "pmd",
                        List.of("category/java/bestpractices.xml"),
                        "spotbugs",
                        true,
                        "detekt",
                        true));
        assertThat(LintPlugin.enabled(config))
                .containsExactly(LintTool.CHECKSTYLE, LintTool.PMD, LintTool.SPOTBUGS, LintTool.DETEKT);
    }

    /** The Checkstyle step: the Java roots, the configuration and the classes dir are its key. */
    @Test
    void the_checkstyle_step_reads_the_sources_the_configuration_and_the_classes() {
        TaskSpec spec = LintPlugin.task(
                LintTool.CHECKSTYLE,
                new PluginConfig(
                        "lint",
                        Map.of(
                                "checkstyle",
                                "config/checkstyle.xml",
                                "sources",
                                List.of("src/main/java", "src/test/java"))));

        assertThat(spec.name()).isEqualTo("lint-checkstyle");
        assertThat(spec.declaredInputs())
                .containsExactly(
                        In.projectFiles("src/main/java"),
                        In.projectFiles("src/test/java"),
                        In.projectFiles("config/checkstyle.xml"),
                        In.classes(),
                        In.config());
        assertThat(spec.declaredOutputs()).containsExactly("lint/checkstyle");
        assertThat(spec.sourcesContributions()).isEmpty();
        assertThat(spec.body()).isNotNull();
    }

    /** A rule set at a URL is the engine's to fetch and key: no module file is declared for it. */
    @Test
    void a_checkstyle_rule_set_at_a_url_is_not_a_project_input() {
        TaskSpec spec = LintPlugin.task(
                LintTool.CHECKSTYLE,
                new PluginConfig("lint", Map.of("checkstyle", "https://example.com/build/checks.xml")));

        assertThat(spec.declaredInputs()).containsExactly(In.projectFiles("src/main/java"), In.classes(), In.config());
    }

    /** PMD's rulesets: a file in the module is an input, a built-in category is not. */
    @Test
    void the_pmd_step_keys_on_ruleset_files_but_not_built_in_categories() {
        TaskSpec spec = LintPlugin.task(
                LintTool.PMD,
                new PluginConfig("lint", Map.of("pmd", List.of("category/java/bestpractices.xml", "config/pmd.xml"))));

        assertThat(spec.name()).isEqualTo("lint-pmd");
        assertThat(spec.declaredInputs())
                .containsExactly(
                        In.projectFiles("src/main/java"), In.projectFiles("config/pmd.xml"), In.classes(), In.config());
    }

    /** SpotBugs analyses classes against the compile classpath. */
    @Test
    void the_spotbugs_step_reads_the_compile_classpath_too() {
        TaskSpec spec = LintPlugin.task(
                LintTool.SPOTBUGS,
                new PluginConfig("lint", Map.of("spotbugs", true, "spotbugs-exclude", "config/spotbugs-exclude.xml")));

        assertThat(spec.declaredInputs())
                .containsExactly(
                        In.projectFiles("src/main/java"),
                        In.projectFiles("config/spotbugs-exclude.xml"),
                        In.classes(),
                        In.compileClasspath(),
                        In.config());
        assertThat(spec.declaredOutputs()).containsExactly("lint/spotbugs");
    }

    /** detekt reads the Kotlin roots. */
    @Test
    void the_detekt_step_reads_the_kotlin_sources() {
        TaskSpec spec = LintPlugin.task(LintTool.DETEKT, new PluginConfig("lint", Map.of("detekt", true)));

        assertThat(spec.name()).isEqualTo("lint-detekt");
        assertThat(spec.declaredInputs())
                .containsExactly(In.projectFiles("src/main/kotlin"), In.classes(), In.config());
    }
}
