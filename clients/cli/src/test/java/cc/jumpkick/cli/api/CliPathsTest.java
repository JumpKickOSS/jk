// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliPathsTest {

    @Test
    void relative_flags_resolve_against_the_invocation_cwd() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        assertThat(CliPaths.abs("rel.toml")).isEqualTo(cwd.resolve("rel.toml"));
        assertThat(CliPaths.abs("./c/../d")).isEqualTo(cwd.resolve("d"));
    }

    @Test
    void absolute_flags_pass_through_normalized() {
        // Absolute + normalize — on Windows "/tmp/…" becomes drive-qualified (C:\tmp\…).
        assertThat(CliPaths.abs("/tmp/a/../b"))
                .isEqualTo(Path.of("/tmp/a/../b").toAbsolutePath().normalize());
    }
}
