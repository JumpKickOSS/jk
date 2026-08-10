// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.JkCacheConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Opportunistic cache prune after sync/build: fires when auto-prune is on and
 * {@code .last-pruned} is missing or older than the interval. Spawns detached
 * {@code jk cache clean --background} (parent does not wait). {@link #shouldRun} is also the
 * engine's idle-path cadence check.
 */
public final class CachePruneScheduler {

    /** Sentinel filename written after each successful prune. */
    public static final String LAST_PRUNED_FILE = ".last-pruned";

    private CachePruneScheduler() {}

    /**
     * Run if cued; do nothing otherwise. Errors are swallowed — the opportunistic prune is a hygiene
     * optimisation, never load-bearing.
     */
    public static void maybeRun(JkCacheConfig config, Path cacheRoot, String jkExe) {
        if (!config.autoPrune()) return;
        try {
            if (!shouldRun(config, cacheRoot)) return;
            spawnDetached(config, cacheRoot, jkExe);
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    /** True if the configured cadence calls for a prune now. */
    public static boolean shouldRun(JkCacheConfig config, Path cacheRoot) throws IOException {
        Path stamp = cacheRoot.resolve(LAST_PRUNED_FILE);
        if (!Files.isRegularFile(stamp)) return true;
        long last;
        try {
            last = Long.parseLong(
                    Files.readString(stamp, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return true;
        }
        long intervalMillis = (long) config.pruneIntervalDays() * 24L * 60L * 60L * 1000L;
        return (System.currentTimeMillis() - last) > intervalMillis;
    }

    /** Build the equivalent of {@code jk cache clean --background} command line. */
    static List<String> commandFor(JkCacheConfig config, Path cacheRoot, String jkExe) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(jkExe);
        cmd.add("cache");
        cmd.add("prune");
        cmd.add("--background");
        cmd.add("--cache-dir");
        cmd.add(cacheRoot.toAbsolutePath().toString());
        cmd.add("--older-than");
        cmd.add(Integer.toString(config.recordTtlDays()));
        return cmd;
    }

    private static void spawnDetached(JkCacheConfig config, Path cacheRoot, String jkExe) throws IOException {
        if (jkExe == null || jkExe.isBlank()) return;
        ProcessBuilder pb = new ProcessBuilder(commandFor(config, cacheRoot, jkExe));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        // Close stdin so the child sees EOF immediately on any read.
        // The parent doesn't wait for the child — it'll outlive us.
        p.getOutputStream().close();
    }

    /**
     * Best-effort resolution of the absolute path to the running {@code jk} binary. Lives here so
     * engine doesn't depend on cli.
     *
     * <p>Order: {@code JK_EXE} override → {@code /proc/self/exe} / {@link ProcessHandle} when that
     * path is not a bare JVM launcher → Gradle/JVM installDist layout ({@code <app>/lib/*.jar} with
     * sibling {@code <app>/bin/jk}). A filename heuristic like {@code contains("jk")} is wrong in
     * both directions: it rejects a renamed native binary and accepts a java launcher installed
     * under a path that happens to contain "jk".
     */
    public static Optional<String> resolveJkExe() {
        String envOverride = System.getenv("JK_EXE");
        if (envOverride != null && !envOverride.isBlank()) {
            return Optional.of(envOverride);
        }
        Path candidate = null;
        try {
            // Authoritative on Linux, for native images and JVMs alike.
            candidate = Files.readSymbolicLink(Path.of("/proc/self/exe"));
        } catch (IOException | RuntimeException ignored) {
            // not Linux (or /proc unavailable) — fall through
        }
        if (candidate == null) {
            try {
                candidate =
                        ProcessHandle.current().info().command().map(Path::of).orElse(null);
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        if (candidate != null && !isJavaLauncher(candidate)) {
            return Optional.of(candidate.toAbsolutePath().toString());
        }
        // JVM dist (installDist / application plugin): process is `java` with classpath under
        // <app>/lib/; the re-invokable launcher is <app>/bin/jk.
        return resolveFromJvmInstallLayout(System.getProperty("java.class.path", ""));
    }

    /**
     * When the process is a JVM launcher, recover the installDist/application script path from the
     * classpath: any {@code …/lib/<jar-or-dir>} entry implies {@code …/bin/jk} (or {@code jk.bat}).
     * Package-visible for tests.
     */
    static Optional<String> resolveFromJvmInstallLayout(String classPath) {
        if (classPath == null || classPath.isBlank()) return Optional.empty();
        String sep = System.getProperty("path.separator", ":");
        for (String entry : classPath.split(java.util.regex.Pattern.quote(sep))) {
            if (entry.isBlank()) continue;
            Path p;
            try {
                p = Path.of(entry).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                continue;
            }
            Path lib = libDirOf(p);
            if (lib == null) continue;
            Path home = lib.getParent();
            if (home == null) continue;
            for (String name : List.of("jk", "jk.bat", "jk.cmd")) {
                Path script = home.resolve("bin").resolve(name);
                if (Files.isRegularFile(script)) {
                    return Optional.of(script.toAbsolutePath().toString());
                }
            }
        }
        return Optional.empty();
    }

    /** {@code path} is a {@code lib/} directory, or a file directly under one. */
    private static Path libDirOf(Path path) {
        if (Files.isDirectory(path) && "lib".equals(fileName(path))) return path;
        Path parent = path.getParent();
        if (parent != null && "lib".equals(fileName(parent))) return parent;
        return null;
    }

    private static String fileName(Path p) {
        Path name = p.getFileName();
        return name == null ? "" : name.toString();
    }

    private static boolean isJavaLauncher(Path p) {
        String name = fileName(p).toLowerCase(java.util.Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }
}
