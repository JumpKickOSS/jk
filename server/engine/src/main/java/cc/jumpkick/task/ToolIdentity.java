// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The identity of an external tool a suite shells out to — {@code node}, {@code git}, {@code
 * protoc} — as a run-tests input: where the name resolves on the PATH the test JVM gets, and what
 * that executable says to {@code --version}. An upgrade changes the version line; a switch of
 * toolchain manager changes the path; a tool that is not on the PATH is {@code missing}, so a
 * suite that went green with the tool and now runs without it is retested rather than replayed.
 *
 * <p>Memoized per executable by its path, size and modification time: the version subprocess runs
 * once per binary per engine lifetime, not once per build.
 */
public final class ToolIdentity {

    /** The value a declared tool takes when the PATH has no executable of that name. */
    public static final String MISSING = "missing";

    /** A tool that hangs on {@code --version} is not going to tell us anything better later. */
    private static final long VERSION_TIMEOUT_MILLIS = 5_000;

    private static final Map<String, String> VERSIONS = new ConcurrentHashMap<>();

    private ToolIdentity() {}

    /**
     * {@code <path>=<version line>} for {@code tool} resolved on {@code path} (the PATH the test JVM
     * gets), or {@link #MISSING}.
     */
    public static String of(String tool, @Nullable String path) {
        Path exe = resolve(tool, path);
        if (exe == null) return MISSING;
        return exe + "=" + version(exe);
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

    private static String version(Path exe) {
        String key;
        try {
            BasicFileAttributes attrs = Files.readAttributes(exe, BasicFileAttributes.class);
            key = exe + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return "unreadable";
        }
        return VERSIONS.computeIfAbsent(key, k -> probe(exe));
    }

    /** The first non-blank line {@code --version} prints, or a word saying why there is none. */
    static String probe(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readNBytes(4096);
            if (!p.waitFor(VERSION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return "no-version";
            }
            return new String(out, StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .findFirst()
                    .orElse("no-version");
        } catch (IOException e) {
            return "no-version";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "no-version";
        }
    }
}
