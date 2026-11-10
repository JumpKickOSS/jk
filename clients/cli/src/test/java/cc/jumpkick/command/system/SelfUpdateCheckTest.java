// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What {@code jk self update --check} says about the latest release against the running one. */
class SelfUpdateCheckTest {

    @Test
    void a_newer_release_is_offered() {
        assertThat(SelfCommand.UpdateSub.checkSummary("0.51.0", "0.50.3"))
                .isEqualTo("jk 0.51.0 is available (this is 0.50.3)");
    }

    @Test
    void the_same_release_or_no_record_is_the_latest() {
        assertThat(SelfCommand.UpdateSub.checkSummary("0.50.3", "0.50.3")).isEqualTo("jk 0.50.3 is the latest release");
        assertThat(SelfCommand.UpdateSub.checkSummary(null, "0.50.3")).isEqualTo("jk 0.50.3 is the latest release");
    }

    @Test
    void a_build_ahead_of_the_site_is_a_finished_check_not_an_error() {
        assertThat(SelfCommand.UpdateSub.checkSummary("0.50.3", "0.50.5"))
                .isEqualTo("jk 0.50.5 is newer than the latest release, 0.50.3");
    }
}
