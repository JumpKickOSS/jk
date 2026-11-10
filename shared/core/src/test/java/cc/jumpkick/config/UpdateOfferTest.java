// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** One "not now" quiets the offer of one version, everywhere, until the next check is due. */
class UpdateOfferTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void a_newer_staged_version_is_offered() {
        assertThat(UpdateOffer.due(SelfUpdateState.EMPTY, "0.51.0", "0.50.3", NOW))
                .contains("0.51.0");
    }

    @Test
    void nothing_staged_or_nothing_newer_is_never_offered() {
        assertThat(UpdateOffer.due(SelfUpdateState.EMPTY, null, "0.50.3", NOW)).isEmpty();
        assertThat(UpdateOffer.due(SelfUpdateState.EMPTY, "0.50.3", "0.50.3", NOW))
                .isEmpty();
        assertThat(UpdateOffer.due(SelfUpdateState.EMPTY, "0.50.2", "0.50.3", NOW))
                .isEmpty();
    }

    @Test
    void not_now_lasts_until_the_next_check_is_due() {
        SelfUpdateState checked = SelfUpdateState.EMPTY.withCheck(NOW.minus(Duration.ofHours(2)), "0.51.0");
        SelfUpdateState snoozed = UpdateOffer.snooze(checked, "0.51.0", NOW);

        assertThat(snoozed.snoozeUntil()).isEqualTo(NOW.plus(Duration.ofHours(10)));
        assertThat(UpdateOffer.due(snoozed, "0.51.0", "0.50.3", NOW.plus(Duration.ofHours(9))))
                .isEmpty();
        assertThat(UpdateOffer.due(snoozed, "0.51.0", "0.50.3", NOW.plus(Duration.ofHours(10))))
                .contains("0.51.0");
    }

    @Test
    void a_newer_version_staged_during_a_snooze_is_offered_at_once() {
        SelfUpdateState snoozed = UpdateOffer.snooze(SelfUpdateState.EMPTY, "0.51.0", NOW);
        assertThat(UpdateOffer.due(snoozed, "0.52.0", "0.50.3", NOW.plusSeconds(60)))
                .contains("0.52.0");
    }

    @Test
    void a_snooze_with_no_check_or_an_overdue_one_lasts_a_full_interval() {
        assertThat(UpdateOffer.snooze(SelfUpdateState.EMPTY, "0.51.0", NOW).snoozeUntil())
                .isEqualTo(NOW.plus(UpdateOffer.CHECK_EVERY));
        SelfUpdateState overdue = SelfUpdateState.EMPTY.withCheck(NOW.minus(Duration.ofHours(30)), "0.51.0");
        assertThat(UpdateOffer.snooze(overdue, "0.51.0", NOW).snoozeUntil())
                .isEqualTo(NOW.plus(UpdateOffer.CHECK_EVERY));
    }
}
