// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Layout;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.scaffold.NewJkBuildRenderer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewJkBuildRendererTest {

    @Test
    void catalog_and_gav_deps_render_into_dependencies() throws IOException {
        NewInputs inputs = new NewInputs(
                "com.example",
                "demo",
                "25",
                25,
                25,
                null,
                "com.example.Main",
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.SIMPLE,
                null,
                List.of("guava", "com.google.code.gson:gson", "com.acme:widget"),
                true,
                Path.of("/tmp/demo"));
        String toml = NewJkBuildRenderer.render(inputs, NewScaffolder.VERSIONS);
        assertThat(toml).contains("[dependencies]");
        // A catalog name — spelled as a name or as its coordinate — is the one-liner; a coordinate
        // the catalog does not know is the GAV string. All carry the number the repositories
        // reported, never `latest`.
        assertThat(toml).contains("guava = \"1.2.3\"");
        assertThat(toml).contains("gson = \"1.2.3\"");
        assertThat(toml).contains("widget = \"com.acme:widget:1.2.3\"");
        assertThat(toml).doesNotContain("latest");
        assertThat(toml).doesNotContain("layout");
    }

    @Test
    void an_explicit_version_in_a_pick_is_written_as_given() throws IOException {
        NewInputs inputs = new NewInputs(
                "com.example",
                "demo",
                "25",
                25,
                25,
                null,
                null,
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                null,
                List.of("com.acme:widget:2.0.0", "com.google.code.gson:gson:3.0.0"),
                true,
                Path.of("/tmp/demo"));
        String toml = NewJkBuildRenderer.render(inputs, (group, artifact) -> {
            throw new IOException("nothing to look up");
        });
        assertThat(toml).contains("widget = \"com.acme:widget:2.0.0\"");
        assertThat(toml).contains("gson = \"3.0.0\"");
    }

    @Test
    void kotlin_compiler_version_is_the_newest_stable_number() throws IOException {
        NewInputs inputs = new NewInputs(
                "com.example",
                "demo",
                "25",
                25,
                25,
                null,
                "com.example.MainKt",
                false,
                false,
                NewInputs.Language.KOTLIN,
                Layout.TRADITIONAL,
                null,
                List.of(),
                true,
                Path.of("/tmp/demo"));
        String toml = NewJkBuildRenderer.render(inputs, (group, artifact) -> {
            assertThat(group + ":" + artifact).isEqualTo("org.jetbrains.kotlin:kotlin-compiler-embeddable");
            return "2.4.20";
        });
        assertThat(toml).contains("kotlin   = \"2.4.20\"");
        JkBuild parsed = JkBuildParser.parse(toml);
        assertThat(parsed.project().kotlin()).isEqualTo(VersionSelector.parse("2.4.20"));
    }

    @Test
    void an_unreachable_repository_fails_the_render() throws IOException {
        NewInputs inputs = new NewInputs(
                "com.example",
                "demo",
                "25",
                25,
                25,
                null,
                null,
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                null,
                List.of("guava"),
                true,
                Path.of("/tmp/demo"));
        assertThatThrownBy(() -> NewJkBuildRenderer.render(inputs, (group, artifact) -> {
                    throw new IOException("could not look up the current version of " + group + ":" + artifact);
                }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("com.google.guava:guava");
    }

    @Test
    void traditional_layout_is_omitted_from_toml() throws IOException {
        NewInputs inputs = new NewInputs(
                "com.example",
                "demo",
                "25",
                25,
                25,
                null,
                null,
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                null,
                List.of(),
                true,
                Path.of("/tmp/demo"));
        String toml = NewJkBuildRenderer.render(inputs, NewScaffolder.VERSIONS);
        assertThat(toml).doesNotContain("layout");
    }

    @Test
    void blank_layout_is_omitted_from_toml() throws IOException {
        NewInputs inputs = new NewInputs(
                "com.example",
                "demo",
                "25",
                25,
                25,
                null,
                null,
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                null,
                List.of(),
                true,
                Path.of("/tmp/demo"));
        String toml = NewJkBuildRenderer.render(inputs, NewScaffolder.VERSIONS);
        assertThat(toml).doesNotContain("layout");
    }

    @Test
    void scala_lang_emits_scala_selector() throws IOException {
        NewInputs inputs = new NewInputs(
                "com.example",
                "demo",
                "25",
                25,
                25,
                null,
                "com.example.Main",
                false,
                false,
                NewInputs.Language.SCALA,
                Layout.TRADITIONAL,
                null,
                List.of(),
                true,
                Path.of("/tmp/demo"));
        String toml = NewJkBuildRenderer.render(inputs, NewScaffolder.VERSIONS);
        assertThat(toml).contains("scala    = \"1.2.3\"");
        assertThat(toml).doesNotContain("java     =");
    }

    /**
     * Render → parse, unchanged. Values are TOML-quoted, so a group or main class carrying a
     * quote or a backslash — a Windows-style class path in {@code main} — still round-trips.
     */
    @Test
    void a_metacharacter_bearing_value_round_trips_through_the_renderer() throws IOException {
        String group = "com.ex\"a\\mple";
        String main = "com.example.Main\tWeird";
        NewInputs inputs = new NewInputs(
                group,
                "demo",
                "25",
                25,
                25,
                null,
                main,
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.SIMPLE,
                null,
                List.of(),
                true,
                Path.of("/tmp/demo"));
        JkBuild parsed = JkBuildParser.parse(NewJkBuildRenderer.render(inputs, NewScaffolder.VERSIONS));
        assertThat(parsed.project().group()).isEqualTo(group);
        assertThat(parsed.mainClass()).isEqualTo(main);
    }
}
