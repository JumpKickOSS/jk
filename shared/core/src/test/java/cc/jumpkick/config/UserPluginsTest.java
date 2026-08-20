// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.PluginDeclaration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserPluginsTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void missing_file_is_empty() {
        assertThat(UserPlugins.fromConfig(Path.of("no-such-config.toml"))).isEmpty();
    }

    @Test
    void missing_plugins_table_is_empty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, "[engine]\njobs = 4\n");
        assertThat(UserPlugins.fromConfig(file)).isEmpty();
    }

    @Test
    void missing_sha256_is_an_error(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [plugins]
                spring-boot = { path = "/tmp/boot.jar" }
                """);
        assertThatThrownBy(() -> UserPlugins.fromConfig(file))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("sha256");
    }

    @Test
    void merge_later_alias_replaces(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [plugins]
                spring-boot = { path = "/tmp/from-config.jar", sha256 = "%s" }
                extra = { group = "com.acme", name = "extra", version = "1.0.0", sha256 = "%s" }
                """.formatted(SHA, SHA));
        List<PluginDeclaration> config = UserPlugins.fromConfig(file);
        List<PluginDeclaration> project = List.of(new PluginDeclaration(
                "spring-boot", "path", "spring-boot", "local", "/tmp/from-project.jar", SHA, Map.of()));
        List<PluginDeclaration> merged = UserPlugins.merge(config, project);
        assertThat(merged).hasSize(2);
        PluginDeclaration boot = merged.stream()
                .filter(d -> d.alias().equals("spring-boot"))
                .findFirst()
                .orElseThrow();
        assertThat(boot.path()).isEqualTo("/tmp/from-project.jar");
        assertThat(merged.stream().map(PluginDeclaration::alias)).contains("extra", "spring-boot");
    }
}
