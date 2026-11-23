// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;

/**
 * What every engine of one memory budget holds, so that the worker budget, its {@link
 * WorkerLeases#OVERBOOK_CAP_FACTOR} cap and the JVM slot cap bound the host rather than one process:
 * a displaced engine still draining and the successor that took over, or engines with different
 * homes in one container, lease from one total.
 *
 * <p>{@value #FILE} under the budget's directory ({@link LeaseScope}) has one line per live engine, {@code <pid>
 * <start-epoch-millis> <budget> <cpu-cap> <bytes> <jvms> <waiting>} followed by {@code
 * <pid>:<start-epoch-millis>} for each worker it forked, and is replaced whole under {@code
 * worker-leases.lock}. {@code <waiting>} counts the leases the engine has queued. Each engine writes
 * only its own line, only when it changed, and keeps it where it is: the first line's budget and
 * JVM cap are the ones every engine leases against, so an engine started with another {@code
 * JK_WORKER_BUDGET_MB} or CPU quota uses the limits of the engine that was there first, until that
 * engine is gone.
 *
 * <p>A reader drops every line whose process is gone or whose pid now names a process started at
 * another time, and stops the workers that line names which still run: their engine cannot read
 * their output, and a hung one would otherwise hold its memory with nothing counting it. A worker
 * is stopped only when its pid still names the process started when the line says.
 *
 * <p>When the file cannot be read or written the engine leases against the other engines' last
 * reading, says so once in the engine log, and its ledger offers its line again, after a pause
 * that doubles from {@value #RETRY_FIRST_MILLIS} ms up to {@value #RETRY_MOST_MILLIS} ms, until the
 * file takes it.
 */
final class HostLeases {

    /** The shared file's name under the budget's directory. */
    static final String FILE = "worker-leases";

    /** A cached reading of the other engines is reused for this long to refuse a lease. */
    private static final long FRESH_NANOS = 250_000_000L;

    /** Waiting this long or more for the file lock counts as contention in {@link SharedLeaseCounts#lockWaits}. */
    static final long CONTENDED_NANOS = 10_000_000L;

    /** The pause before a line the file did not take is offered again the first time. */
    private static final long RETRY_FIRST_MILLIS = 50;

    /** The longest pause between offers of a line the file did not take. */
    private static final long RETRY_MOST_MILLIS = 5_000;

    /**
     * What this engine records: its own budget and JVM cap, its charged bytes, its running JVMs, its
     * queued leases, its workers.
     */
    record Line(long budgetBytes, int cpuCap, long bytes, int jvms, int waiting, List<Worker> workers) {
        Line {
            workers = List.copyOf(workers);
        }
    }

    /** A forked worker: its pid and the instant it started, {@code 0} when the OS did not say. */
    record Worker(long pid, long startedAtMillis) {

        /** The process {@code pid} names now, when it started at {@link #startedAtMillis}. */
        Optional<ProcessHandle> running() {
            if (startedAtMillis <= 0 || pid <= 0 || pid > Owner.PID_MAX) return Optional.empty();
            return ProcessHandle.of(pid)
                    .filter(h -> h.isAlive()
                            && h.info()
                                    .startInstant()
                                    .map(at -> Math.abs(at.toEpochMilli() - startedAtMillis) <= Owner.START_SLACK_MS)
                                    .orElse(false));
        }

        /** Kill this worker and what it started, when it still runs. True when a signal was sent. */
        static boolean stop(Worker worker) {
            if (worker.pid == ProcessHandle.current().pid()) return false;
            Optional<ProcessHandle> handle = worker.running();
            if (handle.isEmpty()) return false;
            try {
                handle.get().descendants().forEach(ProcessHandle::destroyForcibly);
                return handle.get().destroyForcibly();
            } catch (RuntimeException e) {
                Log.debug("worker leases: could not stop worker pid " + worker.pid, e);
                return false;
            }
        }
    }

    /**
     * The other engines' charged bytes, running JVMs and queued leases, and the budget and JVM cap the
     * file records: the first live line's, this engine's own included; {@code 0} when none is.
     */
    record Reading(long bytes, int jvms, int waiting, long budgetBytes, int cpuCap) {
        static final Reading NONE = new Reading(0, 0, 0, 0, 0);
    }

    /**
     * A line this engine records, numbered in the order its ledger made it. A line numbered below the
     * one already recorded is older than what the file holds and is not written over it.
     */
    record Publication(long seq, Line line) {}

    private static final Line NOTHING = new Line(0, 0, 0, 0, 0, List.of());

    private final Path file;
    private final Path lockFile;
    private final Owner self;
    private final Predicate<Owner> alive;
    private final ToIntFunction<List<Worker>> stop;

    /**
     * Guards the fields below, and is never held across the file lock or the file's I/O: a ledger
     * reads the budget, the JVM cap and the last reading under its own lock.
     */
    private final Object state = new Object();

    /** The line this engine last wrote, or {@code null} before. */
    private @Nullable Line published;

    /** The number of {@link #published}; {@code -1} before. */
    private long publishedSeq = -1;

    private Reading last = Reading.NONE;
    private long readAtNanos;
    private boolean read;
    private boolean warned;
    private boolean budgetNoted;
    private boolean cpuCapNoted;

    /** Exchanges that waited {@link #CONTENDED_NANOS} or more for the file lock. */
    private long lockWaits;

    /** Lines of engines that were gone, whose leases or workers this engine reclaimed. */
    private long reclaims;

    /**
     * {@code self}'s line in {@code dir}'s file. {@code alive} decides which other lines still
     * count; {@code stop} is handed the workers of a line that does not and answers how many it
     * stopped.
     */
    HostLeases(Path dir, Owner self, Predicate<Owner> alive, ToIntFunction<List<Worker>> stop) {
        this.file = dir.resolve(FILE);
        this.lockFile = dir.resolve(FILE + ".lock");
        this.self = Objects.requireNonNull(self, "self");
        this.alive = Objects.requireNonNull(alive, "alive");
        this.stop = Objects.requireNonNull(stop, "stop");
    }

    /** The share {@link #forThisProcess} made last: in the engine, its own; null before. */
    private static volatile @Nullable HostLeases thisProcess;

    /** This process's line in {@code dir}'s file, judging the others by {@link Owner#alive}. */
    static HostLeases forThisProcess(Path dir) {
        HostLeases made = new HostLeases(dir, Owner.current(), Owner::alive, HostLeases::stopAll);
        thisProcess = made;
        return made;
    }

    /** What this process's share has met; {@link SharedLeaseCounts#NONE} before it shares the file. */
    static SharedLeaseCounts thisProcessCounts() {
        HostLeases h = thisProcess;
        return h == null ? SharedLeaseCounts.NONE : h.counts();
    }

    /**
     * Under the file lock: hand the other engines' reading to {@code mine}, which answers this
     * engine's line, or {@code null} to keep the one recorded, and record it. Returns that reading.
     * {@code mine} runs with the file locked and may take a ledger's lock; nothing here holds a lock
     * of this class while it runs.
     */
    Reading exchange(Function<Reading, @Nullable Publication> mine) {
        long asked = Clock.SYSTEM.nanos();
        try {
            return FileLocks.withLock(lockFile, () -> {
                if (Clock.SYSTEM.nanos() - asked >= CONTENDED_NANOS) {
                    synchronized (state) {
                        lockWaits++;
                    }
                }
                return exchangeLocked(mine);
            });
        } catch (IOException e) {
            Reading kept;
            synchronized (state) {
                if (!warned) {
                    warned = true;
                    Log.warn("jk engine: worker leases of other engines unreadable at " + file
                            + "; leasing against their last known total: " + e.getMessage());
                }
                kept = last;
            }
            mine.apply(kept);
            return kept;
        }
    }

    /**
     * Record {@code line}, number {@code seq}; no file access when it is unchanged or older than the
     * recorded one. False when the file could not be read or written, and {@code line} is not recorded.
     */
    boolean publish(long seq, Line line) {
        synchronized (state) {
            if (read && seq <= publishedSeq) return true;
            if (read && line.equals(published)) {
                publishedSeq = seq;
                return true;
            }
        }
        Publication p = new Publication(seq, line);
        try {
            exchange(o -> p);
        } catch (RuntimeException e) {
            synchronized (state) {
                if (!warned) Log.warn("jk engine: worker leases not recorded for the other engines: " + e.getMessage());
                warned = true;
            }
        }
        return recordedSeq() >= seq;
    }

    /** The number of the newest line the file is known to hold; {@code -1} before any. */
    long recordedSeq() {
        synchronized (state) {
            return publishedSeq;
        }
    }

    /**
     * Sleep before a line the file did not take is offered again, the {@code failures}th time in a
     * row: from {@value #RETRY_FIRST_MILLIS} ms, doubling up to {@value #RETRY_MOST_MILLIS} ms. False,
     * with the interrupt kept, when the thread is interrupted.
     */
    static boolean pauseBeforeRetry(int failures) {
        long millis = RETRY_FIRST_MILLIS << Math.min(Math.max(0, failures - 1), 7);
        try {
            Thread.sleep(Math.min(RETRY_MOST_MILLIS, millis));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The live engine in {@code dir}'s file that forked one of {@code ancestors}, a process that still
     * runs and started when the line says; null when none did or the file cannot be read. An engine
     * started inside another engine's worker is already charged there, its resident set counted with
     * that worker's.
     */
    static @Nullable Owner forkedOneOf(Path dir, Set<Long> ancestors) {
        String text;
        try {
            text = AtomicWrites.readString(dir.resolve(FILE));
        } catch (IOException e) {
            return null;
        }
        for (String raw : text.split("\n")) {
            Entry e = raw.isBlank() ? null : Entry.parse(raw);
            if (e == null || !e.owner.alive()) continue;
            for (Worker w : e.line.workers()) {
                if (ancestors.contains(w.pid()) && w.running().isPresent()) return e.owner;
            }
        }
        return null;
    }

    /** The other engines' reading, read now. */
    Reading fresh() {
        return exchange(o -> null);
    }

    /** The other engines' reading, read again when the last one is more than 250 ms old. */
    Reading recent() {
        synchronized (state) {
            if (read && Clock.SYSTEM.nanos() - readAtNanos < FRESH_NANOS) return last;
        }
        return fresh();
    }

    /** What this engine's exchanges have met so far. */
    SharedLeaseCounts counts() {
        synchronized (state) {
            return new SharedLeaseCounts(lockWaits, reclaims);
        }
    }

    /** The other engines' reading as last read, without touching the file; {@link Reading#NONE} before any. */
    Reading cached() {
        synchronized (state) {
            return last;
        }
    }

    /** The budget every engine leases against: the recorded one, or {@code own} before any is. */
    long budget(long own) {
        synchronized (state) {
            long recorded = last.budgetBytes();
            return read && recorded > 0 ? recorded : own;
        }
    }

    /** The JVM cap every engine leases against: the recorded one, or {@code own} before any is. */
    int cpuCap(int own) {
        synchronized (state) {
            int recorded = last.cpuCap();
            return read && recorded > 0 ? recorded : own;
        }
    }

    /**
     * Holding the file lock, which also keeps every other thread of this process out: read the
     * other lines, ask {@code mine} for this engine's, write the file when either changed.
     */
    private Reading exchangeLocked(Function<Reading, @Nullable Publication> mine) throws IOException {
        List<String> kept = new ArrayList<>();
        int selfAt = -1;
        boolean changed = false;
        long bytes = 0;
        int jvms = 0;
        int waiting = 0;
        long budget = 0;
        int cpus = 0;
        long firstOwner = 0;
        for (String raw : lines()) {
            if (raw.isBlank()) continue;
            Entry e = Entry.parse(raw);
            if (e == null) {
                changed = true;
                continue;
            }
            if (e.owner.pid() == self.pid()) {
                if (selfAt < 0) {
                    selfAt = kept.size();
                    kept.add("");
                } else changed = true;
                continue;
            }
            if (!alive.test(e.owner)) {
                changed = true;
                reclaim(e);
                continue;
            }
            if (firstOwner == 0) firstOwner = e.owner.pid();
            if (budget == 0) budget = e.line.budgetBytes();
            if (cpus == 0) cpus = e.line.cpuCap();
            kept.add(raw.trim());
            bytes = saturatingAdd(bytes, e.line.bytes());
            jvms = saturatingAdd(jvms, e.line.jvms());
            waiting = saturatingAdd(waiting, e.line.waiting());
        }
        if (selfAt < 0) {
            selfAt = kept.size();
            kept.add("");
        }
        boolean firstIsSelf = selfAt == 0;
        Reading others =
                firstIsSelf ? new Reading(bytes, jvms, waiting, 0, 0) : new Reading(bytes, jvms, waiting, budget, cpus);
        synchronized (state) {
            last = others;
            read = true;
        }
        Publication offered = mine.apply(others);
        Line before;
        Line line;
        long seq;
        synchronized (state) {
            before = published;
            if (offered != null && offered.seq() >= publishedSeq) {
                line = offered.line();
                seq = offered.seq();
            } else {
                line = before != null ? before : NOTHING;
                seq = publishedSeq;
            }
        }
        if (firstIsSelf) {
            others = new Reading(bytes, jvms, waiting, line.budgetBytes(), line.cpuCap());
        } else {
            noteRecorded(others, line, firstOwner);
        }
        if (changed || !line.equals(before)) {
            kept.set(selfAt, render(self, line));
            StringBuilder out = new StringBuilder();
            for (String l : kept) if (!l.isEmpty()) out.append(l).append('\n');
            AtomicWrites.replace(file, out.toString());
        }
        synchronized (state) {
            published = line;
            publishedSeq = seq;
            last = others;
            readAtNanos = Clock.SYSTEM.nanos();
        }
        return others;
    }

    /**
     * Under the file lock: once per engine and limit, say that another engine's budget or JVM cap is
     * the one leased against.
     */
    private void noteRecorded(Reading recorded, Line own, long owner) {
        if (!budgetNoted && recorded.budgetBytes() > 0 && recorded.budgetBytes() != own.budgetBytes()) {
            budgetNoted = true;
            Log.info("jk engine: leasing workers against the " + WorkerLeases.format(recorded.budgetBytes())
                    + " budget engine pid " + owner + " recorded, not this engine's "
                    + WorkerLeases.format(own.budgetBytes())
                    + "; one budget holds for every engine of this host until that engine is gone");
        }
        if (!cpuCapNoted && recorded.cpuCap() > 0 && recorded.cpuCap() != own.cpuCap()) {
            cpuCapNoted = true;
            Log.info("jk engine: running at most " + recorded.cpuCap() + " JVMs, the cap engine pid " + owner
                    + " recorded, not this engine's " + own.cpuCap()
                    + "; one cap holds for every engine of this host until that engine is gone");
        }
    }

    private void reclaim(Entry e) {
        int stopped = e.line.workers().isEmpty() ? 0 : stop.applyAsInt(e.line.workers());
        if (e.line.bytes() <= 0 && stopped == 0) return;
        synchronized (state) {
            reclaims++;
        }
        String line = "jk engine: reclaimed " + WorkerLeases.format(e.line.bytes())
                + " of worker leases held by engine pid " + e.owner.pid() + ", which is gone";
        if (stopped > 0) line += "; stopped " + stopped + (stopped == 1 ? " worker" : " workers") + " it left running";
        Log.info(line);
    }

    private static int stopAll(List<Worker> workers) {
        int stopped = 0;
        for (Worker w : workers) if (Worker.stop(w)) stopped++;
        return stopped;
    }

    private static String render(Owner owner, Line line) {
        StringBuilder out = new StringBuilder()
                .append(owner.pid())
                .append(' ')
                .append(owner.startedAtMillis())
                .append(' ')
                .append(Math.max(0, line.budgetBytes()))
                .append(' ')
                .append(Math.max(0, line.cpuCap()))
                .append(' ')
                .append(Math.max(0, line.bytes()))
                .append(' ')
                .append(Math.max(0, line.jvms()))
                .append(' ')
                .append(Math.max(0, line.waiting()));
        for (Worker w : line.workers()) {
            out.append(' ').append(w.pid()).append(':').append(w.startedAtMillis());
        }
        return out.toString();
    }

    private String[] lines() throws IOException {
        try {
            return AtomicWrites.readString(file).split("\n");
        } catch (NoSuchFileException absent) {
            return new String[0];
        }
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private static int saturatingAdd(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
    }

    /**
     * An engine process: its pid and, when the OS says, the instant it started, so a pid the OS
     * has handed to a later process does not keep a dead engine's line.
     */
    record Owner(long pid, long startedAtMillis) {

        /** Start-instant slack: both sides read the same clock, rounded differently. */
        private static final long START_SLACK_MS = 5_000;

        /** OS process ids are 32-bit; {@link ProcessHandle#of} on a wider value can still report alive. */
        private static final long PID_MAX = 0xFFFF_FFFFL;

        static Owner current() {
            return of(ProcessHandle.current());
        }

        static Owner of(ProcessHandle process) {
            return new Owner(
                    process.pid(),
                    process.info().startInstant().map(Instant::toEpochMilli).orElse(0L));
        }

        /** A process with this pid runs and started when the line says it did. */
        boolean alive() {
            if (pid <= 0 || pid > PID_MAX) return false;
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) return false;
            if (startedAtMillis <= 0) return true;
            Optional<Instant> started = handle.get().info().startInstant();
            return started.isEmpty() || Math.abs(started.get().toEpochMilli() - startedAtMillis) <= START_SLACK_MS;
        }
    }

    /** One engine's line. */
    private record Entry(Owner owner, Line line) {

        /** The fixed fields ahead of the workers: pid, start, budget, cap, bytes, JVMs, waiting. */
        private static final int FIELDS = 7;

        static @Nullable Entry parse(String raw) {
            String[] parts = raw.trim().split("\\s+");
            if (parts.length < FIELDS) return null;
            try {
                long pid = Long.parseLong(parts[0]);
                long started = Long.parseLong(parts[1]);
                long budget = Long.parseLong(parts[2]);
                int cpus = Integer.parseInt(parts[3]);
                long bytes = Long.parseLong(parts[4]);
                int jvms = Integer.parseInt(parts[5]);
                int waiting = Integer.parseInt(parts[6]);
                if (pid <= 0 || budget < 0 || cpus < 0 || bytes < 0 || jvms < 0 || waiting < 0) return null;
                List<Worker> workers = new ArrayList<>();
                for (int i = FIELDS; i < parts.length; i++) {
                    int colon = parts[i].indexOf(':');
                    if (colon <= 0) return null;
                    workers.add(new Worker(
                            Long.parseLong(parts[i].substring(0, colon)),
                            Long.parseLong(parts[i].substring(colon + 1))));
                }
                return new Entry(new Owner(pid, started), new Line(budget, cpus, bytes, jvms, waiting, workers));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
