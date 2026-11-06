// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongUnaryOperator;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The worker bytes every engine of one state directory holds, so that the budget and its {@link
 * WorkerLeases#OVERBOOK_CAP_FACTOR} cap bound the host rather than one process: a displaced engine
 * still draining and the successor that took over lease from one total.
 *
 * <p>{@value #FILE} under the engine directory has one line per engine holding anything, {@code
 * <pid> <start-epoch-millis> <bytes>}, and is replaced whole under {@code worker-leases.lock}. Each
 * engine writes only its own line, and only when its charged total changed. A reader drops every
 * line whose process is gone or whose pid now names a process started at another time, so a crashed
 * engine's bytes come back to the next engine that reads the file.
 *
 * <p>When the file cannot be read or written the engine leases against the other engines' last
 * total it read, and says so once in the engine log.
 */
final class HostLeases {

    /** The shared file's name under the engine directory. */
    static final String FILE = "worker-leases";

    /** A cached reading of the other engines is reused for this long to refuse a lease. */
    private static final long FRESH_NANOS = 250_000_000L;

    private final Path file;
    private final Path lockFile;
    private final Owner self;
    private final Predicate<Owner> alive;
    private final Object state = new Object();

    /** Guarded by {@link #state}: the bytes this engine last wrote, or {@code 0} with no line. */
    private long published;

    private long others;
    private long readAtNanos;
    private boolean read;
    private boolean warned;

    /** {@code self}'s line in {@code engineDir}'s file; {@code alive} decides which other lines still count. */
    HostLeases(Path engineDir, Owner self, Predicate<Owner> alive) {
        this.file = engineDir.resolve(FILE);
        this.lockFile = engineDir.resolve(FILE + ".lock");
        this.self = Objects.requireNonNull(self, "self");
        this.alive = Objects.requireNonNull(alive, "alive");
    }

    /** This process's line in {@code engineDir}'s file, judging the others by {@link Owner#alive}. */
    static HostLeases forThisProcess(Path engineDir) {
        return new HostLeases(engineDir, Owner.current(), Owner::alive);
    }

    /**
     * Under the file lock: hand the other engines' live total to {@code mine}, which answers this
     * engine's charged bytes, and record them. Returns that total.
     */
    long exchange(LongUnaryOperator mine) {
        synchronized (state) {
            try {
                return FileLocks.withLock(lockFile, () -> exchangeLocked(mine));
            } catch (IOException e) {
                if (!warned) {
                    warned = true;
                    Log.warn("jk engine: worker leases of other engines unreadable at " + file
                            + "; leasing against their last known total: " + e.getMessage());
                }
                long last = others;
                mine.applyAsLong(last);
                return last;
            }
        }
    }

    /** Record this engine's charged bytes; no file access when they have not changed. */
    void publish(long bytes) {
        synchronized (state) {
            if (bytes == published && read) return;
        }
        exchange(o -> bytes);
    }

    /** The other engines' total, read now. */
    long freshOthers() {
        synchronized (state) {
            long mine = published;
            return exchange(o -> mine);
        }
    }

    /** The other engines' total, read again when the last reading is more than 250 ms old. */
    long recentOthers() {
        synchronized (state) {
            if (read && Clock.SYSTEM.nanos() - readAtNanos < FRESH_NANOS) return others;
            long mine = published;
            return exchange(o -> mine);
        }
    }

    private long exchangeLocked(LongUnaryOperator mine) throws IOException {
        Map<Long, String> kept = new LinkedHashMap<>();
        boolean changed = false;
        long total = 0;
        for (String line : lines()) {
            if (line.isBlank()) continue;
            Entry e = Entry.parse(line);
            if (e == null) {
                changed = true;
                continue;
            }
            if (e.owner.pid() == self.pid()) continue;
            if (!alive.test(e.owner)) {
                changed = true;
                Log.info("jk engine: reclaimed " + WorkerLeases.format(e.bytes)
                        + " of worker leases held by engine pid " + e.owner.pid() + ", which is gone");
                continue;
            }
            kept.put(e.owner.pid(), line.trim());
            total = saturatingAdd(total, e.bytes);
        }
        long bytes = Math.max(0, mine.applyAsLong(total));
        if (bytes != published || changed || !read) {
            StringBuilder out = new StringBuilder();
            for (String line : kept.values()) out.append(line).append('\n');
            if (bytes > 0) {
                out.append(self.pid())
                        .append(' ')
                        .append(self.startedAtMillis())
                        .append(' ')
                        .append(bytes)
                        .append('\n');
            }
            AtomicWrites.replace(file, out.toString());
            published = bytes;
        }
        others = total;
        readAtNanos = Clock.SYSTEM.nanos();
        read = true;
        return total;
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
            ProcessHandle self = ProcessHandle.current();
            return new Owner(
                    self.pid(),
                    self.info().startInstant().map(Instant::toEpochMilli).orElse(0L));
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
    private record Entry(Owner owner, long bytes) {
        static @Nullable Entry parse(String line) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3) return null;
            try {
                long pid = Long.parseLong(parts[0]);
                long started = Long.parseLong(parts[1]);
                long bytes = Long.parseLong(parts[2]);
                if (pid <= 0 || bytes <= 0) return null;
                return new Entry(new Owner(pid, started), bytes);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
