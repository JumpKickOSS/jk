// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Live memory signals a {@link WorkerLeases.Ledger} reads before granting a lease the reservation
 * budget cannot hold. The sample is host pressure, and free bytes that already count running forks
 * at their real usage. On Linux that is the host's {@code MemAvailable}, which sees the workers of
 * every engine, in cgroup mode no more than this engine's workers group has left under its {@code
 * memory.max}, and PSI pressure. On Windows and macOS it is the host's available physical memory
 * ({@link MemoryProbe#current}); macOS adds the kernel's memory pressure level, and Windows has no
 * pressure signal, so the free memory alone decides.
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

    /** This process's own cgroup: in a container, the container's limit and pressure. */
    private static final Path OWN_MAX = Path.of("/sys/fs/cgroup/memory.max");

    private static final Path OWN_CURRENT = Path.of("/sys/fs/cgroup/memory.current");
    private static final Path OWN_STAT = Path.of("/sys/fs/cgroup/memory.stat");
    private static final Path OWN_PRESSURE = Path.of("/sys/fs/cgroup/memory.pressure");

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
     * True when this engine was started with {@code CI} set: overbooking keeps {@linkplain
     * #headroom(long, boolean) more headroom}, stops at a {@linkplain #ceiling(long, boolean) lower
     * cap}, and on Linux needs a pressure reading. A kill on an unattended runner costs a retry or
     * the job.
     */
    public static final boolean CAUTIOUS = EnvValues.isCi(ProcessEnv::get);

    /** True when this process was started with {@code JK_OVERBOOK} off. Read from the engine process. */
    public static boolean policyOff() {
        return policyOff(ProcessEnv.get(ENV));
    }

    /** {@code knob} is the raw value of {@code JK_OVERBOOK}. */
    static boolean policyOff(@Nullable String knob) {
        return !EnvValues.parseBool(knob).orElse(true);
    }

    /**
     * Whether a Linux engine may overbook at all: not with the knob off; outside a cgroup only when
     * pressure and the free memory are both readable; a {@code cautious} one needs pressure even
     * inside its workers group.
     */
    static boolean allowed(
            boolean cgroup,
            boolean pressureReadable,
            boolean availableReadable,
            boolean cautious,
            @Nullable String knob) {
        if (policyOff(knob)) return false;
        if (cgroup) return !cautious || pressureReadable;
        return pressureReadable && availableReadable;
    }

    /** {@link #ceiling(long, boolean)} for this engine. */
    static long ceiling(long cap) {
        return ceiling(cap, CAUTIOUS);
    }

    /**
     * What the reserved total, and the heaps of the running forks, stay under: {@link
     * WorkerLeases#OVERBOOK_CAP_FACTOR} times {@code cap}, one and a half times when {@code
     * cautious}; saturating.
     */
    static long ceiling(long cap, boolean cautious) {
        long budget = Math.max(0, cap);
        if (budget > Long.MAX_VALUE / WorkerLeases.OVERBOOK_CAP_FACTOR) return Long.MAX_VALUE;
        return cautious ? budget + budget / 2 : budget * WorkerLeases.OVERBOOK_CAP_FACTOR;
    }

    /** {@link #headroom(long, boolean)} for this engine. */
    public static long headroom(long budgetBytes) {
        return headroom(budgetBytes, CAUTIOUS);
    }

    /**
     * Free memory that must remain after an overbooked lease's worst case: the larger of 1 GiB and
     * 10% of {@code budgetBytes}, or of 2 GiB and 20% when {@code cautious}.
     */
    static long headroom(long budgetBytes, boolean cautious) {
        long share = budgetBytes > 0 ? budgetBytes / (cautious ? 5 : 10) : 0;
        return Math.max(cautious ? 2L << 30 : 1L << 30, share);
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
        String knob = ProcessEnv.get(ENV);
        if (!Os.isLinux()) return elsewhere(MemoryProbe.current().availableBytes(), MacPressure.level(), knob);
        Path workers = WorkerContainment.report().mode() == WorkerContainment.Mode.CGROUP
                ? WorkerContainment.workersGroup()
                : null;
        boolean cgroup = workers != null;
        long available = -1L;
        String meminfo = readText(MEMINFO);
        if (meminfo != null) available = MemoryProbe.meminfoValueBytes(meminfo, "MemAvailable");
        long own = cgroupFree(readText(OWN_MAX), readText(OWN_CURRENT), readText(OWN_STAT));
        if (own >= 0) available = available >= 0 ? Math.min(available, own) : own;
        boolean availableReadable = available >= 0;
        long free = workers != null
                ? hostFree(
                        cgroupFree(
                                readText(workers.resolve("memory.max")),
                                readText(workers.resolve("memory.current")),
                                readText(workers.resolve("memory.stat"))),
                        available)
                : available;
        Pressure host = parsePressure(readText(PRESSURE));
        Pressure ownPressure = parsePressure(readText(OWN_PRESSURE));
        if (ownPressure != null) host = host == null ? ownPressure : worse(host, ownPressure);
        if (workers != null) {
            String groupText = readText(workers.resolve("memory.pressure"));
            if (groupText != null) {
                Pressure group = parsePressure(groupText);
                host = group == null ? null : worse(host, group);
            }
        }
        boolean pressureReadable = host != null && Double.isFinite(host.some) && Double.isFinite(host.full);
        boolean enabled = allowed(cgroup, pressureReadable, availableReadable, CAUTIOUS, knob);
        if (host == null || !pressureReadable) {
            return new Reading(enabled, free, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        return new Reading(enabled, free, host.some, host.full);
    }

    /**
     * A Windows or macOS sample: {@code availableBytes} free, and macOS's pressure {@code level}
     * ({@code 1} normal, {@code 2} warning, {@code 4} critical; {@code 0} where there is none). A
     * level past normal reads as pressure that is not low.
     */
    static Reading elsewhere(long availableBytes, int level, @Nullable String knob) {
        boolean enabled = !policyOff(knob) && availableBytes > 0;
        double some = level <= MacPressure.NORMAL
                ? 0
                : level == MacPressure.WARN ? SOME_AVG10_LIMIT : Double.POSITIVE_INFINITY;
        return new Reading(enabled, availableBytes, some, 0);
    }

    /**
     * {@code memory.max} less what the group uses: {@code memory.current} without the inactive file
     * cache {@code memory.stat} names, which the kernel drops before it kills anything (the working
     * set Kubernetes acts on). {@code -1} when either limit is missing or unlimited.
     */
    static long cgroupFree(@Nullable String maxText, @Nullable String currentText, @Nullable String statText) {
        long max = parseLimit(maxText);
        long current = parseLimit(currentText);
        if (max < 0 || current < 0) return -1L;
        long used = Math.max(0, current - MemoryProbe.inactiveFileBytes(statText));
        return used > max ? -1L : max - used;
    }

    /**
     * Free bytes in cgroup mode: the workers group's {@code cgroupFree}, lowered to the host's
     * {@code available} when that is readable. {@code -1} when the group has no room to report.
     */
    static long hostFree(long cgroupFree, long available) {
        if (cgroupFree < 0) return -1L;
        return available >= 0 ? Math.min(cgroupFree, available) : cgroupFree;
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

    /** macOS's {@code kern.memorystatus_vm_pressure_level}, read with {@code sysctlbyname}. */
    static final class MacPressure {
        static final int NORMAL = 1;
        static final int WARN = 2;

        private static final @Nullable MethodHandle SYSCTL = Os.isDarwin() ? load() : null;

        private MacPressure() {}

        /** The level, or {@code 0} off macOS or when it cannot be read. */
        static int level() {
            MethodHandle sysctl = SYSCTL;
            if (sysctl == null) return 0;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment value = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
                size.set(ValueLayout.JAVA_LONG, 0, Integer.BYTES);
                MemorySegment name = arena.allocateFrom("kern.memorystatus_vm_pressure_level");
                int rc = (int) sysctl.invokeExact(name, value, size, MemorySegment.NULL, 0L);
                return rc == 0 ? value.get(ValueLayout.JAVA_INT, 0) : 0;
            } catch (Throwable t) {
                Log.debug("memory pressure level: unreadable", t);
                return 0;
            }
        }

        private static @Nullable MethodHandle load() {
            try {
                Linker linker = Linker.nativeLinker();
                return linker.downcallHandle(
                        linker.defaultLookup().find("sysctlbyname").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG));
            } catch (Throwable unavailable) {
                Log.debug("memory pressure level: sysctlbyname unavailable", unavailable);
                return null;
            }
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

    /** {@link #overbookedLine(long, long, long, Reading, boolean)} naming PSI on Linux only. */
    static String overbookedLine(long grantBytes, long leasedBytes, long budgetBytes, Reading reading) {
        return overbookedLine(grantBytes, leasedBytes, budgetBytes, reading, Os.isLinux());
    }

    /**
     * The engine-log line for one overbooked grant, after the bytes have been taken: what is leased
     * against the budget and its cap, and the sample that allowed it.
     */
    static String overbookedLine(
            long grantBytes, long leasedBytes, long budgetBytes, OverbookSignals.Reading reading, boolean psi) {
        long budget = Math.max(0, budgetBytes);
        long over = Math.max(0, leasedBytes - budget);
        return "overbooked " + WorkerLeases.format(grantBytes)
                + ": leased " + WorkerLeases.format(leasedBytes)
                + " of the " + WorkerLeases.format(budget) + " budget, " + WorkerLeases.format(over)
                + " over, inside the "
                + WorkerLeases.format(ceiling(budget))
                + (CAUTIOUS ? " cap (1.5 times the budget)" : " cap (twice the budget)")
                + "; allowed because free "
                + WorkerLeases.format(Math.max(0, reading.freeBytes())) + " covers the lease plus "
                + WorkerLeases.format(headroom(budget)) + " headroom"
                + (psi
                        ? " and pressure is low (psi some " + String.format(Locale.ROOT, "%.2f", reading.someAvg10())
                                + " full " + String.format(Locale.ROOT, "%.2f", reading.fullAvg10()) + ")"
                        : "");
    }
}
