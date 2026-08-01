// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HostCalibrationStatusTest {

    @Test
    void needs_bootstrap_probe_is_boolean_without_throwing() {
        // Depends on the host's real ~/.jk — only assert the API is safe to call from the CLI.
        assertThat(HostCalibrationStatus.needsBootstrapProbe()).isIn(true, false);
    }
}
