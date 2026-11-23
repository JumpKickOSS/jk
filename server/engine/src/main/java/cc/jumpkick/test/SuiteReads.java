// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.PortablePath;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
 * What a test suite read that its stamp does not already hash, found by watching it run rather than
 * by trusting what it declares.
 *
 * <p>Every JVM that runs a suite records, through JFR, each file it reads ({@code jdk.FileRead}),
 * each program it starts ({@code jdk.ProcessStart}) and its socket traffic. After the run, a file
 * outside everything the stamp already keys — the classes, the classpath, the resources, the JDK, the
 * module's own outputs and the run's sandbox — is a <em>discovered input</em>: it is kept in jk's
 * cache for the project, where a clean leaves it, and keys the suite from then on, by content, together with the listing of
 * the directory it sits in (so a file added beside it is seen, though JFR records no directory
 * listing), and a process by the identity of its executable. A run that found new inputs stores its
 * green marker under the key that folds them in, when they held still while it ran; from then on
 * it is replayed only while they hold. A suite that talks to anything but this host is never
 * replayed.
 *
 * <p>JFR on JDK 22 and later records every read; an older suite JDK records stream and random-access
 * reads only, so a channel read there goes unseen.
 */
public final class SuiteReads {

    private SuiteReads() {}

    /** One run's recordings, under the module's target dir; emptied before each run. */
    public static Path recordingDir(Path moduleTargetDir) {
        return moduleTargetDir.resolve(".jk").resolve("suite-reads");
    }

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

    /** The events only the class telemetry reads ({@link TestTelemetry}). */
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
     * Empty {@code dir} and write the recording settings into it; returns the flag every suite JVM
     * takes. Besides what keys the suite, the recording carries each test class's window ({@code
     * jk.TestClass}, from the test runner), the classes whose code ran in it when the probe marks them
     * ({@code jk.ClassUse}) and the native libraries loaded, which {@link ClassReads}
     * attributes to the classes.
     */
    public static String prepare(Path dir) throws IOException {
        PathUtil.deleteRecursively(dir);
        Files.createDirectories(dir);
        Path settings = dir.resolve("reads.jfc");
        String settingsText =
                TestTelemetry.on() ? SETTINGS.replace("</configuration>", CLASS_EVENTS + "</configuration>") : SETTINGS;
        Files.writeString(settings, settingsText, StandardCharsets.UTF_8);
        return "-XX:StartFlightRecording=settings=" + jfrPath(settings) + ",filename=" + jfrPath(dir)
                + ",dumponexit=true";
    }

    /**
     * JFR splits its options on commas only, so only a comma needs quoting. A space must not be quoted: on
     * Windows the launcher wraps the whole argument in quotes, and an inner pair would split it.
     */
    static String jfrPath(Path path) {
        String s = path.toString();
        return s.indexOf(',') >= 0 ? "\"" + s + "\"" : s;
    }

    /**
     * What one run observed: files read before the run itself wrote them, commands started, the
     * hosts past this one it read from or wrote to, and whether every recording read back ({@code
     * complete} false vouches for nothing this run; {@code unread} then says why).
     */
    public record Observed(
            Set<Path> files,
            Set<String> commands,
            Set<String> hosts,
            boolean complete,
            @Nullable String unread) {
        public Observed(Set<Path> files, Set<String> commands, Set<String> hosts, boolean complete) {
            this(files, commands, hosts, complete, complete ? null : "a recording could not be read");
        }

        /** Whether the run reached past this host. */
        public boolean network() {
            return !hosts.isEmpty();
        }
    }

    /** The events a suite's recordings are read for, and the fields of each. */
    private static final Map<String, List<String>> WANTED = Map.of(
            "jdk.FileRead", List.of("path"),
            "jdk.FileWrite", List.of("path"),
            "jdk.ProcessStart", List.of("command"),
            "jdk.SocketRead", List.of("host", "address"),
            "jdk.SocketWrite", List.of("host", "address"));

    /**
     * The heap counted for the inputs one run is found to read: files outside every root the suite's
     * key already covers ({@link #candidates}), which the key must hash one by one anyway. jk-engine's
     * suite, the largest of jk's own, names tens of thousands of files, almost all in its own sandbox
     * and the module's build output; those are never held (8,264 paths held 4 MB). This is the budget
     * memory admission counts, not a cap: no number of inputs keeps a suite from replaying.
     */
    static final long INPUTS_BYTES = 16L << 20;

    public static final long READ_PEAK_BYTES = RecordingReader.PEAK_BYTES + INPUTS_BYTES;

    /**
     * As {@link #collect(Path, Path, Path, Predicate)} with {@code cwd} as the workspace, keeping every
     * file the recordings name.
     */
    public static Observed collect(Path dir, Path cwd) {
        return collect(dir, cwd, cwd, f -> true);
    }

    /**
     * Every recording under {@code dir}, relative paths resolved against {@code cwd}, keeping only the
     * files {@code candidate} accepts, read or written: a file the suite's key already covers (its
     * sandbox, the module's build output, the classpath, the JDK) is no discovered input, so it is
     * never held, however many a run names. A file the run both read and wrote is judged by
     * {@link FirstTouches#inputs} against {@code workspace}. The recordings are streamed by {@link
     * RecordingReader}, one at a time across the engine; only a recording no reader can read is incomplete.
     */
    public static Observed collect(Path dir, Path cwd, Path workspace, Predicate<Path> candidate) {
        Set<Path> files = new TreeSet<>();
        FirstTouches touched = new FirstTouches(workspace);
        Set<String> commands = new TreeSet<>();
        Set<String> hosts = new TreeSet<>();
        List<Path> recordings = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (child.getFileName().toString().endsWith(".jfr")) recordings.add(child);
                return true;
            });
        } catch (IOException none) {
            return new Observed(files, commands, hosts, false, "the suite wrote no recording");
        }
        if (recordings.isEmpty()) return new Observed(files, commands, hosts, true, null);
        RecordingReader.Sink sink = (name, at, end, v) -> {
            switch (name) {
                case "jdk.FileRead" -> {
                    Path f = resolve(cwd, v[0]);
                    if (f != null && (touched.holds(f) || candidate.test(f))) touched.read(f, at);
                }
                case "jdk.FileWrite" -> {
                    Path f = resolve(cwd, v[0]);
                    if (f != null && (touched.holds(f) || candidate.test(f))) touched.wrote(f, at);
                }
                case "jdk.ProcessStart" -> {
                    // The program, not its arguments: a pid or a temp path in them would
                    // make every run's command new.
                    if (v[0] != null && !v[0].isBlank()) commands.add(executable(v[0]));
                }
                case "jdk.SocketRead", "jdk.SocketWrite" -> {
                    if (!local(v[1])) hosts.add(hostName(v[0], v[1]));
                }
                default -> {}
            }
        };
        String unread = null;
        for (Path jfr : recordings) {
            try {
                read(jfr, sink);
            } catch (IOException | RuntimeException unreadable) {
                // A recording a JVM did not finish (killed, out of memory), or one neither reader
                // could read, vouches for nothing.
                Log.debug("suite reads: recording unreadable at " + jfr, unreadable);
                unread = jfr.getFileName() + ": " + unreadable.getMessage();
                break;
            }
        }
        if (unread != null) {
            // Nothing of an incomplete run is used: let it go before the caller goes on.
            touched.clear();
            return new Observed(files, commands, hosts, false, unread);
        }
        files.addAll(touched.inputs());
        return new Observed(files, commands, hosts, true, null);
    }

    /**
     * Each file's first read and first write in one run, in epoch nanos, from every recording of it:
     * each JVM stamps its events from the host's clock, so the events of several JVMs order against
     * one another however the recordings are read.
     */
    static final class FirstTouches {
        private final Path workspace;
        private final Map<Path, Long> read = new TreeMap<>();
        private final Map<Path, Long> written = new TreeMap<>();

        FirstTouches(Path workspace) {
            this.workspace = workspace.toAbsolutePath().normalize();
        }

        void read(Path f, long atNanos) {
            read.merge(f, atNanos, Math::min);
        }

        void wrote(Path f, long atNanos) {
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
        Set<Path> inputs() {
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

    /**
     * One recording: through {@link RecordingReader} in the engine's heap, one read at a time; when
     * that declines it, through the JDK's own parser in a child JVM ({@link RecordingDump}), whose
     * lines are then streamed in. Events the first reader gave before it declined are given again by
     * the second; every consumer of them keeps sets, so a repeat changes nothing.
     */
    private static void read(Path jfr, RecordingReader.Sink sink) throws IOException {
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            RecordingReader.read(jfr, WANTED, sink);
            return;
        } catch (IOException declined) {
            // A file that does not even begin as a recording is no recording at all: no parser reads it.
            if (!RecordingReader.framed(jfr)) throw new IOException("not a recording", declined);
            Log.info("suite reads: " + jfr.getFileName() + " read through the JDK's parser in a child JVM: "
                    + declined.getMessage());
        }
        Path lines = RecordingDump.dump(jfr, WANTED);
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            RecordingDump.stream(lines, WANTED, sink);
        } finally {
            Files.deleteIfExists(lines);
        }
    }

    /** The name a remote endpoint is shown by: the host name the socket was opened with, else its address. */
    static String hostName(@Nullable String host, @Nullable String address) {
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
     * The files of {@code observed} no key already covers: outside every root in {@code keyed} (the
     * classes, classpath, resources, sources, JDK, outputs and sandbox of the run) and outside the
     * operating system's live pseudo trees, whose content changes with every read.
     */
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
        List<Path> roots = new ArrayList<>();
        for (Path k : keyed) {
            roots.add(k.toAbsolutePath().normalize());
            real(k).ifPresent(roots::add);
        }
        Map<Path, Boolean> outside = new LinkedHashMap<>(REAL_MEMO, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Path, Boolean> eldest) {
                return size() > REAL_MEMO;
            }
        };
        return f -> {
            if (live(f) || under(f, roots) || EnvProbeJar.isProbeJar(f)) return false;
            return outside.computeIfAbsent(
                    f, p -> real(p).filter(r -> under(r, roots)).isEmpty());
        };
    }

    /** How many real-path answers {@link #candidates} remembers. */
    static final int REAL_MEMO = 4096;

    public static Set<Path> undeclared(Observed observed, Collection<Path> keyed) {
        return undeclared(observed, keyed, null, 0);
    }

    /**
     * As {@link #undeclared(Observed, Collection)}, also leaving out a file under a directory outside
     * {@code workspace} that was created at or after {@code startedMillis}: the run made it (a temp
     * root a test framework chose, a store a nested build filled), so what is in it is the run's own
     * output, whichever process wrote it. Inside the workspace a new directory is no such proof.
     */
    public static Set<Path> undeclared(
            Observed observed, Collection<Path> keyed, @Nullable Path workspace, long startedMillis) {
        List<Path> roots = new ArrayList<>();
        for (Path k : keyed) {
            roots.add(k.toAbsolutePath().normalize());
            real(k).ifPresent(roots::add);
        }
        Map<Path, Boolean> made = new HashMap<>();
        Set<Path> out = new TreeSet<>();
        for (Path f : observed.files()) {
            if (live(f) || under(f, roots) || EnvProbeJar.isProbeJar(f)) continue;
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
    static boolean bornDuringRun(BasicFileAttributes attrs, long startedMillis) {
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
        List<Path> roots = new ArrayList<>(List.of(dir.toAbsolutePath().normalize()));
        real(dir).ifPresent(roots::add);
        Set<Path> out = new TreeSet<>();
        for (Path f : files) {
            if (under(f, roots) || real(f).filter(r -> under(r, roots)).isPresent()) out.add(f);
        }
        return out;
    }

    private static Optional<Path> real(Path p) {
        try {
            return Optional.of(p.toRealPath());
        } catch (IOException | RuntimeException absent) {
            return Optional.empty();
        }
    }

    /** The commands of {@code observed} whose program lies outside {@code keyed}: one the run made itself is its own. */
    public static Set<String> undeclaredCommands(
            Observed observed, Collection<Path> keyed, @Nullable Path workspace, long startedMillis) {
        Map<Path, Boolean> made = new HashMap<>();
        List<Path> roots = new ArrayList<>();
        for (Path k : keyed) {
            roots.add(k.toAbsolutePath().normalize());
            real(k).ifPresent(roots::add);
        }
        Set<String> out = new TreeSet<>();
        for (String c : observed.commands()) {
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
     * The inputs a suite was found to read, kept beside its outputs, and the hosts past this one it
     * reached, by name; any host at all keeps the suite from replaying unless the network is allowed.
     */
    public record Discovered(Set<Path> files, Set<String> commands, Set<String> hosts, SuiteEnvReads.Record env) {
        public static final Discovered NONE = new Discovered(Set.of(), Set.of(), Set.of());

        public Discovered(Set<Path> files, Set<String> commands, Set<String> hosts) {
            this(files, commands, hosts, SuiteEnvReads.Record.NONE);
        }

        /** This set with {@code more} added: discovered inputs only ever grow until the target dir goes. */
        public Discovered with(Set<Path> moreFiles, Set<String> moreCommands, Set<String> moreHosts) {
            return with(moreFiles, moreCommands, moreHosts, SuiteEnvReads.Record.NONE);
        }

        /** {@link #with(Set, Set, Set)}, adding what was read of the caller's environment too. */
        public Discovered with(
                Set<Path> moreFiles, Set<String> moreCommands, Set<String> moreHosts, SuiteEnvReads.Record moreEnv) {
            Set<Path> f = new TreeSet<>(files);
            f.addAll(moreFiles);
            Set<String> c = new TreeSet<>(commands);
            c.addAll(moreCommands);
            Set<String> h = new TreeSet<>(hosts);
            h.addAll(moreHosts);
            return new Discovered(f, c, h, env.with(moreEnv));
        }

        /** Whether the suite was found to reach past this host. */
        public boolean network() {
            return !hosts.isEmpty();
        }

        /** Whether {@code observed} read, started or reached anything beyond this set. */
        public boolean covers(Set<Path> readFiles, Set<String> started, Set<String> reached) {
            return files.containsAll(readFiles) && commands.containsAll(started) && hosts.containsAll(reached);
        }
    }

    /**
     * Where a module keeps what its suites under {@code selection} were found to read: in jk's cache
     * for the project, not under {@code target}, so a clean build is keyed as the build before it was.
     * The directory is named by the module's portable tag ({@link ActionKey#taskTag}: the project id
     * and the module's workspace-relative path), never its checkout path, so another checkout of the
     * project, here or on a host the cache is shared with, finds it.
     */
    static Path discoveredFile(Path moduleDir, TestSelection selection) {
        String tier = Hashing.sha256Hex(selection.identityToken()).substring(0, 12);
        String module = "m-" + ActionKey.taskTag(moduleDir);
        return CacheTree.PROJECTS
                .under(JkDirs.cache())
                .resolve(module)
                .resolve("suite-reads")
                .resolve("test-inputs-" + tier);
    }

    /** What the module's suites under {@code selection} were found to read; none before a run found any. */
    public static Discovered load(Path moduleDir, TestSelection selection) {
        Discovered standIn = SuiteReadLists.standIn(moduleDir);
        if (standIn != null) return standIn;
        Path file = discoveredFile(moduleDir, selection);
        if (!Files.isRegularFile(file)) return Discovered.NONE;
        Set<Path> files = new TreeSet<>();
        Set<String> commands = new TreeSet<>();
        Set<String> hosts = new TreeSet<>();
        Set<String> env = new TreeSet<>();
        boolean[] envFlags = new boolean[2];
        try {
            Path workspace = workspaceOf(moduleDir);
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("file ")) files.add(unspell(workspace, line.substring(5)));
                else if (line.startsWith("command ")) commands.add(unspellCommand(workspace, line.substring(8)));
                else if (line.startsWith(NETWORK_LINE)) hosts.add(line.substring(NETWORK_LINE.length()));
                else SuiteEnvReads.Record.read(line, env, envFlags);
            }
        } catch (IOException | RuntimeException unreadable) {
            // Unknown is not "none": key the suite as one that reaches past the host, and that
            // takes the whole environment.
            return new Discovered(
                    Set.of(), Set.of(), Set.of(UNKNOWN_HOST), new SuiteEnvReads.Record(Set.of(), true, true));
        }
        return new Discovered(files, commands, hosts, new SuiteEnvReads.Record(env, envFlags[0], envFlags[1]));
    }

    /** The warning code of a run that stores no marker because its tests reached another host. */
    public static final String NETWORK_WARNING = "suite-network";

    /** The warning code of a run whose recordings could not be read, so its suite will not replay. */
    public static final String UNREAD_WARNING = "suite-unread";

    /** What a run whose recordings could not be read says, on its result line and in its forecast. */
    public static String unreadMessage(String why) {
        return "suite recording could not be read: " + why.replace('\n', ' ').strip() + "; this suite will not replay";
    }

    /** The module's note of its last unread run, beside what its suites were found to read. */
    static Path unreadFile(Path moduleDir) {
        return discoveredFile(moduleDir, TestSelection.DEFAULT).resolveSibling("unread");
    }

    /** The host's note of the last unread run of any module, for {@code jk doctor}. */
    public static Path lastUnreadFile() {
        return JkDirs.state().resolve("suite-reads-unread");
    }

    /** Note that {@code moduleDir}'s last run could not be read, for the forecast and for doctor. */
    public static void noteUnread(Path moduleDir, String why) {
        try {
            Path file = unreadFile(moduleDir);
            Files.createDirectories(file.getParent());
            AtomicWrites.replaceIfChanged(file, why.replace('\n', ' ').strip() + "\n");
            Path host = lastUnreadFile();
            Files.createDirectories(host.getParent());
            AtomicWrites.replaceIfChanged(
                    host,
                    Instant.ofEpochMilli(Clock.SYSTEM.millis()) + "\t"
                            + moduleDir.toAbsolutePath().normalize() + "\t"
                            + why.replace('\n', ' ').replace('\t', ' ').strip() + "\n");
        } catch (IOException | RuntimeException unwritable) {
            Log.debug("suite reads: unread note not kept", unwritable);
        }
    }

    /** Forget {@code moduleDir}'s unread note: its last run's recordings were read. */
    public static void clearUnread(Path moduleDir) {
        try {
            Files.deleteIfExists(unreadFile(moduleDir));
            Path host = lastUnreadFile();
            if (Files.isRegularFile(host)) {
                String[] cells = Files.readString(host, StandardCharsets.UTF_8).split("\t", 3);
                if (cells.length == 3
                        && cells[1].equals(
                                moduleDir.toAbsolutePath().normalize().toString())) {
                    Files.deleteIfExists(host);
                }
            }
        } catch (IOException | RuntimeException kept) {
            Log.debug("suite reads: unread note not cleared", kept);
        }
    }

    /** Why {@code moduleDir}'s last run could not be read, or null when it was. */
    public static @Nullable String unread(Path moduleDir) {
        try {
            Path file = unreadFile(moduleDir);
            if (!Files.isRegularFile(file)) return null;
            String why = Files.readString(file, StandardCharsets.UTF_8).strip();
            return why.isEmpty() ? null : why;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The discovered file's line prefix for one host a suite reached. */
    private static final String NETWORK_LINE = "network ";

    /** The host a suite is taken to reach when its discovered file cannot be read. */
    static final String UNKNOWN_HOST = "an unknown host";

    /** Keep {@code d} for the module's suites under {@code selection}. Best-effort. */
    public static void save(Path moduleDir, TestSelection selection, Discovered d) {
        StringBuilder sb = new StringBuilder();
        // Each file as the key spells it, so the list means the same in every checkout that reads it.
        Path workspace = workspaceOf(moduleDir);
        for (Path f : d.files()) sb.append("file ").append(keep(workspace, f)).append('\n');
        for (String c : d.commands())
            sb.append("command ")
                    .append(keepCommand(workspace, c).replace('\n', ' '))
                    .append('\n');
        for (String h : d.hosts()) sb.append(NETWORK_LINE).append(h).append('\n');
        for (String e : d.env().lines()) sb.append(e).append('\n');
        try {
            Path file = discoveredFile(moduleDir, selection);
            Files.createDirectories(file.getParent());
            AtomicWrites.replaceIfChanged(file, sb.toString());
        } catch (IOException | RuntimeException unwritable) {
            Log.debug("suite reads: not kept", unwritable);
        }
    }

    /**
     * The stamp tokens for what the module's suites were found to read: each file by content (or
     * {@code absent}) and the listing of the directory it sits in, each started command by its
     * executable's identity, and {@code network} for a suite that reached past the host. A path
     * inside {@code workspaceRoot} is spelled relative to it, so checkouts share the key.
     */
    public static List<String> tokens(Path moduleDir, TestSelection selection, Path workspaceRoot) {
        return tokens(moduleDir, selection, workspaceRoot, SuiteEnvReads.Policy.NONE);
    }

    /**
     * {@link #tokens(Path, TestSelection, Path)} with what the suites read of the caller's
     * environment, under the module's {@code policy}, after the files ({@link SuiteEnvReads#tokens}).
     */
    public static List<String> tokens(
            Path moduleDir, TestSelection selection, Path workspaceRoot, SuiteEnvReads.Policy policy) {
        return tokens(moduleDir, selection, workspaceRoot, policy, List.of());
    }

    /**
     * {@link #tokens(Path, TestSelection, Path, SuiteEnvReads.Policy)} for a module that declares
     * {@code declaredTools} in {@code [test] tools}: a started program of that name keys by the
     * declaration ({@link SuitePrograms}).
     */
    public static List<String> tokens(
            Path moduleDir,
            TestSelection selection,
            Path workspaceRoot,
            SuiteEnvReads.Policy policy,
            Collection<String> declaredTools) {
        Discovered d = load(moduleDir, selection);
        List<String> env = SuiteEnvReads.tokens(d.env(), policy, CallerEnv.current());
        if (d.files().isEmpty() && d.commands().isEmpty() && !d.network()) return env;
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        Set<Path> dirs = new TreeSet<>();
        Map<Path, Boolean> outputs = new HashMap<>();
        for (Path f : d.files()) {
            String content;
            try {
                // One stat for the file that is there, which is nearly every one.
                content = FileHashMemo.contentHash(f);
            } catch (IOException notHashed) {
                content = Files.isRegularFile(f) ? "unreadable" : "absent";
            }
            out.add("read:" + spell(root, f) + "=" + content);
            Path parent = f.getParent();
            if (parent != null && keysListing(parent, root, outputs)) dirs.add(parent);
        }
        for (Path dir : dirs) out.add("list:" + spell(root, dir) + "=" + listing(dir));
        // One line per tool: /bin/sh and sh, or one tool started twice, key once.
        Set<String> programs = new TreeSet<>();
        for (String c : d.commands()) programs.add(SuitePrograms.token(root, c, moduleDir, declaredTools));
        out.addAll(programs);
        if (d.network()) out.add("network");
        out.addAll(env);
        return out;
    }

    /** Whether {@code token} is one {@link #tokens} makes: a read file, a listing, a program or the network. */
    public static boolean isReadsToken(String token) {
        return token.startsWith("read:")
                || token.startsWith("list:")
                || token.startsWith("proc:")
                || token.equals("network")
                || SuiteEnvReads.isToken(token);
    }

    /** {@link #tokens} as {@link #memoDigest}. */
    public static String digest(Path moduleDir, TestSelection selection, Path workspaceRoot) {
        return digest(moduleDir, selection, workspaceRoot, SuiteEnvReads.Policy.NONE);
    }

    /** {@link #tokens(Path, TestSelection, Path, SuiteEnvReads.Policy)} as {@link #memoDigest}. */
    public static String digest(
            Path moduleDir, TestSelection selection, Path workspaceRoot, SuiteEnvReads.Policy policy) {
        return memoDigest(tokens(moduleDir, selection, workspaceRoot, policy), workspaceRoot);
    }

    /**
     * {@code tokens} as one digest for the preflight memo, leaving out what lies in a module's build
     * output: the producing module's own inputs vouch for it, and the memo is taken before the build,
     * when a clean tree has none. {@code none} for no tokens.
     */
    public static String memoDigest(List<String> tokens, Path workspaceRoot) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<String> kept = new ArrayList<>();
        Map<Path, Boolean> outputs = new HashMap<>();
        for (String t : tokens) {
            int eq = t.lastIndexOf('=');
            boolean pathToken = (t.startsWith("read:") || t.startsWith("list:")) && eq > 5;
            if (pathToken && inBuildOutput(tokenPath(root, t.substring(5, eq)), root, outputs)) continue;
            kept.add(t);
        }
        return kept.isEmpty() ? "none" : Hashing.sha256Hex(String.join("\n", kept));
    }

    /**
     * The file or directory a {@code read:} or {@code list:} token names, as {@link #spell} wrote it:
     * workspace-relative, under a jk root ({@code store:}, {@code cache:}, {@code jk-home:}), or
     * absolute. A jk root's spelling is no path of its own: on Windows its colon is refused.
     */
    private static Path tokenPath(Path root, String spelled) {
        return root.resolve(PortablePath.fromJkRoot(spelled)).normalize();
    }

    /** {@code p} as a token spells it: workspace-relative, else under a root by its word, else absolute. */
    static String spellUnder(Path root, Path p) {
        return spell(root, p);
    }

    private static String spell(Path root, Path p) {
        return p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : PortablePath.underJkRoot(p);
    }

    /** The line prefix of a kept file inside the workspace, which follows the checkout. */
    private static final String WORKSPACE = "ws:";

    /** {@code f} as a kept line spells it: workspace-relative, else relative to a jk root, else absolute. */
    static String keep(Path workspace, Path f) {
        Path abs = f.toAbsolutePath().normalize();
        if (abs.startsWith(workspace))
            return WORKSPACE + workspace.relativize(abs).toString().replace('\\', '/');
        return PortablePath.underJkRoot(abs);
    }

    /** {@code command} as a kept line spells it: a program's path as {@link #keep} spells a file, a bare name as it is. */
    static String keepCommand(Path workspace, String command) {
        try {
            Path file = Path.of(command);
            if (file.isAbsolute()) return keep(workspace, file);
        } catch (InvalidPathException notAPath) {
            // kept as written
        }
        return command;
    }

    /** The command a kept line names, on this checkout and host, as {@link #executable} spells it. */
    static String unspellCommand(Path workspace, String kept) {
        if (!spelledUnderRoot(kept)) return hostSpelling(kept);
        return unspell(workspace, kept).toString();
    }

    /**
     * {@code program} as this host spells a path: an absolute one with its separators ({@code
     * C:\Program Files\...} on Windows, where a kept line has {@code /}), anything else as written. A
     * program read back is compared with one a run observed, so both take this one spelling.
     */
    static String hostSpelling(String program) {
        try {
            Path p = Path.of(program);
            return p.isAbsolute() ? p.toString() : program;
        } catch (InvalidPathException notAPath) {
            return program;
        }
    }

    /** Whether {@code kept} begins with a root word ({@code ws:}, {@code store:}, …) rather than naming a program itself. */
    static boolean spelledUnderRoot(String kept) {
        if (kept.startsWith(WORKSPACE)) return true;
        for (String word : PortablePath.ROOT_WORDS) {
            if (kept.startsWith(word)) return true;
        }
        return false;
    }

    /** The file a kept line names, on this checkout and host. */
    static Path unspell(Path workspace, String kept) {
        if (kept.startsWith(WORKSPACE))
            return workspace.resolve(kept.substring(WORKSPACE.length())).normalize();
        return PortablePath.fromJkRoot(kept);
    }

    static Path workspaceOf(Path moduleDir) {
        return WorkspaceScan.findRoot(moduleDir)
                .orElse(moduleDir)
                .toAbsolutePath()
                .normalize();
    }

    private static String listing(Path dir) {
        if (!Files.isDirectory(dir)) return "absent";
        Set<String> names = new TreeSet<>();
        try {
            String output = outputEntry(dir);
            PathUtil.forEachChild(dir, (child, attrs) -> {
                String name = String.valueOf(child.getFileName());
                if (!name.equals(output)) names.add(name);
                return true;
            });
        } catch (IOException unreadable) {
            return "unreadable";
        }
        return Hashing.sha256Hex(String.join("\n", names));
    }

    /**
     * Whether a suite that reads a file in {@code dir} is keyed on the directory's listing too: only
     * in the workspace's own tree, outside every module's build output and outside git's database.
     * A build output's listing moves as the build writes it, git's as every git command swaps its
     * index and lock files, and a directory outside the workspace (the home directory, a store) as
     * any other process writes there; there a read file is keyed by its content alone.
     */
    public static boolean keysListing(Path dir, Path workspaceRoot) {
        return keysListing(
                dir.toAbsolutePath().normalize(), workspaceRoot.toAbsolutePath().normalize(), new HashMap<>());
    }

    private static boolean keysListing(Path dir, Path root, Map<Path, Boolean> outputs) {
        return dir.startsWith(root) && !inGitDir(root.relativize(dir)) && !inBuildOutput(dir, root, outputs);
    }

    /** Whether {@code rel}, a path under the workspace root, lies in a {@code .git} directory. */
    private static boolean inGitDir(Path rel) {
        for (Path name : rel) {
            if (name.toString().equals(GIT_DIR)) return true;
        }
        return false;
    }

    private static final String GIT_DIR = ".git";

    /**
     * Whether {@code path} (absolute, normalized) lies in a workspace module's build output. {@code
     * memo} holds the answer for each directory already judged, so a tree of reads asks each
     * directory once.
     */
    private static boolean inBuildOutput(Path path, Path root, Map<Path, Boolean> memo) {
        if (!path.startsWith(root)) return false;
        Boolean known = memo.get(path);
        if (known != null) return known;
        Path name = path.getFileName();
        Path parent = path.getParent();
        boolean output = name != null
                && parent != null
                && name.toString().equals(BuildLayout.TARGET)
                && Files.isRegularFile(ManifestPaths.manifestIn(parent));
        if (!output && parent != null) output = inBuildOutput(parent, root, memo);
        memo.put(path, output);
        return output;
    }

    /**
     * The first entry of {@code dir}'s keyed listing that may have come into being once a run started
     * at {@code startedMillis}, or null when none did. An entry made and removed since is gone, and
     * the listing is as it was.
     */
    public static @Nullable Path bornSince(Path dir, long startedMillis) throws IOException {
        String output = outputEntry(dir);
        Path[] born = new Path[1];
        PathUtil.forEachChild(dir, (child, attrs) -> {
            if (String.valueOf(child.getFileName()).equals(output)) return true;
            if (bornDuringRun(attrs, startedMillis)) born[0] = child;
            return born[0] == null;
        });
        return born[0];
    }

    /**
     * The entry a module directory's listing leaves out: its build output, which the build itself
     * makes and removes; empty for any other directory.
     */
    private static String outputEntry(Path dir) {
        return Files.isRegularFile(ManifestPaths.manifestIn(dir)) ? BuildLayout.TARGET : "";
    }

    /**
     * The program a recorded command line started. JFR records the command as its words joined by
     * spaces, so a program whose path holds a space ({@code C:/Program Files/...}) cannot be told
     * from its arguments by the first word: the program is the shortest run of words that names an
     * absolute file that is there (on Windows also with {@code .exe} added), else the first word.
     * What this answers is a program, kept and read back whole ({@link #hostSpelling}); nothing
     * downstream splits it again.
     */
    static String executable(String command) {
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
}
