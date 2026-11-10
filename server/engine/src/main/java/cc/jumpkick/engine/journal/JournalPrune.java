// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** The {@code [history]} budgets over the runs, ended sessions and settled decisions of one journal. */
final class JournalPrune {

    private JournalPrune() {}

    /** A prunable run, session or decision; {@code decision} is the decision's id, null for the others. */
    record Entry(
            Path path, long millis, long size, @Nullable String decision) {

        /**
         * A run or session directory, last changed at {@code millis}, billed for the bytes of its
         * files not already in {@code billed}: runs hard-link the snapshots they share, and a file
         * linked into ten runs takes its disk once, not ten times.
         */
        static Entry dir(Path dir, long millis, Set<Object> billed) {
            return new Entry(dir, millis, sizeOf(dir, billed), null);
        }
    }

    /**
     * Deletes the entries past {@code maxAgeMillis}, then the oldest until the rest fit {@code
     * maxDiskBytes}: {@code runs}, and the ended sessions and settled decisions under {@code
     * buildsRoot}. A decision goes through {@code dropDecision}, which answers false to keep it.
     */
    static BuildJournal.PruneResult prune(
            Path buildsRoot,
            List<Entry> runs,
            long maxAgeMillis,
            long maxDiskBytes,
            long nowMillis,
            Predicate<String> dropDecision) {
        List<Entry> entries = new ArrayList<>(runs);
        // Ended sessions and settled decisions share the runs' age and disk budgets.
        Set<Object> billed = new HashSet<>();
        entries.addAll(SessionJournal.prunable(buildsRoot, nowMillis, (dir, at) -> Entry.dir(dir, at, billed)));
        entries.addAll(SessionJournal.prunableDecisions(
                buildsRoot, nowMillis, (file, at, size) -> new Entry(file, at, size, SessionJournal.decisionId(file))));
        int removed = 0;
        long removedBytes = 0;
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (maxAgeMillis > 0 && nowMillis - e.millis() > maxAgeMillis) {
                if (delete(e, dropDecision)) {
                    removed++;
                    removedBytes += e.size();
                }
            } else {
                kept.add(e);
            }
        }
        if (maxDiskBytes > 0) {
            long total = kept.stream().mapToLong(Entry::size).sum();
            if (total > maxDiskBytes) {
                kept.sort(Comparator.comparingLong(Entry::millis));
                for (Entry e : kept) {
                    if (total <= maxDiskBytes) break;
                    if (!delete(e, dropDecision)) continue;
                    removed++;
                    removedBytes += e.size();
                    total -= e.size();
                }
            }
        }
        return new BuildJournal.PruneResult(removed, removedBytes);
    }

    /** Deletes {@code e}; false when its decision's writer kept it. */
    private static boolean delete(Entry e, Predicate<String> dropDecision) {
        if (e.decision() != null) return dropDecision.test(e.decision());
        PathUtil.deleteRecursively(e.path());
        return true;
    }

    private static long sizeOf(Path dir, Set<Object> billed) {
        // The walk already read each entry's size; asking Files.size again would stat every file twice.
        long[] total = {0L};
        try {
            PathUtil.forEachRegularFile(dir, (f, attrs) -> {
                Object key = attrs.fileKey();
                if (key == null || billed.add(key)) total[0] += attrs.size();
            });
        } catch (IOException e) {
            return 0L;
        }
        return total[0];
    }
}
