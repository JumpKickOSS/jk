// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [install]} — what installing a module produces besides its jar and POM.
 *
 * <p>Absent for every ordinary target, which is the whole point: a library, an executable, a native
 * binary, a script and an external jar are complete shapes and declare nothing.
 */
class ManifestInstallTest {

    @TempDir
    Path tmp;

    private JkBuild parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
        return JkBuildParser.parse(f);
    }

    @Test
    void an_ordinary_module_declares_nothing() throws Exception {
        assertThat(parse("").install()).isEmpty();
    }

    @Test
    void product_lib_is_read() throws Exception {
        assertThat(parse("[install]\nproduct-lib = \"jk-engine\"\n").install())
                .hasValueSatisfying(i -> assertThat(i.productLib()).isEqualTo("jk-engine"));
    }

    @Test
    void an_unknown_key_is_a_parse_error_not_a_silent_default() throws Exception {
        assertThatThrownBy(() -> parse("[install]\nproduct_lib = \"jk-engine\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("product-lib");
    }

    @Test
    void product_lib_accepts_only_the_engine_home_name() throws Exception {
        // EngineInstall hardcodes the jk-engine home, pointer and jar naming — any other value
        // would install under jk-engine/ while announcing a directory nothing wrote to.
        assertThatThrownBy(() -> parse("[install]\nproduct-lib = \"foo\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jk-engine");
    }

    @Test
    void install_must_be_a_table() throws Exception {
        assertThatThrownBy(() -> parse("install = \"jk-engine\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be a table");
    }
}
