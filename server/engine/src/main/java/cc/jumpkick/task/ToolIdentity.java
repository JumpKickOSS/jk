// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.ChildEnv;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Processes;
import cc.jumpkick.host.SearchPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The identity of an external tool a suite shells out to — {@code node}, {@code git}, {@code
 * protoc} — as a run-tests input: what the executable the name resolves to on the PATH the test JVM
 * gets says to {@code --version}, never where it is installed, so two machines whose tool prints the
 * same version share the stamp, and a binary is asked once while its file holds still. A POSIX
 * utility whose version is the OS's ({@link #STABLE}) is keyed by name and OS family alone. An
 * upgrade changes the version line; a tool that is not on the PATH
 * is {@link #MISSING}, so a suite that went green with the tool and now runs without it is retested
 * rather than replayed.
 *
 * <p>The version is asked in the suite's directory with the request's environment, as the suite
 * would ask it: a toolchain manager's shim ({@code mise}, {@code asdf}, {@code volta}) answers
 * with the version the directory and the shell select, which can change without the binary
 * changing. So the answer is remembered for one request only; a tool that exited without a version
 * line is asked again, and one that hung is not asked again while its file is unchanged.
 */
public final class ToolIdentity {

    /** The value a declared tool takes when the PATH has no executable of that name. */
    public static final String MISSING = "(not on PATH)";

    /** A tool that hangs on {@code --version} is not going to tell us anything better later. */
    private static final long VERSION_TIMEOUT_MILLIS = 5_000;

    /** What {@link #probe} answers when the tool printed no version line or did not exit in time. */
    static final String NO_VERSION = "no-version";

    /** The request-scope key of one tool's version as asked from one directory. */
    private record VersionKey(Path exe, Path dir, @Nullable String path) {}

    /** The request-scope key of the versions this request has read. */
    private record Versions() {}

    private static final Versions VERSIONS = new Versions();

    /**
     * The executables that hung on {@code --version}, while the file keeps its stat. A hang is the
     * binary's, not the directory's or the request's: remembered per request and per directory, it
     * cost every module whose suite starts it the whole timeout on every build, as two Testcontainers
     * modules of hotel-reservations paid 5.1 s each on every replayed {@code jk test -g} on WSL.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, Boolean> HUNG =
            StampedMemo.<Path, StampedMemo.FileStamp, Boolean>bounded(64).keeping(Boolean::booleanValue);

    /**
     * The version line of each binary, while its file keeps its stat: what a real binary (not a
     * version manager's shim, whose answer moves with the directory) says to {@code --version}
     * depends on its bytes alone, so it is asked once per binary for the engine's life rather than
     * once per request.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, String> BY_BINARY =
            StampedMemo.<Path, StampedMemo.FileStamp, String>bounded(256).keeping(line -> !line.equals(NO_VERSION));

    /**
     * POSIX utilities whose behaviour a suite depends on only as the operating system ships them:
     * their version is the OS's, and a suite that runs {@code sh -c}, {@code sleep 1} or {@code ps}
     * does not change outcome between a Mac and a Linux host. Keyed by name and {@link #osFamily}
     * alone. A tool whose flavours differ in what a script sees ({@code bash}, {@code sed}, {@code
     * grep}, {@code awk}, {@code find}) is not one of them.
     */
    static final Set<String> STABLE = Set.of(
            "sh", "sleep", "ps", "kill", "true", "false", "env", "cat", "echo", "ls", "mkdir", "rm", "cp", "mv",
            "chmod", "ln", "touch", "uname", "id", "pwd", "printf", "test", "nohup");

    private ToolIdentity() {}

    /** Whether {@code tool} (a bare name, or a program's file name) is one of {@link #STABLE}. */
    public static boolean stable(String tool) {
        return STABLE.contains(toolName(tool));
    }

    /** The family of operating system a {@link #STABLE} tool comes with: {@code windows} or {@code posix}. */
    public static String osFamily() {
        return Os.isWindows() ? "windows" : "posix";
    }

    /**
     * A program's name as a tool: its file name, less {@code .exe} on Windows, so {@code /bin/sh},
     * {@code /usr/bin/sh} and {@code sh} are one tool.
     */
    public static String toolName(String program) {
        String name = program;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        return Os.isWindows() && name.toLowerCase(Locale.ROOT).endsWith(".exe")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    /**
     * The version line of the program at {@code exe} (an absolute path a suite started), asked in
     * {@code dir} as {@link #of} asks a tool on the PATH and {@link #normalized}; {@link #MISSING}
     * when there is no runnable file. Asked once per binary while its file holds still.
     */
    public static String ofProgram(Path exe, Path dir) {
        if (!Files.isRegularFile(exe) || !PathUtil.isRunnable(exe)) return MISSING;
        return normalized(versionOfBinary(exe, dir, BuildEnv.machine().get("PATH")), exe);
    }

    /**
     * The version line of {@code tool} resolved on {@code path} (the PATH the test JVM gets), asked
     * in {@code dir}, the directory the suite runs in, and {@link #normalized}; or {@link #MISSING}.
     */
    public static String of(String tool, @Nullable String path, Path dir) {
        Path exe = resolve(tool, path);
        if (exe == null) return MISSING;
        return normalized(versionOfBinary(exe, dir, path), exe);
    }

    /**
     * A version line with no machine in it: the executable's own path reads as its file name, the
     * user's home as {@code ~}, and each run of whitespace as one space.
     */
    static String normalized(String line, Path exe) {
        String out = line.replace(exe.toString(), exe.getFileName().toString());
        String home = System.getProperty("user.home");
        if (home != null && home.length() > 1) out = out.replace(home, "~");
        return out.strip().replaceAll("\\s+", " ");
    }

    /**
     * The first executable named {@code tool} on {@code path}, Windows extensions included. A blank
     * entry — the current directory on POSIX — and an entry that is not a path are skipped: a
     * tool's identity must not depend on where the engine happens to be, or on garbage in PATH.
     */
    static @Nullable Path resolve(String tool, @Nullable String path) {
        List<String> names =
                Os.isWindows() ? List.of(tool + ".exe", tool + ".cmd", tool + ".bat", tool) : List.of(tool);
        for (String dir : SearchPath.entries(path)) {
            Path dirPath = SearchPath.path(dir);
            if (dirPath == null) continue;
            for (String name : names) {
                Path candidate = dirPath.resolve(name);
                if (Files.isRegularFile(candidate) && PathUtil.isRunnable(candidate)) return candidate;
            }
        }
        return null;
    }

    /**
     * {@code exe}'s version line from {@link #BY_BINARY} when it is a binary of its own ({@link
     * #ownBinary}), else asked for this request ({@link #version}).
     */
    private static String versionOfBinary(Path exe, Path dir, @Nullable String path) {
        Path real = ownBinary(exe);
        StampedMemo.FileStamp stamp = real == null ? null : StampedMemo.FileStamp.of(real);
        if (real == null || stamp == null) return version(exe, dir, path);
        return BY_BINARY.get(real, stamp, () -> version(exe, dir, path));
    }

    /**
     * The real file of {@code exe} when its answer to {@code --version} depends on its bytes alone,
     * or null: a script ({@code #!}, as an asdf or pyenv shim is) or a program whose real file is
     * named otherwise ({@code git} a link to {@code mise}, {@code node} to {@code volta-shim}) may
     * be a version manager's shim, whose answer moves with the directory and the shell.
     */
    static @Nullable Path ownBinary(Path exe) {
        try {
            Path real = exe.toRealPath();
            Path realName = real.getFileName();
            Path exeName = exe.getFileName();
            if (realName == null || exeName == null || !realName.equals(exeName)) return null;
            byte[] head = new byte[2];
            try (var in = Files.newInputStream(real)) {
                if (in.readNBytes(head, 0, 2) == 2 && head[0] == '#' && head[1] == '!') return null;
            }
            return real;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String version(Path exe, Path dir, @Nullable String path) {
        // Off a request the scope hands out a fresh map every time, so nothing is remembered.
        Map<VersionKey, String> versions = RequestScope.current().get(VERSIONS, k -> new ConcurrentHashMap<>());
        VersionKey key = new VersionKey(exe, dir, path);
        String known = versions.get(key);
        if (known != null) return known;
        Answer a = probeOnce(exe, dir, path, VERSION_TIMEOUT_MILLIS);
        // A version line is remembered for the request, as a shim may answer another one in another
        // directory; a hang for as long as the file holds still (HUNG); a tool that exited without a
        // version line is asked again.
        if (a.timedOut() || !a.line().equals(NO_VERSION)) versions.put(key, a.line());
        return a.line();
    }

    /** {@link #probe}, unless the file at {@code exe} hung on it before and has not changed since. */
    static Answer probeOnce(Path exe, Path dir, @Nullable String path, long timeoutMillis) {
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(exe);
        if (stamp == null) return probe(exe, dir, path, timeoutMillis);
        Answer[] asked = {null};
        boolean hung = HUNG.get(exe, stamp, () -> {
            asked[0] = probe(exe, dir, path, timeoutMillis);
            return asked[0].timedOut();
        });
        if (asked[0] != null) return asked[0];
        return hung ? new Answer(NO_VERSION, true) : probe(exe, dir, path, timeoutMillis);
    }

    /** What {@code --version} gave: its first line or {@link #NO_VERSION}, and whether it hung. */
    record Answer(String line, boolean timedOut) {}

    /** The first non-blank line {@code --version} prints, or a word saying why there is none. */
    static String probe(Path exe, Path dir, @Nullable String path) {
        return probe(exe, dir, path, VERSION_TIMEOUT_MILLIS).line();
    }

    /**
     * As {@link #probe(Path, Path, String)}, giving up after {@code timeoutMillis}. Run in {@code
     * dir} with the caller's environment less anything named like a credential and {@code path} as its {@code PATH}, in the C locale. The output is read on
     * its own thread, so a tool that prints nothing and never exits cannot hold the caller past the
     * bound.
     */
    static Answer probe(Path exe, Path dir, @Nullable String path, long timeoutMillis) {
        ProcessBuilder pb = new ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true);
        if (Files.isDirectory(dir)) pb.directory(dir.toFile());
        Map<String, String> env = ChildEnv.copyOf(BuildEnv.callerEnv());
        // A version line needs no credential: a forge token or a cloud key the request forwarded
        // for the engine's own resolvers stays out of a tool that is only asked its version.
        env.keySet().removeIf(BuildEnv::looksSecret);
        if (path != null) env.put("PATH", path);
        // Asked in the C locale, whatever the caller's: a translated line (bash's "Version" under
        // de_DE) is the same tool, and the key must not move with the shell's language.
        env.keySet().removeIf(name -> name.equals("LANGUAGE") || name.startsWith("LC_"));
        env.put("LC_ALL", "C");
        env.put("LANG", "C");
        ChildEnv.replace(pb, env);
        try {
            Process p = JobWorkers.start(pb);
            Processes.Captured c = Processes.capture(p, null, Duration.ofMillis(timeoutMillis), 4096);
            if (c.timedOut()) return new Answer(NO_VERSION, true);
            String line = c.output()
                    .lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .findFirst()
                    .orElse(NO_VERSION);
            return new Answer(line, false);
        } catch (IOException e) {
            return new Answer(NO_VERSION, false);
        }
    }
}
