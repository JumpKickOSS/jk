// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.wire.runtime.TailArgs;

/** Opens the {@link ReleaseStore} a run's {@code [publish] release} names. */
public final class ReleaseStores {

    private ReleaseStores() {}

    /** The store of {@code run}'s release, written with {@code args}' credential. */
    public static ReleaseStore open(ReleaseRun run, TailArgs.Publish args) {
        return run.target() instanceof ReleaseTarget.Bucket
                ? ReleaseBucket.open(run)
                : ReleaseGitHub.open(run, args.releaseToken());
    }
}
