// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1662: which optimizers AOT ran with is invisible in the build output, so a mistyped
 * {@code aot-config} must fail rather than quietly fall back to jk's defaults.
 */
class MicronautAotConfigTest {

    @Test
    void an_explicit_path_that_does_not_exist_fails_and_names_it(@TempDir Path dir) {
        assertThatThrownBy(() -> MicronautPlugin.userConfigFile(dir, "src/aot.propertis"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("aot-config")
                .hasMessageContaining("src/aot.propertis")
                .hasMessageContaining(dir.resolve("src/aot.propertis").toString());
    }

    @Test
    void an_explicit_path_that_is_a_directory_fails(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config"));

        assertThatThrownBy(() -> MicronautPlugin.userConfigFile(dir, "config")).isInstanceOf(IOException.class);
    }

    @Test
    void an_explicit_path_that_exists_is_used(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Path config = Files.writeString(dir.resolve("src/aot.properties"), "cached.environment.enabled=false\n");

        assertThat(MicronautPlugin.userConfigFile(dir, "src/aot.properties")).isEqualTo(config);
    }

    @Test
    void an_unset_key_picks_up_the_conventional_file(@TempDir Path dir) throws Exception {
        Path conventional = Files.writeString(dir.resolve("aot.properties"), "deduce.environment.enabled=false\n");

        assertThat(MicronautPlugin.userConfigFile(dir, "")).isEqualTo(conventional);
        assertThat(MicronautPlugin.userConfigFile(dir, null)).isEqualTo(conventional);
    }

    @Test
    void an_unset_key_with_no_conventional_file_takes_the_defaults(@TempDir Path dir) throws Exception {
        assertThat(MicronautPlugin.userConfigFile(dir, "  ")).isNull();
    }
}
