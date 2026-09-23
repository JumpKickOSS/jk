// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The packages a rewrite of {@code jk-lock.toml} added, removed or moved to another version. */
public final class LockDiff {

    private LockDiff() {}

    /**
     * One changed lock row by its display coordinate ({@code group:artifact}, plus the classifier
     * or type when it has one): {@code from} is null for an added package, {@code to} for a removed one.
     * {@code members} are the workspace members a member-override row serves (empty for the
     * workspace's own row), so an override counts on its own.
     */
    public record Change(
            String coordinate,
            @Nullable String from,
            @Nullable String to,
            List<String> members) {
        public Change {
            members = List.copyOf(members);
        }
    }

    /** Every changed row, ordered by coordinate; every row of {@code after} when {@code before} is null. */
    public static List<Change> between(@Nullable Lockfile before, Lockfile after) {
        Map<Row, Lockfile.Artifact> old = byRow(before != null ? before.artifacts() : List.of());
        Map<Row, Lockfile.Artifact> now = byRow(after.artifacts());
        List<Change> changes = new ArrayList<>();
        for (var e : now.entrySet()) {
            Lockfile.Artifact was = old.get(e.getKey());
            Lockfile.Artifact is = e.getValue();
            if (was == null || !was.version().equals(is.version()))
                changes.add(new Change(
                        is.displayIdentity(), was == null ? null : was.version(), is.version(), is.members()));
        }
        for (var e : old.entrySet()) {
            Lockfile.Artifact was = e.getValue();
            if (!now.containsKey(e.getKey()))
                changes.add(new Change(was.displayIdentity(), was.version(), null, was.members()));
        }
        changes.sort(Comparator.comparing(Change::coordinate).thenComparing(c -> String.join(",", c.members())));
        return changes;
    }

    /** The lockfile under {@code lockDir} as it stands now, or {@code null} when absent or unreadable. */
    public static @Nullable Lockfile current(Path lockDir) {
        Path file = LockPaths.lockFile(lockDir);
        if (!Files.isRegularFile(file)) return null;
        try {
            return LockfileReader.read(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private record Row(String name, List<String> members) {}

    private static Map<Row, Lockfile.Artifact> byRow(List<Lockfile.Artifact> rows) {
        Map<Row, Lockfile.Artifact> byRow = new LinkedHashMap<>();
        for (Lockfile.Artifact a : rows) byRow.put(new Row(a.name(), a.members()), a);
        return byRow;
    }
}
