// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.engine.api.BuildHistoryKinds;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which earlier run a {@link JobDelta} compares against: the run before from the same origin —
 * the same {@code trigger} and the same {@code session} (an MCP connection, an IDE window; a plain
 * {@code cli} run has none and matches the {@code cli} runs before it). Runs from other origins in
 * between are stepped over, so two agents iterating on one project each see their own trail, and
 * a human's {@code jk build} beside them sees theirs.
 */
final class JournalLineage {

    private JournalLineage() {}

    /** How many earlier runs of a project the lookup walks before giving up. */
    static final int LOOKBACK = 50;

    /** An earlier run and the directory its artifacts sit in. */
    public record Previous(BuildRecord record, Path dir) {}

    /**
     * The newest finished, build-like run of {@code current}'s project from the same origin that
     * ended before {@code current} started, read through {@code reader}; empty when there is none
     * within {@value #LOOKBACK} runs.
     */
    static Optional<Previous> previousInSession(
            Path buildsRoot, Function<Path, Optional<BuildRecord>> reader, BuildRecord current) {
        if (current.dir() == null || current.dir().isBlank()) return Optional.empty();
        Path home;
        try {
            home = ProjectBuilds.projectHome(buildsRoot, current.coord(), Path.of(current.dir()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        int seen = 0;
        for (Path run : ProjectBuilds.listRuns(home)) {
            long number = ProjectBuilds.runNumberOf(run);
            if (number <= 0 || (current.buildNumber() > 0 && number >= current.buildNumber())) continue;
            if (seen++ >= LOOKBACK) break;
            Optional<BuildRecord> record = reader.apply(run).filter(r -> precedes(r, current));
            if (record.isPresent()) return Optional.of(new Previous(record.get(), run));
        }
        return Optional.empty();
    }

    /** {@code earlier} is a finished build-like run from {@code current}'s origin that ended before it began. */
    static boolean precedes(BuildRecord earlier, BuildRecord current) {
        return Objects.equals(earlier.trigger(), current.trigger())
                && Objects.equals(earlier.session(), current.session())
                && !earlier.running()
                && BuildHistoryKinds.isBuildLike(earlier.kind())
                && earlier.finishedAt() > 0
                && earlier.finishedAt() <= current.startedAt();
    }
}
