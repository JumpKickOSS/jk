// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.forge.ForgejoRepository;
import cc.jumpkick.forge.GitHubRepository;
import cc.jumpkick.forge.GitLabProject;
import cc.jumpkick.wire.runtime.TailArgs;

/** Opens the {@link ReleaseStore} a run's {@code [publish] release} names. */
public final class ReleaseStores {

    private ReleaseStores() {}

    /** The store of {@code run}'s release, written with {@code args}' credential. */
    public static ReleaseStore open(ReleaseRun run, TailArgs.Publish args) {
        return switch (run.forge()) {
            case null -> ReleaseBucket.open(run);
            case GitHubRepository github -> ReleaseGitHub.open(run, github, args.releaseToken());
            case GitLabProject gitlab -> ReleaseGitLab.open(run, gitlab, args.releaseToken(), args.releaseJobToken());
            case ForgejoRepository forgejo -> ReleaseForgejo.open(run, forgejo, args.releaseToken());
        };
    }
}
