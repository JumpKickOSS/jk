// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    void a_file_that_does_not_parse_is_an_error_naming_its_position_not_no_pins(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [mirrors
                maven = "https://artifactory.acme.test/maven-dist/"
                """);
        GlobalConfig.clearCache();

        assertThatThrownBy(() -> UserPlugins.fromConfig(file))
                .isInstanceOfSatisfying(UserConfigParseException.class, e -> {
                    assertThat(e.file()).isEqualTo(file);
                    assertThat(e.line()).isEqualTo(1);
                    assertThat(e.getMessage()).contains("plugin pins");
                });
    }

    @Test
    void a_broken_file_with_pins_is_an_error_too(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [plugins]
                spring-boot = { path = "/tmp/boot.jar", sha256 = "%s" }

                [network
                """.formatted(SHA));
        GlobalConfig.clearCache();

        assertThatThrownBy(() -> UserPlugins.fromConfig(file)).isInstanceOf(UserConfigParseException.class);
    }

    @Test
    void a_fixed_file_is_read_on_the_next_call(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, "[plugins\n");
        GlobalConfig.clearCache();
        assertThatThrownBy(() -> UserPlugins.fromConfig(file)).isInstanceOf(UserConfigParseException.class);

        Files.writeString(file, """
                [plugins]
                spring-boot = { path = "/tmp/boot.jar", sha256 = "%s" }
                """.formatted(SHA));
        assertThat(UserPlugins.fromConfig(file))
                .extracting(PluginDeclaration::alias)
                .containsExactly("spring-boot");
    }

    @Test
    void a_project_parse_fails_on_a_user_config_that_does_not_parse(@TempDir Path dir) throws Exception {
        System.setProperty("jk.env.JK_HOME", dir.resolve("home").toString());
        try {
            Path config = JkDirs.userConfigFile();
            Files.createDirectories(Objects.requireNonNull(config.getParent()));
            Files.writeString(config, "[plugins\n");
            Path project = Files.createDirectories(dir.resolve("project"));
            Path toml = Files.writeString(project.resolve("jk.toml"), """
                    group = "com.example"
                    name = "widget"
                    version = "1.0.0"
                    """);
            GlobalConfig.clearCache();
            JkBuildParser.dropMemos();

            assertThatThrownBy(() -> JkBuildParser.parse(toml))
                    .isInstanceOfSatisfying(UserConfigParseException.class, e -> assertThat(e.file())
                            .isEqualTo(config));
        } finally {
            GlobalConfig.clearCache();
        }
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
