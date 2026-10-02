// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [war]}: the table alone packages a war; {@code name} and {@code webapp} move it. */
class ManifestWarTest {

    @TempDir
    Path tmp;

    private JkBuild parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
        return JkBuildParser.parse(f);
    }

    @Test
    void absent_means_no_war() throws Exception {
        assertThat(parse("").build().war()).isNull();
    }

    @Test
    void an_empty_table_is_a_war_named_like_the_jar_from_src_main_webapp() throws Exception {
        JkBuild build = parse("[war]\n");
        BuildBlock.War war = Objects.requireNonNull(build.build().war());
        assertThat(war).isEqualTo(new BuildBlock.War(null, "src/main/webapp"));
        BuildLayout layout = BuildLayout.of(tmp, build);
        assertThat(layout.warFile(war)).isEqualTo(tmp.resolve("target/web-1.0.war"));
        assertThat(layout.explodedWarDir(war)).isEqualTo(tmp.resolve("target/web-1.0"));
    }

    @Test
    void name_is_the_file_name_without_war() throws Exception {
        JkBuild build = parse("[war]\nname = \"ROOT\"\nwebapp = \"web\"\n");
        BuildBlock.War war = Objects.requireNonNull(build.build().war());
        assertThat(war.webapp()).isEqualTo("web");
        assertThat(BuildLayout.of(tmp, build).warFile(war)).isEqualTo(tmp.resolve("target/ROOT.war"));
    }

    @Test
    void a_name_with_the_extension_or_a_webapp_outside_the_module_is_refused() {
        assertThatThrownBy(() -> parse("[war]\nname = \"ROOT.war\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("without .war");
        assertThatThrownBy(() -> parse("[war]\nwebapp = \"../web\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("inside the module");
        assertThatThrownBy(() -> parse("[war]\nroot = \"x\"\n")).isInstanceOf(JkBuildParseException.class);
    }
}
