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
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.orchestrator.DiscoveryRules;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.PortablePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
        Path settings = dir.resolve(SETTINGS);
        Files.writeString(settings, DiscoveryRules.recordingSettings(TestTelemetry.on()), StandardCharsets.UTF_8);
        return recordingFlag(dir);
    }

    /** The recording's settings file, beside its recordings. */
    private static final String SETTINGS = "reads.jfc";

    /** The flag that records into {@code dir} under the settings file written there. */
    static String recordingFlag(Path dir) {
        return "-XX:StartFlightRecording=settings=" + jfrPath(dir.resolve(SETTINGS)) + ",filename=" + jfrPath(dir)
                + ",dumponexit=true";
    }

    /**
     * The run-tests stamp's spelling of the recording flag, the directory a stand-in. What the
     * settings record is the orchestrator's ({@code DiscoveryRules}), which the stamp keys by its
     * classes.
     */
    public static String recordingToken() {
        return recordingFlag(Path.of("@recording")).replace('\\', '/');
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

    /**
     * The heap counted for the inputs one run is found to read: files outside every root the suite's
     * key already covers ({@link DiscoveryRules#candidates}), which the key must hash one by one anyway. jk-engine's
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
     * {@link DiscoveryRules.FirstTouches#inputs} against {@code workspace}. The recordings are streamed by {@link
     * RecordingReader}, one at a time across the engine; only a recording no reader can read is incomplete.
     */
    public static Observed collect(Path dir, Path cwd, Path workspace, Predicate<Path> candidate) {
        List<Path> recordings = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (child.getFileName().toString().endsWith(".jfr")) recordings.add(child);
                return true;
            });
        } catch (IOException none) {
            return new Observed(Set.of(), Set.of(), Set.of(), false, "the suite wrote no recording");
        }
        if (recordings.isEmpty()) return new Observed(Set.of(), Set.of(), Set.of(), true, null);
        DiscoveryRules.Recording seen = new DiscoveryRules.Recording(cwd, workspace, candidate);
        RecordingReader.Sink sink = (name, at, end, v) -> seen.event(name, at, v);
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
            seen.clear();
            return new Observed(Set.of(), seen.commands(), seen.hosts(), false, unread);
        }
        return new Observed(seen.files(), seen.commands(), seen.hosts(), true, null);
    }

    /**
     * One recording: through {@link RecordingReader} in the engine's heap, one read at a time; when
     * that declines it, through the JDK's own parser in a child JVM ({@link RecordingDump}), whose
     * lines are then streamed in. Events the first reader gave before it declined are given again by
     * the second; every consumer of them keeps sets, so a repeat changes nothing.
     */
    private static void read(Path jfr, RecordingReader.Sink sink) throws IOException {
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            RecordingReader.read(jfr, DiscoveryRules.EVENTS, sink);
            return;
        } catch (IOException declined) {
            // A file that does not even begin as a recording is no recording at all: no parser reads it.
            if (!RecordingReader.framed(jfr)) throw new IOException("not a recording", declined);
            Log.info("suite reads: " + jfr.getFileName() + " read through the JDK's parser in a child JVM: "
                    + declined.getMessage());
        }
        Path lines = RecordingDump.dump(jfr, DiscoveryRules.EVENTS);
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            RecordingDump.stream(lines, DiscoveryRules.EVENTS, sink);
        } finally {
            Files.deleteIfExists(lines);
        }
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

    /** The file a {@code read:} token names or the directory a {@code list:} token does; null for any other token. */
    public static @Nullable Path tokenFile(Path workspaceRoot, String token) {
        int eq = token.lastIndexOf('=');
        if (!(token.startsWith("read:") || token.startsWith("list:")) || eq <= 5) return null;
        return tokenPath(workspaceRoot.toAbsolutePath().normalize(), token.substring(5, eq));
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

    /** The command a kept line names, on this checkout and host, as {@link DiscoveryRules#executable} spells it. */
    static String unspellCommand(Path workspace, String kept) {
        if (!spelledUnderRoot(kept)) return DiscoveryRules.hostSpelling(kept);
        return unspell(workspace, kept).toString();
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
            if (DiscoveryRules.bornDuringRun(attrs, startedMillis)) born[0] = child;
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
}
