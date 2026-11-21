// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.EngineLogon;
import org.junit.jupiter.api.Test;

/** {@code jk engine status} on Windows names the logon kind its engine serves, and says nothing elsewhere. */
class EngineStatusLogonTest {

    @Test
    void an_engine_without_a_desktop_says_it_has_no_credential_vault() {
        assertThat(EngineStatusCommand.describeLogon(EngineLogon.NO_DESKTOP))
                .isEqualTo("ssh or service: no Windows credential vault");
        assertThat(EngineStatusCommand.describeLogon(EngineLogon.DESKTOP)).isEqualTo("desktop");
    }

    @Test
    void no_row_off_windows() {
        assertThat(EngineStatusCommand.describeLogon(EngineLogon.ANY)).isNull();
    }
}
