// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.version.Versions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * When a downloaded update is offered to a person, on every surface that offers it: the prompt
 * after a terminal command, the web console's header and an MCP client's elicitation. One answer
 * of "not now" on any of them quiets all of them, for that version, until the next check is due;
 * a newer version staged in the meantime is offered at once. Before 1.0 the answer only quiets the
 * offer: the engine still installs the update at its next quiet spell, whatever was answered.
 */
public final class UpdateOffer {

    /** Gap between checks of the release site; a "not now" lasts until the next one is due. */
    public static final Duration CHECK_EVERY = Duration.ofHours(12);

    private UpdateOffer() {}

    /**
     * The version to offer at {@code now}: {@code staged}, when it is newer than {@code running}
     * and not snoozed. Empty when nothing is staged, the staged update is not newer, or a "not now"
     * for it still holds.
     */
    public static Optional<String> due(SelfUpdateState state, @Nullable String staged, String running, Instant now) {
        if (staged == null || Versions.compare(staged, running) <= 0) return Optional.empty();
        return snoozed(state, staged, now) ? Optional.empty() : Optional.of(staged);
    }

    /** Whether a "not now" for {@code version} still holds at {@code now}. */
    public static boolean snoozed(SelfUpdateState state, String version, Instant now) {
        Instant until = state.snoozeUntil();
        return version.equals(state.snoozeVersion()) && until != null && now.isBefore(until);
    }

    /**
     * {@code state} with "not now" recorded for {@code version}: until the next check is due (the
     * last check plus {@link #CHECK_EVERY}), or a full interval from {@code now} when that moment
     * has already passed or no check is recorded.
     */
    public static SelfUpdateState snooze(SelfUpdateState state, String version, Instant now) {
        Instant last = state.lastCheck();
        Instant until = last == null ? now.plus(CHECK_EVERY) : last.plus(CHECK_EVERY);
        if (!until.isAfter(now)) until = now.plus(CHECK_EVERY);
        return state.withSnooze(version, until);
    }
}
