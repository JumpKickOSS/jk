// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Proves {@link CliOutputGlobal} is live the only way a boundary can be asserted: across two tests
 * in one class. The first leaves script mode on; the second must not inherit it — otherwise
 * Spinner / JdkDownloadBar stay silent for every later class in the same worker.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CliOutputBoundaryTest {

    @Test
    @Order(1)
    void a_test_may_leave_script_mode_on() {
        CliOutput.beginCommand(true);
        assertThat(CliOutput.scriptMode()).isTrue();
    }

    @Test
    @Order(2)
    void the_next_test_does_not_inherit_script_mode() {
        assertThat(CliOutput.scriptMode())
                .describedAs("the previous test's beginCommand(true) must not survive into this one")
                .isFalse();
    }
}
