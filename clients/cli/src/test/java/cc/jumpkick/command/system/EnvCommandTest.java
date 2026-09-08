// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.DotEnv;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.SecretRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvCommandTest {

    @Test
    void file_sourced_secret_is_masked_and_labeled(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("mod");
        Files.createDirectories(module);
        Files.writeString(module.resolve(".env"), """
                REPO_TOKEN=super-secret-token-value
                MODE=dev
                """);
        EnvLookup lookup = EnvLookup.forModule(module, name -> null);
        Map<String, String> mod = DotEnv.read(module.resolve(".env"));
        var row = EnvCommand.resolveRow(
                "REPO_TOKEN", lookup, Map.of(), mod, tmp.resolve(".env"), module.resolve(".env"), tmp, module);
        assertThat(row.secret()).isTrue();
        assertThat(row.effective()).isEqualTo("super-secret-token-value");
        assertThat(row.source()).contains(".env").contains("module");
        // Display path masks via SecretRedactor.MASK
        assertThat(SecretRedactor.MASK).isEqualTo("***");
    }

    @Test
    void shell_shadows_module_env(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("mod");
        Files.createDirectories(module);
        Files.writeString(module.resolve(".env"), "MODE=from-file\n");
        EnvLookup lookup = EnvLookup.forModule(module, name -> "MODE".equals(name) ? "from-shell" : null);
        Map<String, String> mod = DotEnv.read(module.resolve(".env"));
        var row = EnvCommand.resolveRow(
                "MODE", lookup, Map.of(), mod, tmp.resolve(".env"), module.resolve(".env"), tmp, module);
        assertThat(row.secret()).isFalse();
        assertThat(row.effective()).isEqualTo("from-shell");
        assertThat(row.source()).startsWith("shell");
        assertThat(row.shadowed()).isEqualTo("from-file");
    }

    @Test
    void buildEnv_lookup_usable_for_cli_dir(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "FOO=bar-from-file\n");
        EnvLookup lookup = BuildEnv.lookupFor(tmp);
        assertThat(lookup.get("FOO")).isEqualTo("bar-from-file");
        assertThat(lookup.isFromFile("FOO")).isTrue();
    }
}
