// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Os;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Which of a suite's recorded reads count as discovered inputs: the events its JVMs record, which
 * files read before the run wrote them are the project's, which lie outside everything the key
 * already covers, which programs it started and whether a socket left this host. A change here
 * takes effect only when a suite runs again, so a run-tests stamp keys these rules by this
 * package's classes; reading the recordings and keeping what they found is the engine's.
 */
public final class DiscoveryRules {

    private DiscoveryRules() {}

    private static final String SETTINGS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration version="2.0" label="jk suite reads">
              <event name="jdk.FileRead">
                <setting name="enabled">true</setting>
                <setting name="stackTrace">false</setting>
                <setting name="threshold">0 ms</setting>
              </event>
              <event name="jdk.FileWrite">
                <setting name="enabled">true</setting>
                <setting name="stackTrace">false</setting>
                <setting name="threshold">0 ms</setting>
              </event>
              <event name="jdk.ProcessStart">
                <setting name="enabled">true</setting>
                <setting name="stackTrace">false</setting>
              </event>
              <event name="jdk.SocketRead">
                <setting name="enabled">true</setting>
                <setting name="stackTrace">false</setting>
                <setting name="threshold">0 ms</setting>
              </event>
              <event name="jdk.SocketWrite">
                <setting name="enabled">true</setting>
                <setting name="stackTrace">false</setting>
                <setting name="threshold">0 ms</setting>
              </event>
            </configuration>
            """;

    /** The events only the engine's class telemetry reads. */
    private static final String CLASS_EVENTS = """
              <event name="jdk.NativeLibraryLoad">
                <setting name="enabled">true</setting>
                <setting name="stackTrace">false</setting>
                <setting name="threshold">0 ms</setting>
              </event>
              <event name="jk.TestClass">
                <setting name="enabled">true</setting>
                <setting name="stackTrace">false</setting>
                <setting name="threshold">0 ms</setting>
              </event>
              <event name="jk.ClassUse">
                <setting name="enabled">true</setting>
                <setting name="stackTrace">false</setting>
              </event>
            """;

    /**
     * The JFR settings every suite JVM records under; {@code classEvents} adds each test class's
     * window ({@code jk.TestClass}), the classes whose code ran in it when the probe marks them
     * ({@code jk.ClassUse}) and the native libraries loaded.
     */
    public static String recordingSettings(boolean classEvents) {
        return classEvents ? SETTINGS.replace("</configuration>", CLASS_EVENTS + "</configuration>") : SETTINGS;
    }

    /** The events a suite's recordings are read for, and the fields of each. */
    public static final Map<String, List<String>> EVENTS = Map.of(
            "jdk.FileRead", List.of("path"),
            "jdk.FileWrite", List.of("path"),
            "jdk.ProcessStart", List.of("command"),
            "jdk.SocketRead", List.of("host", "address"),
            "jdk.SocketWrite", List.of("host", "address"));

    /**
     * What one run's recordings say, event by event ({@link #EVENTS}): the files the run read or
     * wrote that {@code candidate} accepts, the programs it started and the hosts past this one it
     * reached. Relative paths resolve against {@code cwd}; a file it both read and wrote is judged by
     * {@link FirstTouches#inputs} against {@code workspace}.
     */
    public static final class Recording {
        private final Path cwd;
        private final Predicate<Path> candidate;
        private final FirstTouches touched;
        private final Set<String> commands = new TreeSet<>();
        private final Set<String> hosts = new TreeSet<>();

        public Recording(Path cwd, Path workspace, Predicate<Path> candidate) {
            this.cwd = cwd;
            this.candidate = candidate;
            this.touched = new FirstTouches(workspace);
        }

        /** One event of {@code name} at {@code atNanos}, its fields in {@link #EVENTS}'s order. */
        public void event(String name, long atNanos, @Nullable String[] values) {
            switch (name) {
                case "jdk.FileRead" -> {
                    Path f = resolve(cwd, values[0]);
                    if (f != null && (touched.holds(f) || candidate.test(f))) touched.read(f, atNanos);
                }
                case "jdk.FileWrite" -> {
                    Path f = resolve(cwd, values[0]);
                    if (f != null && (touched.holds(f) || candidate.test(f))) touched.wrote(f, atNanos);
                }
                case "jdk.ProcessStart" -> {
                    // The program, not its arguments: a pid or a temp path in them would
                    // make every run's command new.
                    String command = values[0];
                    if (command != null && !command.isBlank()) commands.add(executable(command));
                }
                case "jdk.SocketRead", "jdk.SocketWrite" -> {
                    if (!local(values[1])) hosts.add(hostName(values[0], values[1]));
                }
                default -> {}
            }
        }

        /** The files that are the run's inputs ({@link FirstTouches#inputs}). */
        public Set<Path> files() {
            return touched.inputs();
        }

        public Set<String> commands() {
            return commands;
        }

        public Set<String> hosts() {
            return hosts;
        }

        /** Let go of what was held, for a run whose recordings vouch for nothing. */
        public void clear() {
            touched.clear();
        }
    }

    /**
     * Each file's first read and first write in one run, in epoch nanos, from every recording of it:
     * each JVM stamps its events from the host's clock, so the events of several JVMs order against
     * one another however the recordings are read.
     */
    public static final class FirstTouches {
        private final Path workspace;
        private final Map<Path, Long> read = new TreeMap<>();
        private final Map<Path, Long> written = new TreeMap<>();

        public FirstTouches(Path workspace) {
            this.workspace = workspace.toAbsolutePath().normalize();
        }

        public void read(Path f, long atNanos) {
            read.merge(f, atNanos, Math::min);
        }

        public void wrote(Path f, long atNanos) {
            written.merge(f, atNanos, Math::min);
        }

        /** Whether {@code f} was read or written already, so it was a candidate. */
        boolean holds(Path f) {
            return read.containsKey(f) || written.containsKey(f);
        }

        void clear() {
            read.clear();
            written.clear();
        }

        /**
         * The files the run read and never wrote, and those in the workspace it read before it first
         * wrote them: a snapshot, a golden file or a fixture a test updates is the project's input, and
         * what the tests saw of it is what it held before the run. A read stamped with its write counts
         * as first: it may have seen the old bytes. A file the run wrote first is its own output. A file
         * outside the workspace the run both read and wrote is some tool's own state (a daemon registry,
         * a resolver's status file, preferences), which the project cannot fix and which, keyed, would
         * keep the suite from ever replaying. So is one outside it the run replaced by renaming a temp
         * file over it ({@link #replacedFirst}), as the JDK rewrites its font cache.
         */
        public Set<Path> inputs() {
            Map<Path, Long> replaced = replacedFirst();
            Set<Path> out = new TreeSet<>();
            read.forEach((f, at) -> {
                Long wrote = written.get(f);
                if (f.startsWith(workspace)) {
                    if (wrote == null || at <= wrote) out.add(f);
                } else if (wrote == null && !replacedDuringRun(f, replaced.get(f.getParent()))) {
                    out.add(f);
                }
            });
            return out;
        }

        /**
         * For each directory outside the workspace that holds a file the run read, the first write
         * of a file the run wrote there and that is gone now: a temp file renamed over its target
         * leaves no write of the target in the recording, only of the temp file.
         */
        private Map<Path, Long> replacedFirst() {
            Set<Path> dirs = new TreeSet<>();
            for (Path f : read.keySet()) {
                Path dir = f.getParent();
                if (dir != null && !f.startsWith(workspace)) dirs.add(dir);
            }
            Map<Path, Long> first = new HashMap<>();
            written.forEach((w, at) -> {
                Path dir = w.getParent();
                if (dir != null && dirs.contains(dir) && !read.containsKey(w) && !Files.exists(w)) {
                    first.merge(dir, at, Math::min);
                }
            });
            return first;
        }

        /**
         * Whether {@code f} was last modified no earlier than {@code tempWrittenNanos}, the first write
         * of a temp file beside it the run left gone: the rename gave {@code f} that temp file's time.
         */
        private static boolean replacedDuringRun(Path f, @Nullable Long tempWrittenNanos) {
            if (tempWrittenNanos == null) return false;
            try {
                FileTime modified = Files.getLastModifiedTime(f);
                long latest = modified.toMillis() + (wholeSecond(modified) ? 999 : 0);
                return latest >= TimeUnit.NANOSECONDS.toMillis(tempWrittenNanos) - BIRTH_SLACK_MILLIS;
            } catch (IOException gone) {
                return false;
            }
        }
    }

    /** The name a remote endpoint is shown by: the host name the socket was opened with, else its address. */
    public static String hostName(@Nullable String host, @Nullable String address) {
        String name = host != null && !host.isBlank() ? host : String.valueOf(address);
        return name.strip().replace('\n', ' ');
    }

    /** The file a recorded path names, resolved against {@code cwd}; null for none. */
    private static @Nullable Path resolve(Path cwd, @Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return cwd.resolve(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            // A pseudo path (a pipe, a descriptor) is not a file the suite depends on.
            return null;
        }
    }

    /** Whether a socket stayed on this host: loopback, or a Unix-domain socket (its address is a path). */
    private static boolean local(@Nullable String address) {
        if (address == null || address.isBlank() || address.startsWith("[")) return true;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException | RuntimeException unknown) {
            return false;
        }
    }

    /**
     * The files that may be discovered inputs of a suite keyed over {@code keyed}: what {@link
     * #undeclared} would not drop by where the file lies (a live system tree, a probe jar, a path
     * under a keyed root under either spelling). Held to it while the recordings are read, a run
     * keeps only its candidates, so the files a suite writes and reads in its own sandbox cost
     * nothing; {@link #undeclared} applies the rest of its rules to what is kept, and the result is
     * the one the whole set would have given. A real-path lookup, a system call, is made only for a
     * path no root holds as spelled, and remembered for the last {@value #REAL_MEMO} such paths.
     */
    public static Predicate<Path> candidates(Collection<Path> keyed) {
        List<Path> roots = roots(keyed);
        Map<Path, Boolean> outside = new LinkedHashMap<>(REAL_MEMO, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Path, Boolean> eldest) {
                return size() > REAL_MEMO;
            }
        };
        return f -> {
            if (live(f) || under(f, roots) || probeJar(f)) return false;
            return outside.computeIfAbsent(
                    f, p -> real(p).filter(r -> under(r, roots)).isEmpty());
        };
    }

    /** How many real-path answers {@link #candidates} remembers. */
    static final int REAL_MEMO = 4096;

    /** As {@link #undeclared(Set, Collection, Path, long)} with no workspace to judge new directories by. */
    public static Set<Path> undeclared(Set<Path> files, Collection<Path> keyed) {
        return undeclared(files, keyed, null, 0);
    }

    /**
     * The files of {@code files} no key already covers: outside every root in {@code keyed} (the
     * classes, classpath, resources, sources, JDK, outputs and sandbox of the run), outside the
     * operating system's live pseudo trees, whose content changes with every read, and still there.
     * Also left out is a file under a directory outside {@code workspace} that was created at or
     * after {@code startedMillis}: the run made it (a temp root a test framework chose, a store a
     * nested build filled), so what is in it is the run's own output, whichever process wrote it.
     * Inside the workspace a new directory is no such proof.
     */
    public static Set<Path> undeclared(
            Set<Path> files, Collection<Path> keyed, @Nullable Path workspace, long startedMillis) {
        List<Path> roots = roots(keyed);
        Map<Path, Boolean> made = new HashMap<>();
        Set<Path> out = new TreeSet<>();
        for (Path f : files) {
            if (live(f) || under(f, roots) || probeJar(f)) continue;
            // A file is the same input under either spelling (macOS's /var is /private/var).
            if (real(f).filter(r -> under(r, roots)).isPresent()) continue;
            if (workspace != null && madeDuringRun(f, workspace, startedMillis, made)) continue;
            // Gone once the run ended: made and removed by it, or consumed by it; either way
            // nothing to key, and a run that needs it back finds it missing on its own.
            if (!Files.exists(f)) continue;
            out.add(f);
        }
        return out;
    }

    /** The commands of {@code commands} whose program lies outside {@code keyed}: one the run made itself is its own. */
    public static Set<String> undeclaredCommands(
            Set<String> commands, Collection<Path> keyed, @Nullable Path workspace, long startedMillis) {
        Map<Path, Boolean> made = new HashMap<>();
        List<Path> roots = roots(keyed);
        Set<String> out = new TreeSet<>();
        for (String c : commands) {
            try {
                Path program = Path.of(c);
                if (program.isAbsolute() && under(program.normalize(), roots)) continue;
                if (program.isAbsolute()
                        && workspace != null
                        && madeDuringRun(program.normalize(), workspace, startedMillis, made)) {
                    continue;
                }
            } catch (InvalidPathException notAPath) {
                // A bare name: keyed by what the PATH resolves it to.
            }
            out.add(c);
        }
        return out;
    }

    /**
     * Whether {@code file} is a probe jar the engine cut ({@code <cache>/env-reads/env-probe-*.jar}).
     * jk hands it to every probed test JVM, and the JVM reads it whenever it loads a probe class,
     * which may be late in the run (a {@code -Duser.timezone} test JVM loads one then): it is jk's
     * own tool, never an input of the suite, and since every launch touches it, keying it would
     * call it changed under any suite that ran beside another.
     */
    public static boolean probeJar(Path file) {
        Path name = file.getFileName();
        Path parent = file.getParent();
        return name != null
                && parent != null
                && parent.getFileName() != null
                && parent.getFileName().toString().equals(CacheTree.ENV_READS.entry())
                && name.toString().startsWith("env-probe-")
                && name.toString().endsWith(".jar");
    }

    /**
     * The latest moment, in epoch millis, the entry {@code attrs} describes can have come into being.
     * A birth time on a whole second may have been cut to it: the JDK reports macOS's to the second
     * on file systems that stamp nanoseconds, and a coarse file system stamps whole seconds. Such an
     * entry may have been born up to a second later, though never after it was last modified. A
     * modification older than the birth (a copy that kept its source's time) bounds nothing.
     */
    static long latestBirthMillis(BasicFileAttributes attrs) {
        FileTime born = attrs.creationTime();
        if (!wholeSecond(born)) return born.toMillis();
        FileTime modified = attrs.lastModifiedTime();
        long modifiedLatest = modified.toMillis() + (wholeSecond(modified) ? 999 : 0);
        long latest = born.toMillis() + 999;
        return modifiedLatest >= born.toMillis() ? Math.min(latest, modifiedLatest) : latest;
    }

    private static boolean wholeSecond(FileTime t) {
        return t.to(TimeUnit.NANOSECONDS) % 1_000_000_000L == 0;
    }

    /**
     * How far before the run's start an entry's latest birth may fall and still be the run's own: a
     * few clock ticks, since the kernel stamps files from a clock that lags ours by up to a tick.
     */
    static final long BIRTH_SLACK_MILLIS = 20;

    /** Whether the entry {@code attrs} describes may have come into being once a run started at {@code startedMillis}. */
    public static boolean bornDuringRun(BasicFileAttributes attrs, long startedMillis) {
        return latestBirthMillis(attrs) >= startedMillis - BIRTH_SLACK_MILLIS;
    }

    /** Whether an ancestor of {@code f} outside {@code workspace} came into being once the run had started. */
    static boolean madeDuringRun(Path f, Path workspace, long startedMillis, Map<Path, Boolean> memo) {
        Path ws = workspace.toAbsolutePath().normalize();
        for (Path dir = f.getParent(); dir != null && dir.getParent() != null; dir = dir.getParent()) {
            if (dir.startsWith(ws) || ws.startsWith(dir)) continue;
            Boolean known = memo.get(dir);
            if (known == null) {
                try {
                    known = bornDuringRun(Files.readAttributes(dir, BasicFileAttributes.class), startedMillis);
                } catch (IOException gone) {
                    // Gone already: only the run's own scratch is reaped before it ends.
                    known = true;
                }
                memo.put(dir, known);
            }
            if (known) return true;
        }
        return false;
    }

    /** The files of {@code files} that lie under {@code dir}, by either spelling of either path. */
    public static Set<Path> under(Set<Path> files, Path dir) {
        List<Path> roots = roots(List.of(dir));
        Set<Path> out = new TreeSet<>();
        for (Path f : files) {
            if (under(f, roots) || real(f).filter(r -> under(r, roots)).isPresent()) out.add(f);
        }
        return out;
    }

    /** Each of {@code keyed} absolute and normalized, and by its real path too when it has one. */
    private static List<Path> roots(Collection<Path> keyed) {
        List<Path> roots = new ArrayList<>();
        for (Path k : keyed) {
            roots.add(k.toAbsolutePath().normalize());
            real(k).ifPresent(roots::add);
        }
        return roots;
    }

    private static Optional<Path> real(Path p) {
        try {
            return Optional.of(p.toRealPath());
        } catch (IOException | RuntimeException absent) {
            return Optional.empty();
        }
    }

    private static boolean under(Path f, List<Path> roots) {
        for (Path r : roots) {
            if (f.startsWith(r)) return true;
        }
        return false;
    }

    private static boolean live(Path f) {
        String s = f.toString().replace('\\', '/');
        return s.startsWith("/proc/") || s.startsWith("/sys/") || s.startsWith("/dev/");
    }

    /**
     * The program a recorded command line started. JFR records the command as its words joined by
     * spaces, so a program whose path holds a space ({@code C:/Program Files/...}) cannot be told
     * from its arguments by the first word: the program is the shortest run of words that names an
     * absolute file that is there (on Windows also with {@code .exe} added), else the first word.
     * What this answers is a program, kept and read back whole ({@link #hostSpelling}); nothing
     * downstream splits it again.
     */
    public static String executable(String command) {
        String c = command.strip();
        int space = c.indexOf(' ');
        if (space < 0) return hostSpelling(c);
        for (int at = space; at >= 0; at = c.indexOf(' ', at + 1)) {
            String words = c.substring(0, at);
            if (namesProgram(words)) return hostSpelling(words);
        }
        return hostSpelling(namesProgram(c) ? c : c.substring(0, space));
    }

    private static boolean namesProgram(String words) {
        try {
            Path p = Path.of(words);
            if (!p.isAbsolute()) return false;
            return Files.isRegularFile(p) || (Os.isWindows() && Files.isRegularFile(Path.of(words + ".exe")));
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }

    /**
     * {@code program} as this host spells a path: an absolute one with its separators ({@code
     * C:\Program Files\...} on Windows, where a kept line has {@code /}), anything else as written. A
     * program read back is compared with one a run observed, so both take this one spelling.
     */
    public static String hostSpelling(String program) {
        try {
            Path p = Path.of(program);
            return p.isAbsolute() ? p.toString() : program;
        } catch (InvalidPathException notAPath) {
            return program;
        }
    }
}
