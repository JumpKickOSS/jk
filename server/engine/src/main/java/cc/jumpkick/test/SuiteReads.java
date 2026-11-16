// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestSelection;
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
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.ToolIdentity;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
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

    /** Empty {@code dir} and write the recording settings into it; returns the flag every suite JVM takes. */
    public static String prepare(Path dir) throws IOException {
        PathUtil.deleteRecursively(dir);
        Files.createDirectories(dir);
        Path settings = dir.resolve("reads.jfc");
        Files.writeString(settings, SETTINGS, StandardCharsets.UTF_8);
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
     * What one run observed: files read and not written by the run itself, commands started, the
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
     * The most distinct files one run's recordings may name, read and written together. Past it the
     * run's reads are not kept (the suite is not replayed): jk's largest suites name about 8,000.
     */
    static final int MAX_PATHS = 32 * 1024;

    /**
     * The heap a kept path is counted at: the path, its bytes and its set entry (8,264 paths held
     * 4 MB, measured on jk-engine's suite).
     */
    static final long BYTES_PER_PATH = 512;

    /**
     * The most heap reading one run's recordings can hold: the reader's own peak and the paths it
     * keeps. Memory admission keeps this much of the engine's heap free of jobs ({@code
     * MemoryAdmission}), and {@link RecordingReader.Permit} lets one read run at a time, so a job is never admitted
     * into a read's peak.
     */
    public static final long READ_PEAK_BYTES = RecordingReader.PEAK_BYTES + MAX_PATHS * BYTES_PER_PATH;

    /**
     * Every recording under {@code dir}, relative paths resolved against {@code cwd}. The recordings
     * are streamed by {@link RecordingReader}, one at a time across the engine; a recording past the
     * reader's caps, or a run that names more than {@link #MAX_PATHS} files, is incomplete.
     */
    public static Observed collect(Path dir, Path cwd) {
        Set<Path> files = new TreeSet<>();
        Set<Path> written = new TreeSet<>();
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
                    if (v[0] != null && !v[0].isBlank()) resolve(cwd, v[0], files);
                }
                case "jdk.FileWrite" -> {
                    if (v[0] != null && !v[0].isBlank()) resolve(cwd, v[0], written);
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
            if (files.size() + written.size() > MAX_PATHS) throw new TooManyPaths();
        };
        String unread = null;
        for (Path jfr : recordings) {
            try {
                read(jfr, sink);
            } catch (TooManyPaths wide) {
                unread = wide.getMessage();
                break;
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
            files.clear();
            written.clear();
            return new Observed(files, commands, hosts, false, unread);
        }
        // A file the run wrote is its own output: reading it back depends on nothing outside.
        files.removeAll(written);
        return new Observed(files, commands, hosts, true, null);
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
        } catch (TooManyPaths wide) {
            throw wide;
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

    /** A run that names more files than {@link #MAX_PATHS}. */
    private static final class TooManyPaths extends RuntimeException {
        TooManyPaths() {
            super("a run named more than " + MAX_PATHS + " files", null, false, false);
        }
    }

    /** The name a remote endpoint is shown by: the host name the socket was opened with, else its address. */
    static String hostName(@Nullable String host, @Nullable String address) {
        String name = host != null && !host.isBlank() ? host : String.valueOf(address);
        return name.strip().replace('\n', ' ');
    }

    private static void resolve(Path cwd, String raw, Set<Path> into) {
        try {
            into.add(cwd.resolve(raw).toAbsolutePath().normalize());
        } catch (InvalidPathException odd) {
            // A pseudo path (a pipe, a descriptor) is not a file the suite depends on.
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
    public record Discovered(Set<Path> files, Set<String> commands, Set<String> hosts) {
        public static final Discovered NONE = new Discovered(Set.of(), Set.of(), Set.of());

        /** This set with {@code more} added: discovered inputs only ever grow until the target dir goes. */
        public Discovered with(Set<Path> moreFiles, Set<String> moreCommands, Set<String> moreHosts) {
            Set<Path> f = new TreeSet<>(files);
            f.addAll(moreFiles);
            Set<String> c = new TreeSet<>(commands);
            c.addAll(moreCommands);
            Set<String> h = new TreeSet<>(hosts);
            h.addAll(moreHosts);
            return new Discovered(f, c, h);
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
     */
    static Path discoveredFile(Path moduleDir, TestSelection selection) {
        String tier = Hashing.sha256Hex(selection.identityToken()).substring(0, 12);
        Path dir = moduleDir.toAbsolutePath().normalize();
        try {
            if (Files.exists(dir)) dir = dir.toRealPath();
        } catch (IOException keepNormalized) {
            // the normalized path names it
        }
        String module = Hashing.sha256Hex(dir.toString()).substring(0, 16);
        return CacheTree.PROJECTS
                .under(JkDirs.cache())
                .resolve(module)
                .resolve("suite-reads")
                .resolve("test-inputs-" + tier);
    }

    /** What the module's suites under {@code selection} were found to read; none before a run found any. */
    public static Discovered load(Path moduleDir, TestSelection selection) {
        Path file = discoveredFile(moduleDir, selection);
        if (!Files.isRegularFile(file)) return Discovered.NONE;
        Set<Path> files = new TreeSet<>();
        Set<String> commands = new TreeSet<>();
        Set<String> hosts = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("file ")) files.add(Path.of(line.substring(5)));
                else if (line.startsWith("command ")) commands.add(line.substring(8));
                else if (line.startsWith(NETWORK_LINE)) hosts.add(line.substring(NETWORK_LINE.length()));
            }
        } catch (IOException | RuntimeException unreadable) {
            // Unknown is not "none": key the suite as one that reaches past the host.
            return new Discovered(Set.of(), Set.of(), Set.of(UNKNOWN_HOST));
        }
        return new Discovered(files, commands, hosts);
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
        for (Path f : d.files()) sb.append("file ").append(f).append('\n');
        for (String c : d.commands())
            sb.append("command ").append(c.replace('\n', ' ')).append('\n');
        for (String h : d.hosts()) sb.append(NETWORK_LINE).append(h).append('\n');
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
        Discovered d = load(moduleDir, selection);
        if (d.files().isEmpty() && d.commands().isEmpty() && !d.network()) return List.of();
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
        for (String c : d.commands()) out.add("proc:" + c + "=" + programIdentity(c, moduleDir));
        if (d.network()) out.add("network");
        return out;
    }

    /** Whether {@code token} is one {@link #tokens} makes: a read file, a listing, a program or the network. */
    public static boolean isReadsToken(String token) {
        return token.startsWith("read:")
                || token.startsWith("list:")
                || token.startsWith("proc:")
                || token.equals("network");
    }

    /** {@link #tokens} as {@link #memoDigest}. */
    public static String digest(Path moduleDir, TestSelection selection, Path workspaceRoot) {
        return memoDigest(tokens(moduleDir, selection, workspaceRoot), workspaceRoot);
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
            if (pathToken && inBuildOutput(root.resolve(t.substring(5, eq)).normalize(), root, outputs)) continue;
            kept.add(t);
        }
        return kept.isEmpty() ? "none" : Hashing.sha256Hex(String.join("\n", kept));
    }

    private static String spell(Path root, Path p) {
        return p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : p.toString();
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
     * A started program by content when the command named its file, else by what the name, resolved
     * on the PATH the suite gets, says to {@code --version} ({@link ToolIdentity}).
     */
    private static String programIdentity(String program, Path moduleDir) {
        try {
            Path file = Path.of(program);
            if (file.isAbsolute()) {
                return Files.isRegularFile(file) ? FileHashMemo.contentHash(file.normalize()) : "absent";
            }
        } catch (InvalidPathException | IOException unreadable) {
            return "unreadable";
        }
        return ToolIdentity.of(program, BuildEnv.machine().get("PATH"), moduleDir);
    }

    /**
     * The program a recorded command line started. JFR records the command as its words joined by
     * spaces, so a program whose path holds a space ({@code C:/Program Files/...}) cannot be told
     * from its arguments by the first word: the program is the shortest run of words that names an
     * absolute file that is there (on Windows also with {@code .exe} added), else the first word.
     * What this answers is a program, kept and read back whole; nothing downstream splits it again.
     */
    static String executable(String command) {
        String c = command.strip();
        int space = c.indexOf(' ');
        if (space < 0) return c;
        for (int at = space; at >= 0; at = c.indexOf(' ', at + 1)) {
            String words = c.substring(0, at);
            if (namesProgram(words)) return words;
        }
        return namesProgram(c) ? c : c.substring(0, space);
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
