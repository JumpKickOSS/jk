// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
     * The board at one moment. {@code settled} of {@code planned} are the open plans' files (both 0
     * when no step declared a set); {@code files} bodies have landed and {@code bytes} have streamed
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

        /** The whole body arrived; what follows (checksum, placement) moves no bytes of it. */
        void finished();

        @Override
        void close();
    }

    /** A step's declared file set. Settle each file once; close the plan when the step ends. */
    public interface Plan extends AutoCloseable {

        /** One file of the set is on disk — downloaded, already present, or given up on. */
        void settled();

        @Override
        void close();
    }

    private final Clock clock;
    private final ConcurrentHashMap<Live, Boolean> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Planned, Boolean> plans = new ConcurrentHashMap<>();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder files = new LongAdder();
    private final LongAdder artifacts = new LongAdder();

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
        int settled = 0;
        int planned = 0;
        for (Planned p : plans.keySet()) {
            settled += Math.min(p.settled.get(), p.items);
            planned += p.items;
        }
        return new Snapshot(settled, planned, (int) files.sum(), bytes.sum(), inflight, rows);
    }

    private final class Live implements Transfer {
        final String label;
        final boolean artifact;
        volatile long startedNanos;
        volatile long total = -1L;
        volatile long read;

        Live(String label, boolean artifact) {
            this.label = label;
            this.artifact = artifact;
        }

        @Override
        public void started(long totalBytes) {
            read = 0;
            total = totalBytes;
            startedNanos = clock.nanos();
            live.put(this, Boolean.TRUE);
        }

        @Override
        public void advanced(long readBytes) {
            long delta = readBytes - read;
            if (delta > 0) bytes.add(delta);
            read = readBytes;
        }

        @Override
        public void finished() {
            if (live.remove(this) != null) {
                files.increment();
                if (artifact) artifacts.increment();
            }
        }

        @Override
        public void close() {
            live.remove(this);
        }
    }

    private final class Planned implements Plan {
        final int items;
        final AtomicInteger settled = new AtomicInteger();

        Planned(int items) {
            this.items = items;
        }

        @Override
        public void settled() {
            settled.incrementAndGet();
        }

        @Override
        public void close() {
            plans.remove(this);
        }
    }
}
