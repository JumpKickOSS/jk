// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.ToolIdentity;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
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
     * What one run observed: files read and not written by the run itself, commands started,
     * whether it reached past this host, and whether every recording read back ({@code complete}
     * false vouches for nothing this run).
     */
    public record Observed(Set<Path> files, Set<String> commands, boolean network, boolean complete) {}

    /** Every recording under {@code dir}, relative paths resolved against {@code cwd}. */
    public static Observed collect(Path dir, Path cwd) {
        Set<Path> files = new TreeSet<>();
        Set<Path> written = new TreeSet<>();
        Set<String> commands = new TreeSet<>();
        boolean network = false;
        boolean complete = true;
        List<Path> recordings = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (child.getFileName().toString().endsWith(".jfr")) recordings.add(child);
                return true;
            });
        } catch (IOException none) {
            return new Observed(files, commands, false, false);
        }
        for (Path jfr : recordings) {
            try (RecordingFile in = new RecordingFile(jfr)) {
                while (in.hasMoreEvents()) {
                    RecordedEvent e = in.readEvent();
                    switch (e.getEventType().getName()) {
                        case "jdk.FileRead" -> {
                            String p = e.getString("path");
                            if (p != null && !p.isBlank()) resolve(cwd, p, files);
                        }
                        case "jdk.FileWrite" -> {
                            String p = e.getString("path");
                            if (p != null && !p.isBlank()) resolve(cwd, p, written);
                        }
                        case "jdk.ProcessStart" -> {
                            // The program, not its arguments: a pid or a temp path in them would
                            // make every run's command new.
                            String c = e.getString("command");
                            if (c != null && !c.isBlank()) commands.add(executable(c));
                        }
                        case "jdk.SocketRead", "jdk.SocketWrite" -> network |= !local(e.getString("address"));
                        default -> {}
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                // A recording a JVM did not finish (killed, out of memory) vouches for nothing.
                Log.debug("suite reads: recording unreadable at " + jfr, unreadable);
                complete = false;
            }
        }
        // A file the run wrote is its own output: reading it back depends on nothing outside.
        files.removeAll(written);
        return new Observed(files, commands, network, complete);
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
            if (live(f) || under(f, roots)) continue;
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

    /** Whether an ancestor of {@code f} outside {@code workspace} came into being once the run had started. */
    static boolean madeDuringRun(Path f, Path workspace, long startedMillis, Map<Path, Boolean> memo) {
        Path ws = workspace.toAbsolutePath().normalize();
        for (Path dir = f.getParent(); dir != null && dir.getParent() != null; dir = dir.getParent()) {
            if (dir.startsWith(ws) || ws.startsWith(dir)) continue;
            Boolean known = memo.get(dir);
            if (known == null) {
                try {
                    // A second of slack: a file system's creation clock is coarser than ours.
                    known = Files.readAttributes(dir, BasicFileAttributes.class)
                                    .creationTime()
                                    .toMillis()
                            >= startedMillis - 1_000;
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
                Path program = Path.of(executable(c));
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

    /** The inputs a suite was found to read, kept beside its outputs. */
    public record Discovered(Set<Path> files, Set<String> commands, boolean network) {
        public static final Discovered NONE = new Discovered(Set.of(), Set.of(), false);

        /** This set with {@code more} added: discovered inputs only ever grow until the target dir goes. */
        public Discovered with(Set<Path> moreFiles, Set<String> moreCommands, boolean moreNetwork) {
            Set<Path> f = new TreeSet<>(files);
            f.addAll(moreFiles);
            Set<String> c = new TreeSet<>(commands);
            c.addAll(moreCommands);
            return new Discovered(f, c, network || moreNetwork);
        }

        /** Whether {@code observed} read, started or reached anything beyond this set. */
        public boolean covers(Set<Path> readFiles, Set<String> started, boolean reached) {
            return files.containsAll(readFiles) && commands.containsAll(started) && (network || !reached);
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
        boolean network = false;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("file ")) files.add(Path.of(line.substring(5)));
                else if (line.startsWith("command ")) commands.add(line.substring(8));
                else if (line.equals("network")) network = true;
            }
        } catch (IOException | RuntimeException unreadable) {
            // Unknown is not "none": key the suite as one that reaches past the host.
            return new Discovered(Set.of(), Set.of(), true);
        }
        return new Discovered(files, commands, network);
    }

    /** Keep {@code d} for the module's suites under {@code selection}. Best-effort. */
    public static void save(Path moduleDir, TestSelection selection, Discovered d) {
        StringBuilder sb = new StringBuilder();
        for (Path f : d.files()) sb.append("file ").append(f).append('\n');
        for (String c : d.commands())
            sb.append("command ").append(c.replace('\n', ' ')).append('\n');
        if (d.network()) sb.append("network\n");
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
            if (parent != null && parent.startsWith(root) && !inBuildOutput(parent, root, outputs)) dirs.add(parent);
        }
        for (Path dir : dirs) out.add("list:" + spell(root, dir) + "=" + listing(dir));
        for (String c : d.commands()) out.add("proc:" + executable(c) + "=" + programIdentity(executable(c)));
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
     * in the workspace's own tree, outside every module's build output. A build output's listing
     * moves as the build writes it, and a directory outside the workspace (the home directory, a
     * store) as any other process writes there; there a read file is keyed by its content alone.
     */
    public static boolean keysListing(Path dir, Path workspaceRoot) {
        Path d = dir.toAbsolutePath().normalize();
        Path root = workspaceRoot.toAbsolutePath().normalize();
        return d.startsWith(root) && !inBuildOutput(d, root, new HashMap<>());
    }

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
     * The first entry of {@code dir}'s keyed listing that came into being at or after {@code
     * millis}, or null when none did. An entry made and removed since is gone, and the listing is
     * as it was.
     */
    public static @Nullable Path bornSince(Path dir, long millis) throws IOException {
        String output = outputEntry(dir);
        Path[] born = new Path[1];
        PathUtil.forEachChild(dir, (child, attrs) -> {
            if (String.valueOf(child.getFileName()).equals(output)) return true;
            if (attrs.creationTime().toMillis() >= millis) born[0] = child;
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
     * A started program by content when the command named its file, else by what the PATH the suite
     * gets resolves the name to and what it says to {@code --version}.
     */
    private static String programIdentity(String program) {
        try {
            Path file = Path.of(program);
            if (file.isAbsolute()) {
                return Files.isRegularFile(file) ? FileHashMemo.contentHash(file.normalize()) : "absent";
            }
        } catch (InvalidPathException | IOException unreadable) {
            return "unreadable";
        }
        return ToolIdentity.of(program, BuildEnv.machine().get("PATH"));
    }

    /** The program a recorded command line started: its first word. */
    private static String executable(String command) {
        String c = command.strip();
        int space = c.indexOf(' ');
        return space < 0 ? c : c.substring(0, space);
    }
}
