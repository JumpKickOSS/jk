// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Layout;
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.scaffold.NewJkBuildRenderer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewJkBuildRendererTest {

    @Test
    void catalog_and_gav_deps_render_into_dependencies() {
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
                List.of("guava", "com.google.code.gson:gson"),
                true,
                Path.of("/tmp/demo"));
        String toml = NewJkBuildRenderer.render(inputs);
        assertThat(toml).contains("[dependencies]");
        assertThat(toml).contains("guava");
        assertThat(toml).contains("gson");
        assertThat(toml).doesNotContain("layout");
    }

    @Test
    void traditional_layout_is_omitted_from_toml() {
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
        String toml = NewJkBuildRenderer.render(inputs);
        assertThat(toml).doesNotContain("layout");
    }

    @Test
    void blank_layout_is_omitted_from_toml() {
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
        String toml = NewJkBuildRenderer.render(inputs);
        assertThat(toml).doesNotContain("layout");
    }

    @Test
    void scala_lang_emits_scala_selector() {
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
        String toml = NewJkBuildRenderer.render(inputs);
        assertThat(toml).contains("scala    = \"latest\"");
        assertThat(toml).doesNotContain("java     =");
    }

    /**
     * Render → parse, unchanged. Values are TOML-quoted, so a group or main class carrying a
     * quote or a backslash — a Windows-style class path in {@code main} — still round-trips.
     */
    @Test
    void a_metacharacter_bearing_value_round_trips_through_the_renderer() {
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
        JkBuild parsed = JkBuildParser.parse(NewJkBuildRenderer.render(inputs));
        assertThat(parsed.project().group()).isEqualTo(group);
        assertThat(parsed.mainClass()).isEqualTo(main);
    }
}
