// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.HostLoad;
import org.junit.jupiter.api.Test;

/**
 * The per-file limit is the 3 s default on a quiet host and grows with the load per online
 * processor — rounded up, at most ten times — so a formatter run on a machine fifteen engines are
 * sharing does not give up on files a quiet machine formats in a tenth of a second. A limit set by
 * hand is taken as written.
 */
class FormatTimeoutTest {

    @Test
    void a_quiet_or_unknown_host_keeps_the_default_and_says_nothing_about_load() {
        assertThat(FormatTimeout.forHost(0.4, 24)).isEqualTo(new FormatTimeout(FormatWatchdog.DEFAULT_TIMEOUT_MS, ""));
        assertThat(FormatTimeout.forHost(-1, 24)).isEqualTo(new FormatTimeout(FormatWatchdog.DEFAULT_TIMEOUT_MS, ""));
        assertThat(FormatTimeout.forHost(24, 24)).isEqualTo(new FormatTimeout(FormatWatchdog.DEFAULT_TIMEOUT_MS, ""));
    }

    @Test
    void a_loaded_host_stretches_the_default_by_its_load_per_processor_and_the_note_says_how() {
        FormatTimeout doubled = FormatTimeout.forHost(48, 24);
        assertThat(doubled.ms()).isEqualTo(6_000);
        assertThat(doubled.why())
                .isEqualTo("the 3000 ms default stretched 2× for a load average of 48.0 over 24 processors");

        FormatTimeout capped = FormatTimeout.forHost(290.5, 24);
        assertThat(capped.ms()).isEqualTo(FormatWatchdog.DEFAULT_TIMEOUT_MS * HostLoad.MAX_FACTOR);
        assertThat(capped.why()).contains("stretched 10×").contains("290.5 over 24 processors");
    }

    @Test
    void a_limit_set_by_hand_is_taken_as_written_whatever_the_load() {
        assertThat(FormatTimeout.explicit(15_000)).isEqualTo(new FormatTimeout(15_000, ""));
        assertThat(FormatTimeout.explicit(0)).isEqualTo(new FormatTimeout(0, ""));
    }
}
