// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.InFlightBuilds.Hold;
import org.jspecify.annotations.Nullable;

/**
 * Result of admitting a job: either a rejection hold (same fingerprint already running) or
 * allocated build number + optional journal id.
 */
public record AdmitResult(
        @Nullable Hold rejected, long buildNumber, @Nullable String journalId) {
    public static AdmitResult reject(Hold h) {
        return new AdmitResult(h, 0, null);
    }

    public static AdmitResult ok(long buildNumber, @Nullable String journalId) {
        return new AdmitResult(null, buildNumber, journalId);
    }
}
