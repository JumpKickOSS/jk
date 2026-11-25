// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.WorkingDirectory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliPathsTest {

    @Test
    void relative_flags_resolve_against_the_invocation_cwd() {
        Path cwd = WorkingDirectory.current();
        assertThat(CliPaths.abs("rel.toml")).isEqualTo(cwd.resolve("rel.toml"));
        assertThat(CliPaths.abs("./c/../d")).isEqualTo(cwd.resolve("d"));
    }

    @Test
    void rooted_flags_use_the_invocations_root_and_are_normalized() {
        assertThat(CliPaths.abs("/tmp/a/../b"))
                .isEqualTo(WorkingDirectory.current().getRoot().resolve("tmp/b"));
    }
}
