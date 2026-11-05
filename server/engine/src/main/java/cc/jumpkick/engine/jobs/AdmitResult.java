// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.api.InFlightBuilds.Hold;
import org.jspecify.annotations.Nullable;

/**
 * Result of admitting a job: either a rejection hold (same fingerprint already running) or
 * allocated build number, optional journal id and the session episode the run was placed in.
 */
public record AdmitResult(
        @Nullable Hold rejected,
        long buildNumber,
        @Nullable String journalId,
        @Nullable String episode) {
    public static AdmitResult reject(Hold h) {
        return new AdmitResult(h, 0, null, null);
    }

    public static AdmitResult ok(long buildNumber, @Nullable String journalId, @Nullable String episode) {
        return new AdmitResult(null, buildNumber, journalId, episode);
    }
}
