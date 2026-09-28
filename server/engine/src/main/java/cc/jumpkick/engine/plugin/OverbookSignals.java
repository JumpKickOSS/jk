// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Live memory signals a {@link WorkerLeases.Ledger} reads before granting a lease the reservation
 * budget cannot hold. The sample is host pressure, and free bytes that already count running forks
 * at their real usage.
 *
 * <p>Readings are reused for {@link #CACHE_NANOS}. A grant checks the cached sample under the ledger
 * lock; the cache is what keeps that check from reading {@code /proc} on every fork.
 */
public final class OverbookSignals {

    private OverbookSignals() {}

    /**
     * {@code 0} (also {@code false}, {@code no}, {@code off}) turns overbooking off for this engine
     * process. Unset leaves it on, subject to the host signals and {@code CI}. Read from the engine
     * process: {@code jk engine stop} first.
     */
    public static final String ENV = "JK_OVERBOOK";

    /** How long one sample of free memory and pressure is reused. */
    public static final long CACHE_NANOS = 250_000_000L;

    /**
     * {@code some avg10} must be strictly below this. The number is the kernel's percentage, the
     * same figure {@code /proc/pressure/memory} prints.
     */
    public static final double SOME_AVG10_LIMIT = 5.0;

    /** {@code full avg10} must be strictly below this, on the same percentage scale. */
    public static final double FULL_AVG10_LIMIT = 1.0;

    private static final Path MEMINFO = Path.of("/proc/meminfo");
    private static final Path PRESSURE = Path.of("/proc/pressure/memory");

    private static final Reading CLOSED = new Reading(false, -1L, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

    /**
     * One sample. {@code freeBytes} is {@code -1} when it could not be read. Pressure is
     * {@link Double#POSITIVE_INFINITY} when it could not be read, which is not low.
     */
    public record Reading(boolean enabled, long freeBytes, double someAvg10, double fullAvg10) {

        /** Overbooking must not happen. */
        public static Reading closed() {
            return CLOSED;
        }

        /** Both averages are strictly under their limits. */
        public boolean pressureLow() {
            return someAvg10 < SOME_AVG10_LIMIT && fullAvg10 < FULL_AVG10_LIMIT;
        }

        /** {@code freeBytes} covers {@code leaseBytes} plus {@code headroomBytes}, without overflow. */
        public boolean covers(long leaseBytes, long headroomBytes) {
            if (freeBytes < 0 || leaseBytes < 0 || headroomBytes < 0) return false;
            if (leaseBytes > freeBytes) return false;
            return headroomBytes <= freeBytes - leaseBytes;
        }
    }

    /** What the ledger calls under its lock. Implementations must be cheap when the sample is fresh. */
    @FunctionalInterface
    public interface Source {
        Reading read();
    }

    /** A source that never allows an overbooked grant. */
    public static Source off() {
        return () -> CLOSED;
    }

    /** The process's signals, reused for {@link #CACHE_NANOS}. */
    public static Source live() {
        return caching(OverbookSignals::measure, Clock.SYSTEM::nanos, CACHE_NANOS);
    }

    /** {@code raw}, reused until {@code clock} moves {@code ttlNanos}. */
    public static Source caching(Source raw, LongSupplier clock, long ttlNanos) {
        return new Cached(raw, clock, ttlNanos);
    }

    /**
     * Whether overbooking may be considered at all. {@code ci} and {@code knob} are the raw values,
     * not the variable names. A set {@code CI} (the jk-wide truth set) disables it, and so does an
     * explicit off on the knob. Unrecognized knob text leaves the default, which is on.
     */
    static boolean allowed(
            boolean linux,
            boolean cgroup,
            boolean pressureReadable,
            boolean availableReadable,
            @Nullable String ci,
            @Nullable String knob) {
        if (!linux) return false;
        if (EnvValues.isCi(name -> "CI".equals(name) ? ci : null)) return false;
        if (!EnvValues.parseBool(knob).orElse(true)) return false;
        if (cgroup) return true;
        return pressureReadable && availableReadable;
    }

    /** This process's sample. Never throws. */
    static Reading measure() {
        try {
            return sample();
        } catch (RuntimeException e) {
            Log.debug("overbook signals: unreadable", e);
            return CLOSED;
        }
    }

    private static Reading sample() {
        if (!Os.isLinux()) return CLOSED;
        String ci = System.getenv("CI");
        String knob = System.getenv(ENV);
        Path workers = WorkerContainment.report().mode() == WorkerContainment.Mode.CGROUP
                ? WorkerContainment.workersGroup()
                : null;
        boolean cgroup = workers != null;
        long free = -1L;
        boolean availableReadable = false;
        if (workers != null) {
            free = cgroupFree(readText(workers.resolve("memory.max")), readText(workers.resolve("memory.current")));
        } else {
            String meminfo = readText(MEMINFO);
            if (meminfo != null) {
                long avail = MemoryProbe.meminfoValueBytes(meminfo, "MemAvailable");
                if (avail >= 0) {
                    availableReadable = true;
                    free = avail;
                }
            }
        }
        Pressure host = parsePressure(readText(PRESSURE));
        if (workers != null) {
            String groupText = readText(workers.resolve("memory.pressure"));
            if (groupText != null) {
                Pressure group = parsePressure(groupText);
                host = group == null ? null : worse(host, group);
            }
        }
        boolean pressureReadable = host != null && Double.isFinite(host.some) && Double.isFinite(host.full);
        boolean enabled = allowed(true, cgroup, pressureReadable, availableReadable, ci, knob);
        if (host == null || !pressureReadable) {
            return new Reading(enabled, free, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        return new Reading(enabled, free, host.some, host.full);
    }

    /** {@code memory.max - memory.current}, or {@code -1} when either side is missing or unlimited. */
    static long cgroupFree(@Nullable String maxText, @Nullable String currentText) {
        long max = parseLimit(maxText);
        long current = parseLimit(currentText);
        if (max < 0 || current < 0 || current > max) return -1L;
        return max - current;
    }

    /** The worse of the two, or {@code null} when the host sample is missing. */
    static @Nullable Pressure worse(@Nullable Pressure host, @Nullable Pressure group) {
        if (host == null) return null;
        if (group == null) return host;
        return new Pressure(Math.max(host.some, group.some), Math.max(host.full, group.full));
    }

    /**
     * {@code some} and {@code full} {@code avg10} from a pressure file. Null when either line is
     * missing. A workers-group file that is present but does not parse is not low.
     */
    static @Nullable Pressure parsePressure(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        Double some = null;
        Double full = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("some ")) some = avg10(line);
            else if (line.startsWith("full ")) full = avg10(line);
        }
        if (some == null || full == null) return null;
        return new Pressure(some, full);
    }

    /** One pressure file's {@code avg10} pair. */
    record Pressure(double some, double full) {}

    private static @Nullable Double avg10(String line) {
        int at = line.indexOf("avg10=");
        if (at < 0) return null;
        int start = at + "avg10=".length();
        int end = start;
        while (end < line.length()) {
            char c = line.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.') end++;
            else break;
        }
        if (end == start) return null;
        try {
            return Double.parseDouble(line.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code -1} for absent, blank, {@code max}, or a value the kernel uses as unlimited. */
    private static long parseLimit(@Nullable String text) {
        if (text == null) return -1L;
        String s = text.trim();
        if (s.isEmpty() || "max".equals(s)) return -1L;
        try {
            long v = Long.parseLong(s);
            if (v <= 0 || v >= MemoryProbe.CGROUP_UNLIMITED) return -1L;
            return v;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Null when the file cannot be read. Cgroup and proc files often report a size of zero, so this
     * reads the stream rather than trusting that size.
     */
    private static @Nullable String readText(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static final class Cached implements Source {
        private final Source raw;
        private final LongSupplier clock;
        private final long ttlNanos;
        private volatile @Nullable Reading cached;
        private volatile long atNanos;

        Cached(Source raw, LongSupplier clock, long ttlNanos) {
            this.raw = raw;
            this.clock = clock;
            this.ttlNanos = ttlNanos;
        }

        @Override
        public Reading read() {
            long now = clock.getAsLong();
            Reading hit = cached;
            if (hit != null && fresh(now)) return hit;
            synchronized (this) {
                now = clock.getAsLong();
                hit = cached;
                if (hit != null && fresh(now)) return hit;
                Reading fresh = raw.read();
                atNanos = now;
                cached = fresh;
                return fresh;
            }
        }

        private boolean fresh(long now) {
            return now >= atNanos && now - atNanos < ttlNanos;
        }
    }
}
