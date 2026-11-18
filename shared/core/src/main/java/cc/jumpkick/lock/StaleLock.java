// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import org.jspecify.annotations.Nullable;

/**
 * The words for a stale lock, apart from the decision ({@link LockFreshness}) so a client that
 * only prints them reaches no parser: what a build says when it stops on one it could not update,
 * and what {@code jk explain} says of that build before it runs.
 */
public final class StaleLock {

    /**
     * The one phrase for a lock its manifests moved past, so the build that fails or warns on it,
     * {@code jk explain} and {@code jk doctor} name it alike.
     */
    public static final String STALE = "jk-lock.toml is stale: jk.toml changed since it was written";

    private StaleLock() {}

    /**
     * What a build says when it stops on a lock stale for {@code stale} (a {@link
     * LockFreshness#staleReason} sentence: a moved manifest, or an install a row came from) that it
     * could not update for {@code reason}.
     */
    public static String refreshFailed(String stale, @Nullable String reason) {
        String why = reason == null || reason.isBlank() ? "dependency resolution failed" : reason.strip();
        if (!stale.equals(STALE)) {
            return stale + ", and updating it failed: " + why
                    + "\nThe build stops rather than run on bytes no install here holds: run jk install in that"
                    + " project on this machine, or add a repository that serves it to [repositories]";
        }
        return stale + ", and updating it failed: " + why
                + "\nThe build stops rather than run on pins jk.toml no longer names: run jk lock once it can"
                + " resolve (online, the host allowed, the artifact published), or undo the jk.toml change";
    }
}
