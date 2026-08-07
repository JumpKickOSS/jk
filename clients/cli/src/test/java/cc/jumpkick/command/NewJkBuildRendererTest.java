// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.scaffold.NewJkBuildRenderer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
                Optional.empty(),
                Optional.of("com.example.Main"),
                false,
                false,
                NewInputs.Language.JAVA,
                "simple",
                Optional.empty(),
                List.of("guava", "com.google.code.gson:gson"),
                true,
                Path.of("/tmp/demo"));
        String toml = NewJkBuildRenderer.render(inputs);
        assertThat(toml).contains("[dependencies]");
        assertThat(toml).contains("guava");
        assertThat(toml).contains("gson");
    }
}
