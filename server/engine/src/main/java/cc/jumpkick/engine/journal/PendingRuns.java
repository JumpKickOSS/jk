// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * The runs {@link BuildJournal#beginLater} admitted whose in-flight entry is not written yet, by
 * request id. Whichever of the stub timer and the run's end comes first decides: the timer writes
 * the entry, the end settles it, and a run settled unwritten has no directory until {@link
 * BuildJournal#complete} writes it whole — or never, as a no-op's line in the run log.
 */
final class PendingRuns {

    /** One run's unwritten entry and the directory {@link BuildJournal#append} will give it. */
    static final class Entry {
        final BuildRecord running;
        final Path planned;
        private boolean settled;
        private boolean begun;
        /** The first line of a transcript the engine writes itself (a detached run's); see {@link #hold}. */
        private @Nullable String transcriptHeader;

        Entry(BuildRecord running, Path planned) {
            this.running = running;
            this.planned = planned;
        }

        synchronized @Nullable String transcriptHeader() {
            return transcriptHeader;
        }

        /** Write the in-flight entry unless the run has ended; true when this call decided. */
        private synchronized boolean materialize(BiConsumer<BuildRecord, @Nullable String> begin) {
            if (settled) return false;
            begin.accept(running, transcriptHeader);
            begun = true;
            settled = true;
            return true;
        }

        /** Keep {@code header} for the directory; false when the entry is written, or never will be. */
        private synchronized boolean hold(String header) {
            if (settled) return false;
            transcriptHeader = header;
            return true;
        }

        /** The run has ended: true when its entry was never written, so none is on disk. */
        private synchronized boolean settle() {
            boolean unwritten = !begun;
            settled = true;
            return unwritten;
        }
    }

    private final ConcurrentHashMap<Long, Entry> pending = new ConcurrentHashMap<>();

    /**
     * Requests whose entry was settled unwritten. Kept until the run's entry is written, so a
     * retried {@link BuildJournal#complete} still knows the run has no directory: its number must
     * never be looked up across projects, where another project's run of that number would answer.
     */
    private final ConcurrentHashMap<Long, Entry> unwritten = new ConcurrentHashMap<>();

    private final BiConsumer<BuildRecord, @Nullable String> begin;

    /** {@code begin} writes a run's in-flight entry, opening its transcript with the header when given. */
    PendingRuns(BiConsumer<BuildRecord, @Nullable String> begin) {
        this.begin = begin;
    }

    /** Hold {@code running}'s entry for {@code delayMillis}, then write it if the run is still going. */
    void defer(long requestId, BuildRecord running, Path planned, long delayMillis) {
        Entry entry = new Entry(running, planned);
        pending.put(requestId, entry);
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(() -> {
            if (entry.materialize(begin)) pending.remove(requestId, entry);
        });
    }

    /** Whether a pending run will have {@code planned} as its directory. */
    boolean plannedAt(Path planned) {
        for (Entry e : pending.values()) {
            if (e.planned.equals(planned)) return true;
        }
        return false;
    }

    /** {@link BuildJournal#holdTranscriptHeader}. */
    boolean hold(long requestId, String header) {
        Entry entry = requestId > 0 ? pending.get(requestId) : null;
        return entry != null && entry.hold(header);
    }

    /** Settle every pending run planned at {@code planned}: a run deleted before it began never begins. */
    void settleAt(Path planned) {
        pending.forEach((id, e) -> {
            if (e.planned.equals(planned) && pending.remove(id, e)) e.settle();
        });
    }

    /** Settle {@code requestId}'s run; its entry when that was never written, else {@code null}. */
    @Nullable
    Entry settle(long requestId) {
        if (requestId <= 0) return null;
        Entry entry = pending.remove(requestId);
        if (entry != null && entry.settle()) {
            if (unwritten.size() >= 1_024) unwritten.clear();
            unwritten.put(requestId, entry);
        }
        return unwritten.get(requestId);
    }

    /** {@code requestId}'s run has its entry now, or never will. */
    void written(long requestId) {
        unwritten.remove(requestId);
    }

    /**
     * Append {@code finished}'s line to the run log beside {@code planned}'s {@code runs/}: {@code
     * <build-number> <finished-at UTC> <kind> <millis> <trigger>}, tab-separated. Best-effort.
     */
    static void logNoop(Path planned, BuildRecord finished) {
        try {
            Path runs = Objects.requireNonNull(planned.getParent(), "run dir has no runs/");
            Path home = Objects.requireNonNull(runs.getParent(), "runs/ has no project home");
            String line = finished.buildNumber() + "\t"
                    + Instant.ofEpochMilli(finished.finishedAt() > 0 ? finished.finishedAt() : Clock.SYSTEM.millis())
                    + "\t" + oneField(finished.kind())
                    + "\t" + finished.millis()
                    + "\t" + oneField(finished.trigger())
                    + "\n";
            // One write under O_APPEND: a crash can only cut this line short, never another.
            OwnerOnlyFiles.writeString(
                    home.resolve(BuildJournal.RUN_LOG),
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            Log.debug("run log append: best-effort", e);
        }
    }

    private static String oneField(@Nullable String s) {
        if (s == null || s.isBlank()) return "-";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
