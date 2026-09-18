// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.PackageId;
import java.util.ArrayList;
import java.util.List;

/**
 * The marks a lock from a writer before {@link Lockfile#FILELESS_ROWS_MARKED_SINCE} lacks: its
 * BOMs, aggregators and relocation stubs pin no checksum and name no file, which a reader takes
 * as file-less by that writer's rule. {@link #marked} names the POM each such row stands for, so
 * the lock reads under the current rule with nothing else changed — no resolve, no other diff.
 */
public final class FilelessRows {

    private FilelessRows() {}

    /** True when {@code lock}'s writer marked no row and a checksum-less row of it is still bare. */
    public static boolean needsMarks(Lockfile lock) {
        if (lock.marksFilelessRows()) return false;
        for (Lockfile.Artifact row : lock.artifacts()) {
            if (row.checksum() == null && !row.pomOnly()) return true;
        }
        return false;
    }

    /** {@code lock} with every bare checksum-less row naming its POM file; every other row as it was. */
    public static Lockfile marked(Lockfile lock) {
        List<Lockfile.Artifact> rows = new ArrayList<>(lock.artifacts().size());
        for (Lockfile.Artifact row : lock.artifacts()) {
            rows.add(row.checksum() == null && !row.pomOnly() ? withPomPath(row) : row);
        }
        return lock.withArtifacts(rows);
    }

    private static Lockfile.Artifact withPomPath(Lockfile.Artifact row) {
        String artifact = PackageId.isMavenPackageKey(row.name())
                ? PackageId.parse(row.name()).artifact()
                : row.name();
        return new Lockfile.Artifact(
                row.name(),
                row.version(),
                row.source(),
                null,
                artifact + "-" + row.version() + ".pom",
                row.scopes(),
                row.deps(),
                row.pinnedBy(),
                row.git(),
                row.sourcesChecksum(),
                row.declared(),
                row.excludedBy(),
                row.members());
    }
}
