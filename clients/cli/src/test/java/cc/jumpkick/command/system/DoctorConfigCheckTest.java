// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The config row: a config.toml that does not parse fails every fetch, so doctor fails it too, with the position. */
class DoctorConfigCheckTest {

    @Test
    void a_config_toml_that_does_not_parse_is_a_failure_naming_the_line(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(
                tmp.resolve("config.toml"), "[repositories.corp]\nurl = \"https://x.test/\"\n[oops\n");

        DoctorCommand.Check check = DoctorCommand.checkConfig(file);

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.FAIL);
        assertThat(check.label()).isEqualTo("config");
        assertThat(check.detail()).startsWith(file + ":3:");
    }

    @Test
    void a_config_toml_that_parses_or_is_absent_is_ok(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("config.toml"), "nerd-font = false\n");

        assertThat(DoctorCommand.checkConfig(file).status()).isEqualTo(DoctorCommand.Status.OK);
        assertThat(DoctorCommand.checkConfig(tmp.resolve("absent.toml")).status())
                .isEqualTo(DoctorCommand.Status.OK);
    }
}
