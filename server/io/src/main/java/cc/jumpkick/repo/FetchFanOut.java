// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * What the network legs of one coordinate's fan-out across a {@link RepoGroup} said, over the
 * eligible pass and the last-resort pass: the first transport failure, and whether the repository
 * that holds the coordinate's POM answered. A failure is the fan-out's answer unless the holder
 * answered not-found — a GAV's POM and its files are published together, so a jar the holder says
 * is not there is not there; the failure is then that remote's problem, said once per run, and the
 * coordinate has no artifact.
 */
final class FetchFanOut {

    private final Coordinate coord;
    private final @Nullable MavenRepo holder;
    private @Nullable IOException firstFailure;
    private @Nullable MavenRepo failed;
    private boolean holderAnswered;

    /** @param holder the repository that served {@code coord}'s POM, or null when none is known */
    FetchFanOut(Coordinate coord, @Nullable MavenRepo holder) {
        this.coord = coord;
        this.holder = holder;
    }

    /** {@code repo} answered not-found. */
    void notFound(MavenRepo repo) {
        if (repo == holder) holderAnswered = true;
    }

    /** {@code repo}'s leg failed in transport: a reset, a 5xx, a timeout. */
    void failed(MavenRepo repo, IOException transport) {
        if (firstFailure == null) {
            firstFailure = transport;
            failed = repo;
        }
    }

    /**
     * Called once every candidate has missed: throws the failure when nobody who counts answered,
     * otherwise warns once per run per repository and lets the miss stand.
     */
    void settle() throws IOException {
        if (firstFailure == null) return;
        if (!holderAnswered || holder == null || failed == null) throw firstFailure;
        MavenRepo unreachable = failed;
        IOException cause = firstFailure;
        MavenRepo answered = holder;
        RunNotices.warnOnce(
                "repo-unreachable:" + unreachable.name(),
                () -> "jk: warning: repository " + unreachable.name() + " is unreachable ("
                        + RepoGroup.describe(cause) + "); " + answered.name() + " holds the POM of " + coord
                        + " and answered for its files");
    }
}
