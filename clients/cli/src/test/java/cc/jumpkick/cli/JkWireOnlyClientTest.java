// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code --engine-server} on the slim client names {@code jk snapshot}, never an install of a {@code
 * jk build} dist: that dist is release-versioned, and installed into the default home it hides every
 * later snapshot of the same version.
 */
class JkWireOnlyClientTest {

    @Test
    void the_wire_only_advice_names_jk_snapshot_and_no_installer() {
        assertThat(Jk.WIRE_ONLY_CLIENT)
                .contains(
                        "wire-only client",
                        "`jk snapshot`",
                        "`jk self materialize`",
                        "`jk self update`",
                        "JK_ENGINE_EXE")
                .doesNotContain("install.sh");
    }
}
