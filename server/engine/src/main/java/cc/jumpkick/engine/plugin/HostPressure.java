// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The host's memory pressure, and the warm workers that give their memory back when it rises. The
 * lease ledger only sees jk's own forks; this sees the IDE and the browser too. An idle warm worker
 * exits when the host is short of memory, a busy one is left to finish, and the next run starts a
 * fresh one.
 *
 * <p>Sources: on macOS the kernel's {@code kern.memorystatus_vm_pressure_level} (the level behind
 * the system's memory-pressure notifications); on Linux the pressure stall information of the
 * engine's cgroup ({@code /sys/fs/cgroup/memory.pressure}), else of the host ({@code
 * /proc/pressure/memory}). Anywhere else the level reads {@link Level#NORMAL} and nothing is shed.
 */
public final class HostPressure {

    private HostPressure() {}

    public enum Level {
        NORMAL,
        WARN,
        CRITICAL
    }

    /** An owner of idle warm workers: end those idle at least {@code minIdle}, and say how many. */
    @FunctionalInterface
    public interface Shedder {
        int shed(Duration minIdle);
    }

    /** How often the level is read while any owner is registered. */
    static final Duration POLL = Duration.ofSeconds(5);

    /** Under {@link Level#WARN} a worker idle this long exits: one between two modules of a build stays. */
    static final Duration WARN_IDLE = Duration.ofSeconds(60);

    /** Linux: the share of the last 10 s some task stalled on memory that reads as {@link Level#WARN}. */
    static final double PSI_SOME_WARN = 10.0;

    /** Linux: the share of the last 10 s every task stalled on memory that reads as {@link Level#CRITICAL}. */
    static final double PSI_FULL_CRITICAL = 10.0;

    private static final Path CGROUP_PRESSURE = Path.of("/sys/fs/cgroup/memory.pressure");
    private static final Path PROC_PRESSURE = Path.of("/proc/pressure/memory");

    /** Owners by name. */
    private static final Map<String, Shedder> SHEDDERS = new ConcurrentHashMap<>();

    private static volatile @Nullable Level forTests;
    private static volatile boolean watching;

    /** Shed {@code name}'s idle workers when the host is short of memory. Idempotent per name. */
    public static void register(String name, Shedder shedder) {
        SHEDDERS.putIfAbsent(name, shedder);
        watch();
    }

    /** Read the level once and shed accordingly; returns how many workers exited. */
    static int check() {
        Level level = level();
        if (level == Level.NORMAL) return 0;
        Duration minIdle = level == Level.CRITICAL ? Duration.ZERO : WARN_IDLE;
        int shed = 0;
        for (Shedder s : SHEDDERS.values()) {
            try {
                shed += s.shed(minIdle);
            } catch (RuntimeException e) {
                Log.debug("memory pressure: shedding failed", e);
            }
        }
        if (shed > 0) {
            Log.info("host memory pressure " + level.name().toLowerCase(Locale.ROOT) + ": stopped " + shed
                    + " idle warm worker" + (shed == 1 ? "" : "s"));
        }
        return shed;
    }

    /** The host's memory pressure now. */
    public static Level level() {
        Level pinned = forTests;
        if (pinned != null) return pinned;
        try {
            if (Os.isDarwin()) return macLevel();
            if (Os.isLinux()) {
                Path file = Files.isReadable(CGROUP_PRESSURE) ? CGROUP_PRESSURE : PROC_PRESSURE;
                if (Files.isReadable(file)) return psiLevel(Files.readString(file));
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("memory pressure: unreadable", e);
        }
        return Level.NORMAL;
    }

    /**
     * The level a pressure stall file reads as: {@code some avg10} at {@link #PSI_SOME_WARN} or
     * more is {@link Level#WARN}, {@code full avg10} at {@link #PSI_FULL_CRITICAL} or more is
     * {@link Level#CRITICAL}.
     */
    static Level psiLevel(String text) {
        double some = 0;
        double full = 0;
        for (String line : text.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 2 || !fields[1].startsWith("avg10=")) continue;
            double avg10;
            try {
                avg10 = Double.parseDouble(fields[1].substring("avg10=".length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if ("some".equals(fields[0])) some = avg10;
            else if ("full".equals(fields[0])) full = avg10;
        }
        if (full >= PSI_FULL_CRITICAL) return Level.CRITICAL;
        if (some >= PSI_SOME_WARN) return Level.WARN;
        return Level.NORMAL;
    }

    /** The level {@code kern.memorystatus_vm_pressure_level} names: 1 normal, 2 warn, 4 critical. */
    static Level macLevel(int kernel) {
        return switch (kernel) {
            case 2 -> Level.WARN;
            case 4 -> Level.CRITICAL;
            default -> Level.NORMAL;
        };
    }

    private static final FunctionDescriptor SYSCTLBYNAME = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG);

    private static @Nullable MethodHandle sysctlbyname;

    private static Level macLevel() {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle call = sysctlbyname;
            if (call == null) {
                Linker linker = Linker.nativeLinker();
                MemorySegment fn = linker.defaultLookup()
                        .find("sysctlbyname")
                        .orElseThrow(() -> new IllegalStateException("no sysctlbyname"));
                call = linker.downcallHandle(fn, SYSCTLBYNAME);
                sysctlbyname = call;
            }
            MemorySegment value = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
            size.set(ValueLayout.JAVA_LONG, 0, ValueLayout.JAVA_INT.byteSize());
            int rc = (int) call.invokeExact(
                    arena.allocateFrom("kern.memorystatus_vm_pressure_level"), value, size, MemorySegment.NULL, 0L);
            return rc == 0 ? macLevel(value.get(ValueLayout.JAVA_INT, 0)) : Level.NORMAL;
        } catch (Throwable t) {
            Log.debug("memory pressure: sysctlbyname failed", t);
            return Level.NORMAL;
        }
    }

    private static void watch() {
        if (watching) return;
        synchronized (HostPressure.class) {
            if (watching) return;
            watching = true;
            // Memory-pressure watcher for warm workers; reads no session.
            Thread.ofVirtual().name("jk-host-pressure").start(() -> {
                while (true) {
                    try {
                        Thread.sleep(POLL);
                    } catch (InterruptedException e) {
                        return;
                    }
                    check();
                }
            });
        }
    }

    /** Test seam: pin the level; {@code null} reads the host again. */
    static void forTests(@Nullable Level level) {
        forTests = level;
    }
}
