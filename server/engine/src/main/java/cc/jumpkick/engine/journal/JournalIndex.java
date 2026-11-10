// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.ProjectBuilds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Every run of a builds root by its record head, so a lookup by id or request id, the newest N
 * runs and the stale-running sweep cost a few stats rather than a read of every record.
 *
 * <p>The index stays honest without being told about every write: before it answers it stats each
 * project home's {@code runs/} directory (one stat per project, not per run) and rescans only a
 * home whose listing moved, reusing every run whose directory did not change. A run still marked
 * running is re-checked by its own directory's mtime, so one another engine finished on the same
 * root is seen. The journal's own writes mark the run they touched, so a rewrite inside one clock
 * tick is not missed.
 */
final class JournalIndex {

    /** One run: its directory, its head cut before the tail, and the fields read off that head. */
    record Entry(
            Path dir,
            String head,
            boolean headComplete,
            long dirMillis,
            @Nullable String id,
            long requestId,
            long sortKey,
            boolean running,
            boolean synthetic) {}

    /** What a home's runs looked like when scanned. */
    private record Home(long runsMillis, Map<Path, Entry> runs) {}

    /** Reads a run's head as {@code [head, complete]}, or null when the record is unreadable. */
    @FunctionalInterface
    interface HeadReader {
        @Nullable
        HeadText read(Path runDir);
    }

    /** A record head: the text before the tail and whether the tail mark was reached. */
    record HeadText(String text, boolean complete) {}

    private final Path buildsRoot;
    private final HeadReader reader;
    private final Map<Path, Home> homes = new HashMap<>();
    private final Set<Path> touched = new HashSet<>();
    private List<Entry> newest = List.of();
    private Map<String, Entry> byId = Map.of();
    private Map<Long, Entry> byRequestId = Map.of();

    /** Head reads since construction: the cost seam tests measure. */
    private long headReads;

    JournalIndex(Path buildsRoot, HeadReader reader) {
        this.buildsRoot = buildsRoot;
        this.reader = reader;
    }

    /** Every run, newest first by finish (else start) time, synthetic ones included. */
    synchronized List<Entry> newest() {
        refresh();
        return newest;
    }

    synchronized Optional<Entry> byId(String id) {
        refresh();
        return Optional.ofNullable(byId.get(id));
    }

    synchronized Optional<Entry> byRequestId(long requestId) {
        refresh();
        return Optional.ofNullable(byRequestId.get(requestId));
    }

    /** {@code runDir} was written or removed by this journal: read it again on the next ask. */
    synchronized void touched(Path runDir) {
        touched.add(runDir.toAbsolutePath().normalize());
        Path home = homeOf(runDir);
        if (home != null) homes.computeIfPresent(home, (h, state) -> new Home(Long.MIN_VALUE, state.runs()));
    }

    /** A whole project home was removed or rewritten: rescan it on the next ask. */
    synchronized void touchedHome(Path home) {
        homes.remove(home.toAbsolutePath().normalize());
    }

    synchronized long headReads() {
        return headReads;
    }

    private void refresh() {
        boolean changed = false;
        Set<Path> seen = new HashSet<>();
        for (Path h : ProjectBuilds.listProjectHomes(buildsRoot)) {
            Path home = h.toAbsolutePath().normalize();
            seen.add(home);
            long runsMillis = millis(home.resolve(ProjectBuilds.RUNS));
            Home state = homes.get(home);
            if (state == null || state.runsMillis() != runsMillis) {
                homes.put(home, scan(home, runsMillis, state));
                changed = true;
            } else if (recheckRunning(state)) {
                changed = true;
            }
        }
        if (homes.keySet().retainAll(seen)) changed = true;
        touched.clear();
        if (changed || newest.isEmpty() && !homes.isEmpty()) rebuild();
    }

    /** A home's runs, reusing every entry whose directory did not change since it was read. */
    private Home scan(Path home, long runsMillis, @Nullable Home previous) {
        Map<Path, Entry> runs = new LinkedHashMap<>();
        for (Path r : ProjectBuilds.listRuns(home)) {
            Path dir = r.toAbsolutePath().normalize();
            long dirMillis = millis(dir);
            Entry old = previous == null ? null : previous.runs().get(dir);
            Entry entry =
                    old != null && old.dirMillis() == dirMillis && !touched.contains(dir) ? old : read(dir, dirMillis);
            if (entry != null) runs.put(dir, entry);
        }
        return new Home(runsMillis, runs);
    }

    /** Re-read each running entry whose directory moved: an engine finishing it rewrote its record. */
    private boolean recheckRunning(Home state) {
        boolean changed = false;
        for (Map.Entry<Path, Entry> e : state.runs().entrySet()) {
            Entry old = e.getValue();
            if (!old.running() && !touched.contains(old.dir())) continue;
            long dirMillis = millis(old.dir());
            if (dirMillis == old.dirMillis() && !touched.contains(old.dir())) continue;
            Entry fresh = read(old.dir(), dirMillis);
            if (fresh == null) continue;
            e.setValue(fresh);
            changed = true;
        }
        return changed;
    }

    private @Nullable Entry read(Path dir, long dirMillis) {
        headReads++;
        HeadText head = reader.read(dir);
        if (head == null) return null;
        String text = head.text();
        long sort = BuildJournal.scanLong(text, "finishedAt");
        if (sort <= 0) sort = BuildJournal.scanLong(text, "startedAt");
        return new Entry(
                dir,
                text,
                head.complete(),
                dirMillis,
                BuildJournal.scanString(text, "id"),
                BuildJournal.scanLong(text, "requestId"),
                sort,
                BuildJournal.scanTrue(text, "running"),
                BuildRecord.isSyntheticTrigger(BuildJournal.scanString(text, "trigger")));
    }

    private void rebuild() {
        List<Entry> all = new ArrayList<>();
        for (Home h : homes.values()) all.addAll(h.runs().values());
        all.sort(Comparator.comparingLong(Entry::sortKey).reversed());
        newest = List.copyOf(all);
        byId = index(all, Entry::id);
        Map<Long, Entry> rids = new HashMap<>();
        for (Entry e : all) {
            // Request ids are engine-local and restart at every engine: the newest run holds one.
            if (e.requestId() > 0) rids.putIfAbsent(e.requestId(), e);
        }
        byRequestId = rids;
    }

    private static <K> Map<K, Entry> index(List<Entry> newestFirst, Function<Entry, @Nullable K> key) {
        Map<K, Entry> out = new HashMap<>();
        for (Entry e : newestFirst) {
            K k = key.apply(e);
            if (k != null) out.putIfAbsent(k, e);
        }
        return out;
    }

    /** {@code projects/<key>} for {@code projects/<key>/runs/<run>}, or null for another shape. */
    private static @Nullable Path homeOf(Path runDir) {
        Path runs = runDir.toAbsolutePath().normalize().getParent();
        return runs == null ? null : runs.getParent();
    }

    private static long millis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }
}
