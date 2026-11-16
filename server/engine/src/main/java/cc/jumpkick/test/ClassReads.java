// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.jspecify.annotations.Nullable;

/**
 * What each test class of a suite did, read from the suite's recordings ({@link SuiteReads}): the
 * test runner marks each top-level class's window with a {@code jk.TestClass} event, and every
 * other event is joined to the classes whose window, in the same JVM, holds its start time.
 *
 * <ul>
 *   <li>Any thread: a background thread's read while a class runs is that class's. Overlapping
 *       windows (in-process parallelism) each get it.
 *   <li>Outside every window (discovery, a static initialiser before the first class, a shutdown
 *       hook): pooled, which stands for every class the suite ran.
 *   <li>After a class finished: never that class's.
 *   <li>A code file (a jar, a class file outside the class trees) the JVM read: the class in whose
 *       window it was read and every class that ran later in that JVM, since a class loader keeps
 *       what it loaded for whoever asks next.
 * </ul>
 *
 * <p>A class file read under one of the suite's class trees is a class loading: noted by name, as
 * the observed half of the class's cone. Reads of the operating system's own trees and native
 * libraries outside the JDK are platform evidence: kept, never keyed.
 */
public final class ClassReads {

    private ClassReads() {}

    /** The event the test runner marks a class's window with. */
    static final String WINDOW_EVENT = "jk.TestClass";

    /**
     * What a read means, by where it lies.
     *
     * @param classTrees the directories classes load from by file: test classes, main classes,
     *     fixtures, siblings' class trees
     * @param keyed roots the suite's key already covers whole (the JDK, the external classpath, the
     *     resource roots, the module's build output, the run's sandbox): a read there is none of the
     *     class's own
     * @param externalJars the jars on the suite's runtime classpath: a read of one loads a library
     *     the suite's key holds, or a workspace module's classes, which the cone holds class by class
     * @param workspace the workspace root, which paths in a record are spelled relative to
     * @param javaHome the test JDK, whose own native libraries are no evidence of anything
     */
    public record Layout(
            List<Path> classTrees,
            List<Path> keyed,
            Set<Path> externalJars,
            Path workspace,
            @Nullable Path javaHome) {
        public Layout {
            classTrees = List.copyOf(normalized(classTrees));
            keyed = List.copyOf(normalized(keyed));
            externalJars = Set.copyOf(normalized(externalJars));
            workspace = workspace.toAbsolutePath().normalize();
            javaHome = javaHome == null ? null : javaHome.toAbsolutePath().normalize();
        }
    }

    /**
     * One class's share of a suite's run, or the pool's.
     *
     * @param reads data files read, outside every keyed root
     * @param code code files read by path (worker and plugin jars, a jar a test opens itself)
     * @param loaded classes loaded from the class trees, by binary name
     * @param processes programs started
     * @param nativeCode native libraries loaded from outside the JDK
     * @param osPaths reads of the operating system's own trees ({@code /proc}, {@code /etc}, …)
     * @param hosts hosts past this one the JVM's sockets reached
     */
    public record Observation(
            Set<Path> reads,
            Set<Path> code,
            Set<String> loaded,
            Set<String> processes,
            Set<String> nativeCode,
            Set<Path> osPaths,
            Set<String> hosts) {
        static Observation empty() {
            return new Observation(
                    new TreeSet<>(),
                    new TreeSet<>(),
                    new TreeSet<>(),
                    new TreeSet<>(),
                    new TreeSet<>(),
                    new TreeSet<>(),
                    new TreeSet<>());
        }

        /**
         * Whether anything was observed that ties a result to the platform it ran on. The files every
         * JVM reads of the system for itself (its random seed, its own cgroup limits) are kept as
         * evidence but tie nothing.
         */
        public boolean platformBound() {
            if (!nativeCode.isEmpty() || !processes.isEmpty()) return true;
            for (Path p : osPaths) if (!jvmOwn(p)) return true;
            return false;
        }
    }

    /**
     * A suite's run, split by class.
     *
     * @param classes each top-level class that ran in a window, by binary name
     * @param pooled what no window held: every class's
     * @param complete false when a recording could not be read whole: no class of it is attributed
     * @param jvms how many recordings were read
     * @param events how many events were joined
     */
    public record Attributed(
            Map<String, Observation> classes, Observation pooled, boolean complete, int jvms, long events) {
        public static final Attributed NONE = new Attributed(Map.of(), Observation.empty(), false, 0, 0);
    }

    /** One window: a class from its start to its finish, in epoch nanos. */
    record Window(String testClass, long start, long end) {}

    /** One event of a recording, reduced to when it started and what it says. */
    private record Seen(long at, Kind kind, String value) {}

    private enum Kind {
        READ,
        WRITE,
        PROCESS,
        NATIVE,
        HOST
    }

    /** Every recording under {@code dir}, relative paths resolved against {@code cwd}, read and joined. */
    public static Attributed attribute(Path dir, Path cwd, Layout layout) {
        List<Path> recordings = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (child.getFileName().toString().endsWith(".jfr")) recordings.add(child);
                return true;
            });
        } catch (IOException none) {
            return Attributed.NONE;
        }
        Map<String, Observation> classes = new LinkedHashMap<>();
        Observation pooled = Observation.empty();
        boolean complete = true;
        long events = 0;
        for (Path jfr : recordings) {
            List<Window> windows = new ArrayList<>();
            List<Seen> seen = new ArrayList<>();
            try (RecordingFile in = new RecordingFile(jfr)) {
                while (in.hasMoreEvents()) read(in.readEvent(), windows, seen);
            } catch (IOException | RuntimeException unreadable) {
                Log.debug("class reads: recording unreadable at " + jfr, unreadable);
                complete = false;
                continue;
            }
            events += seen.size();
            join(windows, seen, cwd, layout, classes, pooled);
        }
        return new Attributed(classes, pooled, complete, recordings.size(), events);
    }

    private static void read(RecordedEvent e, List<Window> windows, List<Seen> seen) {
        long at = nanos(e.getStartTime());
        switch (e.getEventType().getName()) {
            case WINDOW_EVENT -> {
                String cls = e.getString("testClass");
                if (cls != null && !cls.isBlank()) windows.add(new Window(cls, at, nanos(e.getEndTime())));
            }
            case "jdk.FileRead" -> add(seen, at, Kind.READ, e.getString("path"));
            case "jdk.FileWrite" -> add(seen, at, Kind.WRITE, e.getString("path"));
            case "jdk.ProcessStart" -> add(seen, at, Kind.PROCESS, program(e.getString("command")));
            case "jdk.NativeLibraryLoad" -> {
                if (e.hasField("success") && !e.getBoolean("success")) return;
                add(seen, at, Kind.NATIVE, e.getString("name"));
            }
            case "jdk.SocketRead", "jdk.SocketWrite" -> {
                String address = e.getString("address");
                if (!local(address)) add(seen, at, Kind.HOST, SuiteReads.hostName(e.getString("host"), address));
            }
            default -> {}
        }
    }

    private static void add(List<Seen> seen, long at, Kind kind, @Nullable String value) {
        if (value != null && !value.isBlank()) seen.add(new Seen(at, kind, value));
    }

    /**
     * Each event of one JVM given to the classes whose window holds it, or to the pool. A file the
     * JVM wrote is its own output: a read of it is no input.
     */
    static void join(
            List<Window> windows,
            List<Seen> seen,
            Path cwd,
            Layout layout,
            Map<String, Observation> classes,
            Observation pooled) {
        windows.sort(Comparator.comparingLong(Window::start));
        long[] starts = new long[windows.size()];
        long[] reach = new long[windows.size()]; // the latest end of any window up to i
        for (int i = 0; i < windows.size(); i++) {
            starts[i] = windows.get(i).start();
            reach[i] = Math.max(windows.get(i).end(), i > 0 ? reach[i - 1] : Long.MIN_VALUE);
            of(classes, windows.get(i).testClass());
        }
        Set<Path> written = new TreeSet<>();
        for (Seen s : seen) {
            if (s.kind() == Kind.WRITE) resolved(cwd, s.value()).ifPresent(written::add);
        }
        List<CodeRead> codeReads = new ArrayList<>();
        Map<Path, Boolean> exists = new HashMap<>();
        for (Seen s : seen) {
            if (s.kind() == Kind.WRITE) continue;
            List<Observation> into = new ArrayList<>();
            for (int i = lastStartingBy(starts, s.at()); i >= 0 && reach[i] >= s.at(); i--) {
                Window w = windows.get(i);
                if (w.end() >= s.at()) into.add(of(classes, w.testClass()));
            }
            if (into.isEmpty()) into.add(pooled);
            switch (s.kind()) {
                case PROCESS -> {
                    // The test JDK's own launcher (an agent attaching from a second JVM, as Mockito's
                    // does) is the JDK the key already holds, not a program the class depends on.
                    if (!underJdk(s.value(), layout))
                        into.forEach(o -> o.processes().add(s.value()));
                }
                case HOST -> into.forEach(o -> o.hosts().add(s.value()));
                case NATIVE -> {
                    if (!underJdk(s.value(), layout))
                        into.forEach(o -> o.nativeCode().add(s.value()));
                }
                case READ -> {
                    Path p = resolved(cwd, s.value()).orElse(null);
                    if (p == null || written.contains(p)) continue;
                    switch (classify(p, layout)) {
                        case LOADED -> {
                            String cls = className(p, layout);
                            if (cls != null) into.forEach(o -> o.loaded().add(cls));
                        }
                        case OS -> into.forEach(o -> o.osPaths().add(p));
                        case CODE -> codeReads.add(new CodeRead(s.at(), p));
                        case DATA -> {
                            if (exists.computeIfAbsent(p, Files::exists))
                                into.forEach(o -> o.reads().add(p));
                        }
                        case KEYED -> {}
                    }
                }
                case WRITE -> {}
            }
        }
        // A code file read by the time a class finished is one its JVM's loaders may serve it from.
        for (CodeRead c : codeReads) {
            boolean any = false;
            for (Window w : windows) {
                if (c.at() <= w.end()) {
                    of(classes, w.testClass()).code().add(c.path());
                    any = true;
                }
            }
            if (!any) pooled.code().add(c.path());
        }
    }

    private record CodeRead(long at, Path path) {}

    private static Observation of(Map<String, Observation> classes, String testClass) {
        return classes.computeIfAbsent(testClass, c -> Observation.empty());
    }

    /** The index of the last window starting at or before {@code at}, or -1. */
    private static int lastStartingBy(long[] starts, long at) {
        int lo = 0;
        int hi = starts.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= at) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** What a read path is to the class that read it. */
    enum Where {
        LOADED,
        OS,
        CODE,
        KEYED,
        DATA
    }

    static Where classify(Path p, Layout layout) {
        String name = String.valueOf(p.getFileName());
        if (name.endsWith(".class") && under(p, layout.classTrees())) return Where.LOADED;
        if (osPath(p)) return Where.OS;
        if (layout.javaHome() != null && p.startsWith(layout.javaHome())) return Where.KEYED;
        if (codeFile(name)) return layout.externalJars().contains(p) ? Where.KEYED : Where.CODE;
        if (under(p, layout.keyed())) return Where.KEYED;
        return Where.DATA;
    }

    private static boolean codeFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") || n.endsWith(".zip") || n.endsWith(".jmod") || n.endsWith(".class");
    }

    /**
     * Whether {@code p} lies in the operating system's own trees: the live pseudo trees and the
     * system configuration on Linux, the system and library trees on macOS, the Windows directory.
     */
    static boolean osPath(Path p) {
        String s = p.toString().replace('\\', '/');
        for (String prefix : OS_PREFIXES) {
            if (s.startsWith(prefix)) return true;
        }
        String win = ProcessEnv.get("SystemRoot");
        return win != null
                && !win.isBlank()
                && s.toLowerCase(Locale.ROOT).startsWith(win.replace('\\', '/').toLowerCase(Locale.ROOT) + "/");
    }

    /** Whether {@code p} is a system file every JVM reads for itself, whatever its code does. */
    static boolean jvmOwn(Path p) {
        String s = p.toString().replace('\\', '/');
        for (String prefix : JVM_OWN) {
            if (s.equals(prefix) || (prefix.endsWith("/") && s.startsWith(prefix))) return true;
        }
        return false;
    }

    private static final List<String> JVM_OWN =
            List.of("/dev/random", "/dev/urandom", "/dev/null", "/proc/self/", "/proc/cgroups", "/sys/fs/cgroup/");

    private static final List<String> OS_PREFIXES =
            List.of("/proc/", "/sys/", "/dev/", "/etc/", "/System/", "/Library/", "/private/etc/");

    /** The binary name of the class file {@code p} under one of the class trees. */
    private static @Nullable String className(Path p, Layout layout) {
        for (Path tree : layout.classTrees()) {
            if (!p.startsWith(tree)) continue;
            String rel = tree.relativize(p).toString().replace('\\', '/');
            return rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
        }
        return null;
    }

    private static boolean underJdk(String library, Layout layout) {
        if (layout.javaHome() == null) return false;
        try {
            Path lib = Path.of(library).toAbsolutePath().normalize();
            return lib.startsWith(layout.javaHome()) || real(lib).startsWith(real(layout.javaHome()));
        } catch (InvalidPathException bare) {
            return false;
        }
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException gone) {
            return p;
        }
    }

    private static boolean under(Path p, Collection<Path> roots) {
        for (Path r : roots) {
            if (p.startsWith(r)) return true;
        }
        return false;
    }

    private static Optional<Path> resolved(Path cwd, String raw) {
        try {
            return Optional.of(cwd.resolve(raw).toAbsolutePath().normalize());
        } catch (InvalidPathException odd) {
            return Optional.empty();
        }
    }

    private static boolean local(@Nullable String address) {
        if (address == null || address.isBlank() || address.startsWith("[")) return true;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException | RuntimeException unknown) {
            return false;
        }
    }

    /** The program of a recorded command line, as evidence a process started: its first word. */
    private static @Nullable String program(@Nullable String command) {
        if (command == null) return null;
        String c = command.strip();
        int space = c.indexOf(' ');
        return space < 0 ? c : c.substring(0, space);
    }

    private static long nanos(Instant t) {
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }

    private static List<Path> normalized(Collection<Path> paths) {
        List<Path> out = new ArrayList<>();
        for (Path p : paths) out.add(p.toAbsolutePath().normalize());
        return out;
    }
}
