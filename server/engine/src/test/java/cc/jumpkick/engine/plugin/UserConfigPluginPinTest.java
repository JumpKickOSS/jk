// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.Hashing;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-2253: a user-config plugin pin that cannot be honored is a loud error — silently running
 * the shipped plugin instead is wrong code with no diagnostic.
 */
class UserConfigPluginPinTest {

    private static final String MANIFEST = """
            [plugin]
            id      = "zz-userpin"
            table   = "zz-userpin"
            version = "1.0.0"

            [schema]
            enabled = { type = "bool", default = true }
            """;

    @AfterEach
    void restoreConfigOverride() {
        System.clearProperty("jk.env.JK_CONFIG_FILE");
    }

    @Test
    void missing_pinned_jar_is_a_loud_error(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                [plugins]
                acme = { path = "%s", sha256 = "%s" }
                """.formatted(tmp.resolve("gone.jar"), "ab".repeat(32)));
        System.setProperty("jk.env.JK_CONFIG_FILE", config.toString());

        assertThatThrownBy(BuiltInPluginJars::installUserConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no file exists")
                .hasMessageContaining("gone.jar");
    }

    @Test
    void hash_mismatch_names_both_digests(@TempDir Path tmp) throws Exception {
        Path jar = writePluginJar(tmp.resolve("acme.jar"));
        String actual = Hashing.sha256Hex(jar);
        String declared = "cd".repeat(32);
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                [plugins]
                acme = { path = "%s", sha256 = "%s" }
                """.formatted(jar, declared));
        System.setProperty("jk.env.JK_CONFIG_FILE", config.toString());

        assertThatThrownBy(BuiltInPluginJars::installUserConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(declared)
                .hasMessageContaining(actual);
    }

    @Test
    void matching_pin_installs_the_manifest(@TempDir Path tmp) throws Exception {
        Path jar = writePluginJar(tmp.resolve("acme.jar"));
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                [plugins]
                acme = { path = "%s", sha256 = "%s" }
                """.formatted(jar, Hashing.sha256Hex(jar)));
        System.setProperty("jk.env.JK_CONFIG_FILE", config.toString());

        BuiltInPluginJars.installUserConfig();

        assertThat(PluginTableRegistry.byTable("zz-userpin")).isPresent();
    }

    private static Path writePluginJar(Path target) throws Exception {
        try (OutputStream out = Files.newOutputStream(target);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("jk-plugin.toml"));
            zip.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return target;
    }
}
