// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin's action key has one renderer for its declared inputs and one for the project facts.
 * Both are pinned here because the defect they replaced is silent: an input or a fact that reaches
 * a plugin body without reaching its key restores a stale artifact and reports it as up-to-date.
 */
class PluginActionKeyTokensTest {

    private static final String TOML = """
            name = "demo"
            group = "com.demo"
            version = "0.1.0"
            java = 25
            """;

    /**
     * Every kind in the closed {@code In} vocabulary is fingerprinted. The switch is exhaustive at
     * compile time; this pins that no arm renders nothing, which would drop the input from the key.
     */
    @Test
    void every_declared_input_kind_renders_a_token(@TempDir Path tmp) throws Exception {
        PlannerPlugin.InputSources src = sources(tmp);
        for (In.Kind kind : In.Kind.values()) {
            assertThat(PlannerPlugin.declaredInputTokens(List.of(wire(kind)), src))
                    .as("declared input %s", wire(kind))
                    .isNotEmpty();
        }
    }

    /**
     * The runtime view has one spelling. The step arm said {@code cp:} and the packager arm said
     * {@code libs:} for the same declared input, so the two arms keyed the same dependency set two
     * ways.
     */
    @Test
    void the_runtime_view_has_one_prefix_and_containers_ride_with_entries(@TempDir Path tmp) throws Exception {
        PlannerPlugin.InputSources src = sources(tmp);

        List<String> classpathOnly =
                PlannerPlugin.declaredInputTokens(List.of(In.runtimeClasspath().wireName()), src);
        assertThat(classpathOnly).hasSize(1);
        assertThat(classpathOnly.get(0)).startsWith("cp:");

        assertThat(PlannerPlugin.declaredInputTokens(List.of(In.runtimeEntries().wireName()), src))
                .anySatisfy(token -> assertThat(token).startsWith("cp:"))
                .anySatisfy(token -> assertThat(token).startsWith("container:dep-1.0.aar:"));
    }

    /** An input the engine cannot fingerprint is refused, not skipped — skipping it is a stale key. */
    @Test
    void an_input_the_engine_cannot_fingerprint_is_refused(@TempDir Path tmp) throws Exception {
        PlannerPlugin.InputSources src = sources(tmp);
        assertThatThrownBy(() -> PlannerPlugin.declaredInputTokens(List.of("secret-sauce"), src))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Every fact a plugin body receives changes the key. {@code nativeDeclared} and {@code kotlin}
     * used to reach the body and not the step key, so a project gaining a {@code [native]} table
     * restored the non-native artifact.
     */
    @Test
    void the_facts_token_moves_for_every_fact_a_plugin_body_sees() {
        ProjectFacts base =
                new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Main", false, false, Map.of("K", "v"));
        assertThat(List.of(
                        base.token(),
                        new ProjectFacts("other", "demo", "0.1.0", 25, "ex.Main", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "other", "0.1.0", 25, "ex.Main", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.2.0", 25, "ex.Main", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 21, "ex.Main", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Other", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Main", true, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Main", false, true, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Main", false, false, Map.of("K", "w"))
                                .token()))
                .as("one distinct token per ProjectFacts component")
                .doesNotHaveDuplicates()
                .hasSize(ProjectFacts.class.getRecordComponents().length + 1);
    }

    /**
     * Adding a component to {@link ProjectFacts} must add it to {@link ProjectFacts#token()} in the
     * same change — this is the assertion that fails when it does not.
     */
    @Test
    void the_facts_token_covers_every_projectfacts_component() {
        assertThat(ProjectFacts.class.getRecordComponents())
                .as("render any new ProjectFacts component in token(), then widen the sweep above")
                .hasSize(8);
    }

    /** The [native] table and a kotlin version reach the body through facts(), so they key it. */
    @Test
    void a_declared_native_table_and_kotlin_change_the_facts_token() {
        JkBuild plain = JkBuildParser.parse(TOML);
        JkBuild nativeDeclared = JkBuildParser.parse(TOML + "\n[native]\nenabled = \"always\"\n");
        JkBuild kotlin = JkBuildParser.parse(TOML + "\nkotlin = \"2.1.0\"\n");

        assertThat(PluginBuild.facts(nativeDeclared, "ex.Main").token())
                .isNotEqualTo(PluginBuild.facts(plain, "ex.Main").token());
        assertThat(PluginBuild.facts(kotlin, "ex.Main").token())
                .isNotEqualTo(PluginBuild.facts(plain, "ex.Main").token());
    }

    private static PlannerPlugin.InputSources sources(Path tmp) throws Exception {
        JkBuild project = JkBuildParser.parse(TOML);
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(classes.resolve("App.class"), "fake");
        Path jar = Files.writeString(tmp.resolve("dep-1.0.jar"), "dep");
        Path exploded = Files.createDirectories(tmp.resolve("exploded"));
        Files.writeString(exploded.resolve("AndroidManifest.xml"), "<manifest/>");
        PluginBuild.ProdEntry entry = new PluginBuild.ProdEntry("dep-1.0.aar", jar, false, exploded);
        return new PlannerPlugin.InputSources(
                classes,
                List.of(jar),
                List.of(entry),
                new PluginConfig("fake", Map.of("enabled", Boolean.TRUE)),
                BuildLayout.of(tmp, project),
                tmp);
    }

    private static String wire(In.Kind kind) {
        return switch (kind) {
            case CLASSES, RUNTIME_CLASSPATH, RUNTIME_ENTRIES, CONFIG -> new In(kind, null).wireName();
            case STEP_OUTPUT -> In.stepOutput("aot").wireName();
            case PROJECT_FILES -> In.projectFiles("src/main/res").wireName();
        };
    }
}
