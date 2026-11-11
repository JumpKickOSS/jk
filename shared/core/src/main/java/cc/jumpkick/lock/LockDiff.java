// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        Map<Row, List<Lockfile.Artifact>> old = byRow(before != null ? before.artifacts() : List.of());
        Map<Row, List<Lockfile.Artifact>> now = byRow(after.artifacts());
        List<Change> changes = new ArrayList<>();
        for (var e : now.entrySet()) {
            List<Lockfile.Artifact> was = new ArrayList<>(old.getOrDefault(e.getKey(), List.of()));
            List<Lockfile.Artifact> is = new ArrayList<>(e.getValue());
            pair(was, is, changes);
        }
        for (var e : old.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            for (Lockfile.Artifact was : e.getValue())
                changes.add(new Change(was.displayIdentity(), was.version(), null, was.members()));
        }
        changes.sort(Comparator.comparing(Change::coordinate).thenComparing(c -> String.join(",", c.members())));
        return changes;
    }

    /**
     * The changes between the rows of one coordinate before and after. A coordinate can hold a main
     * row and a test row at another version; a version both sides hold is unchanged whichever row
     * holds it, and the rest pair up in version order.
     */
    private static void pair(List<Lockfile.Artifact> was, List<Lockfile.Artifact> is, List<Change> changes) {
        for (var it = is.iterator(); it.hasNext(); ) {
            Lockfile.Artifact row = it.next();
            for (var w = was.iterator(); w.hasNext(); ) {
                if (w.next().version().equals(row.version())) {
                    w.remove();
                    it.remove();
                    break;
                }
            }
        }
        Comparator<Lockfile.Artifact> byVersion = Comparator.comparing(Lockfile.Artifact::version, Versions::compare);
        was.sort(byVersion);
        is.sort(byVersion);
        for (int i = 0; i < Math.max(was.size(), is.size()); i++) {
            Lockfile.Artifact from = i < was.size() ? was.get(i) : null;
            Lockfile.Artifact to = i < is.size() ? is.get(i) : null;
            Lockfile.Artifact shown = to != null ? to : Objects.requireNonNull(from);
            changes.add(new Change(
                    shown.displayIdentity(),
                    from == null ? null : from.version(),
                    to == null ? null : to.version(),
                    shown.members()));
        }
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

    private static Map<Row, List<Lockfile.Artifact>> byRow(List<Lockfile.Artifact> rows) {
        Map<Row, List<Lockfile.Artifact>> byRow = new LinkedHashMap<>();
        for (Lockfile.Artifact a : rows)
            byRow.computeIfAbsent(new Row(a.name(), a.members()), k -> new ArrayList<>())
                    .add(a);
        return byRow;
    }
}
