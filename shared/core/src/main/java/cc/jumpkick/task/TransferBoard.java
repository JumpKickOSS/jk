// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The downloads of one request as they stream: what a live display draws, whichever verb, phase or
 * caller asked for the bytes. Owned by the request's {@link IoLedger}, so it has the ledger's scope —
 * the runner thread and every {@code JkThreads} pool task the request hands work to — and deep code
 * reaches it the way it reaches the ledger: {@code SessionContext.current().io().transfers()}.
 *
 * <p>Two kinds of report land here. A {@link Transfer} is one body streaming to disk: the code that
 * copies bytes ({@code DownloadLeg}, the tool and JDK installers) opens one, reports its declared
 * length and running count, and closes it. A {@link Plan} is a step that knows the set of files it
 * needs — the lock's download pass, the sync — declaring its size and settling each file, downloaded
 * or already present, so the display can say {@code 120/183}. Downloads outside any plan (a plugin,
 * a tool) still count in bytes and rows.
 *
 * <p>Reporting is cheap enough for every 16 KiB chunk: a volatile write and a {@link LongAdder} add,
 * never a lock the download threads share. {@link #snapshot} is read by a sampler a few times a
 * second, never per chunk.
 *
 * <p>A transfer gets a row only once it has run for {@link #DWELL_NANOS}: most jars land in tens of
 * milliseconds, and a row per one of them would be flicker. Rows stay in the order their transfers
 * began, so a row never jumps when a neighbor finishes, and a row leaves as soon as its body has
 * landed — the checksum reads that follow wait on the host's permits, and a row parked at 100% for
 * them would hide a transfer still moving.
 */
public final class TransferBoard {

    /** How long a transfer runs before it is shown. */
    public static final long DWELL_NANOS = 250_000_000L;

    /** The most rows a snapshot carries — the live region's row cap. */
    public static final int MAX_ROWS = 8;

    /** One body in flight: what it is ({@code g:a:v}), bytes read, declared length or {@code -1}. */
    public record Row(String label, long read, long total) {}

    /**
     * The board at one moment. {@code settled} of {@code planned} are the files the run's declared
     * sets download: a set counts once one of its own files was downloaded, {@code planned} leaves
     * out the files a set found already on disk, and a closed set stays counted by what it
     * downloaded. Both 0 when no declared set downloaded anything. {@code files} bodies have landed and {@code bytes} have streamed
     * this request; {@code inflight} bodies are streaming, of which {@code rows} — at most {@link
     * #MAX_ROWS}, oldest first — ran past the dwell.
     */
    public record Snapshot(int settled, int planned, int files, long bytes, int inflight, List<Row> rows) {

        /** Nothing has moved: a request that downloads nothing never shows anything. */
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0L, 0, List.of());

        public Snapshot {
            rows = List.copyOf(rows);
        }

        /**
         * True while the run is downloading: bytes have moved and a body is streaming, or a step's
         * declared set is still settling. A run whose files were all on disk moves no bytes and is
         * never downloading, so a warm sync shows nothing.
         */
        public boolean downloading() {
            return bytes > 0 && (inflight > 0 || settled < planned);
        }
    }

    /** One body streaming to disk. Close it however the stream ends; closing twice is harmless. */
    public interface Transfer extends AutoCloseable {

        /** The body began to arrive; {@code totalBytes} is {@code -1} when no length was declared. */
        void started(long totalBytes);

        /** Cumulative bytes of the body read so far. */
        void advanced(long readBytes);

        /**
         * The whole body is in: its row leaves the board now, since what follows (a checksum read
         * that may wait on the host's permits, placement) moves no bytes of it, and a row parked at
         * 100% would hide transfers still moving. Whether the file counts as landed is {@link
         * #finished}'s to say.
         */
        default void landed() {}

        /** The file is in and kept: counted as landed. A transfer closed first is not counted. */
        void finished();

        @Override
        void close();
    }

    /** A step's declared file set. Settle each file once; close the plan when the step ends. */
    public interface Plan extends AutoCloseable {

        /**
         * One file of the set is settled: {@code downloaded} when this step fetched its bytes, else
         * it was already on disk or given up on. Only downloads make the set a download.
         */
        void settled(boolean downloaded);

        @Override
        void close();
    }

    private final Clock clock;
    private final ConcurrentHashMap<Live, Boolean> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Planned, Boolean> plans = new ConcurrentHashMap<>();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder files = new LongAdder();
    private final LongAdder artifacts = new LongAdder();
    private final Set<String> artifactLabels = ConcurrentHashMap.newKeySet();
    /** Files the declared sets that closed downloaded; they stay in the run's count. */
    private final AtomicInteger closedItems = new AtomicInteger();

    public TransferBoard(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A transfer labeled {@code label}; nothing shows until {@link Transfer#started}. */
    public Transfer open(String label) {
        return new Live(Objects.requireNonNull(label, "label"), false);
    }

    /**
     * As {@link #open}, for a dependency's own file (its jar or aar, not its POM or metadata): the
     * run counts those apart, so it can say how many artifacts it downloaded.
     */
    public Transfer openArtifact(String label) {
        return new Live(Objects.requireNonNull(label, "label"), true);
    }

    /** Artifacts whose bodies landed this run, whichever step or caller downloaded them. */
    public long artifactsLanded() {
        return artifacts.sum();
    }

    /** Whether the artifact {@code label} ({@code g:a:v}) landed this run. */
    public boolean landed(String label) {
        return artifactLabels.contains(label);
    }

    /**
     * The labels ({@code g:a:v}) of the artifacts that landed this run, so a caller can tell its own
     * files from the rest: a sync counts the lock's rows among them, not a toolchain's jars.
     */
    public Set<String> artifactLabelsLanded() {
        return Set.copyOf(artifactLabels);
    }

    /**
     * What the bodies streaming now still owe: {@code owed} bytes of the ones that declared a length,
     * {@code read} bytes already in of all of them, and {@code undeclared} bodies whose length is
     * unknown.
     */
    public record InFlight(long owed, long read, int undeclared) {}

    /** The bodies streaming now, in bytes; see {@link InFlight}. */
    public InFlight inFlight() {
        long owed = 0;
        long read = 0;
        int undeclared = 0;
        for (Live t : live.keySet()) {
            long r = t.read;
            long total = t.total;
            read += r;
            if (total > 0) owed += Math.max(0, total - r);
            else undeclared++;
        }
        return new InFlight(owed, read, undeclared);
    }

    /** A step that will settle {@code items} files. */
    public Plan plan(int items) {
        Planned p = new Planned(Math.max(0, items));
        plans.put(p, Boolean.TRUE);
        return p;
    }

    /** The board now. */
    public Snapshot snapshot() {
        long now = clock.nanos();
        List<Live> shown = new ArrayList<>();
        int inflight = 0;
        for (Live t : live.keySet()) {
            inflight++;
            if (now - t.startedNanos >= DWELL_NANOS) shown.add(t);
        }
        shown.sort(Comparator.comparingLong((Live t) -> t.startedNanos).thenComparing(t -> t.label));
        List<Row> rows = new ArrayList<>(Math.min(shown.size(), MAX_ROWS));
        for (Live t : shown) {
            if (rows.size() == MAX_ROWS) break;
            rows.add(new Row(t.label, t.read, t.total));
        }
        // A declared set counts by its own downloads: a step settling files that are all on disk is
        // not downloading, whatever else the run fetches beside it. A closed set stays counted by
        // what it downloaded, so a nested set (a closure's depth inside the sync) never makes the
        // run's count go down.
        // Under the lock a set's close takes, so a set closing now is counted once: open or closed.
        int settled;
        int planned;
        synchronized (plans) {
            settled = closedItems.get();
            planned = closedItems.get();
            for (Planned p : plans.keySet()) {
                int downloaded = p.downloaded.get();
                if (downloaded == 0) continue;
                int toDownload = Math.max(0, p.items - p.found.get());
                settled += Math.min(downloaded, toDownload);
                planned += toDownload;
            }
        }
        return new Snapshot(settled, planned, (int) files.sum(), bytes.sum(), inflight, rows);
    }

    private final class Live implements Transfer {
        final String label;
        final boolean artifact;
        volatile long startedNanos;
        volatile long total = -1L;
        volatile long read;
        final AtomicBoolean done = new AtomicBoolean();
        volatile boolean started;

        Live(String label, boolean artifact) {
            this.label = label;
            this.artifact = artifact;
        }

        @Override
        public void started(long totalBytes) {
            read = 0;
            total = totalBytes;
            startedNanos = clock.nanos();
            started = true;
            live.put(this, Boolean.TRUE);
        }

        @Override
        public void advanced(long readBytes) {
            long delta = readBytes - read;
            if (delta > 0) bytes.add(delta);
            read = readBytes;
        }

        @Override
        public void landed() {
            live.remove(this);
        }

        @Override
        public void finished() {
            if (!started || !done.compareAndSet(false, true)) return;
            live.remove(this);
            files.increment();
            if (artifact) {
                artifacts.increment();
                artifactLabels.add(label);
            }
        }

        @Override
        public void close() {
            done.set(true);
            live.remove(this);
        }
    }

    private final class Planned implements Plan {
        final int items;
        final AtomicInteger downloaded = new AtomicInteger();
        final AtomicInteger found = new AtomicInteger();

        Planned(int items) {
            this.items = items;
        }

        @Override
        public void settled(boolean fetched) {
            (fetched ? downloaded : found).incrementAndGet();
        }

        @Override
        public void close() {
            synchronized (plans) {
                if (plans.remove(this) != null) closedItems.addAndGet(downloaded.get());
            }
        }
    }
}
