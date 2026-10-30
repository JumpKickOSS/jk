// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdateState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelfUpdateStatusTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private static String row(String version, SelfUpdateState state, Optional<PreparedUpdate> prepared) {
        SelfUpdatePolicy.Decision decision = SelfUpdatePolicy.decide(version, null, null, state, false);
        return SelfUpdateStatus.describe(version, decision, state, prepared, NOW);
    }

    @Test
    void a_home_that_never_checked_says_so() {
        assertThat(row("0.16.2", SelfUpdateState.EMPTY, Optional.empty()))
                .isEqualTo("auto (updates are compulsory before 1.0); not checked yet");
    }

    @Test
    void the_last_check_and_what_it_found() {
        var current = SelfUpdateState.EMPTY.withCheck(NOW.minus(Duration.ofHours(3)), "0.16.2");
        assertThat(row("0.16.2", current, Optional.empty())).endsWith("checked 3 h ago, up to date");
        var behind = SelfUpdateState.EMPTY.withCheck(NOW.minus(Duration.ofMinutes(5)), "0.17.0");
        assertThat(row("0.16.2", behind, Optional.empty())).endsWith("checked 5 min ago, 0.17.0 is out");
    }

    @Test
    void a_download_waiting_to_install() {
        var state = SelfUpdateState.EMPTY.withCheck(NOW.minus(Duration.ofMinutes(5)), "0.17.0");
        var prepared = new PreparedUpdate(
                "0.17.0", "0.16.2", "a".repeat(64), PreparedUpdate.Client.NATIVE, "b".repeat(64), NOW);
        assertThat(row("0.16.2", state, Optional.of(prepared)))
                .endsWith("checked 5 min ago, 0.17.0 downloaded, installs when the engine is quiet");
    }

    @Test
    void a_hold_names_the_way_out() {
        assertThat(row("0.16.2", SelfUpdateState.EMPTY.withHold("0.16.2", "0.17.0"), Optional.empty()))
                .startsWith("off: held at 0.16.2")
                .contains("jk self update");
    }

    @Test
    void ages_read_in_the_unit_that_fits() {
        assertThat(SelfUpdateStatus.ago(Duration.ofMinutes(12))).isEqualTo("12 min ago");
        assertThat(SelfUpdateStatus.ago(Duration.ofHours(5))).isEqualTo("5 h ago");
        assertThat(SelfUpdateStatus.ago(Duration.ofDays(3))).isEqualTo("3 days ago");
    }
}
